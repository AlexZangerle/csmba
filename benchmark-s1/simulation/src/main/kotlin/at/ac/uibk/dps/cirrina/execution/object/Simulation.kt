package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.Event
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.annotations.Unstable
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.sample.Sample
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

fun log(msg: String) {
  val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
  println("  [$time] $msg")
}

fun epochNanos(): Long {
  val now = Clock.System.now()
  return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

@OptIn(Unstable::class)
fun main(args: Array<String>) {
  val durationMs =
    args.find { it.startsWith("--duration=") }?.substringAfter("=")?.toLong() ?: 900_000L
  val settleMs = args.find { it.startsWith("--settle=") }?.substringAfter("=")?.toLong() ?: 100L
  val metricsDir = args.find { it.startsWith("--metrics=") }?.substringAfter("=") ?: "/metrics"
  val circuit = args.find { it.startsWith("--circuit=") }?.substringAfter("=") ?: "both"

  val doGas = circuit == "gas" || circuit == "both"
  val doElec = circuit == "electrical" || circuit == "both"

  println("  Scenario 1 — Circuit Cost Simulation")
  println("  Circuit:   $circuit")
  println("  Duration:  ${durationMs / 1000}s")
  println("  Settle:    ${settleMs}ms")

  val session = Zenoh.open(Config.default()).getOrThrow()
  log("Connected to Zenoh")

  val simCsv = File("$metricsDir/simulation.csv")
  simCsv.parentFile.mkdirs()
  simCsv.writeText("epoch_ns,event_type\n")

  val gasNeedsReset = AtomicBoolean(false)
  val elecNeedsReset = AtomicBoolean(false)

  val subscriber =
    session
      .declareAdvancedSubscriber(
        KeyExpr.tryFrom("events/**").getOrThrow(),
        callback = { sample: Sample ->
          try {
            val bytes = sample.payload.toBytes()
            val event: Event = Serializer.deserialize(bytes)
            when (event.topic) {
              "gasLeakDetected" ->
                if (doGas) {
                  gasNeedsReset.set(true)
                  simCsv.appendText("${epochNanos()},gasLeakDetected\n")
                }

              "arcFaultDetected" ->
                if (doElec) {
                  elecNeedsReset.set(true)
                  simCsv.appendText("${epochNanos()},arcFaultDetected\n")
                }
            }
          } catch (_: Exception) {
          }
        },
      )
      .getOrThrow()

  fun publish(topic: String) {
    val event =
      Event(
        topic = topic,
        channel = EventChannel.PERIPHERAL,
        data = ArrayList(),
        source = "peripheral",
        emittedTime = epochNanos(),
      )
    val key = KeyExpr.tryFrom("events/peripheral/$topic").getOrThrow()
    session.put(key, ZBytes.from(Serializer.serialize(event))).getOrThrow()
  }

  log("Running for ${durationMs / 1000}s...")

  val endTime = System.currentTimeMillis() + durationMs
  var gasResets = 0
  var elecResets = 0

  while (System.currentTimeMillis() < endTime) {
    if (doGas && gasNeedsReset.compareAndSet(true, false)) {
      Thread.sleep(settleMs)
      publish("gasPurged")
      gasResets++
      simCsv.appendText("${epochNanos()},gasPurged\n")
    }

    if (doElec && elecNeedsReset.compareAndSet(true, false)) {
      Thread.sleep(settleMs)
      publish("electricalReset")
      elecResets++
      simCsv.appendText("${epochNanos()},electricalReset\n")
    }

    Thread.sleep(10)
  }

  log("Done — gas: $gasResets cycles, electrical: $elecResets cycles")

  subscriber.close()
  session.close()
}
