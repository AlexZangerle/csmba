package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock

fun log(msg: String) {
  val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
  println("  [$time] $msg")
}

fun epochNanos(): Long {
  val now = Clock.System.now()
  return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

fun main(args: Array<String>) {
  val sensors = args.find { it.startsWith("--sensors=") }?.substringAfter("=")?.toInt() ?: 5
  val intervalMs = args.find { it.startsWith("--interval=") }?.substringAfter("=")?.toLong() ?: 5000L
  val durationSec = args.find { it.startsWith("--duration=") }?.substringAfter("=")?.toLong() ?: 900L
  val cooldownMs = args.find { it.startsWith("--cooldown=") }?.substringAfter("=")?.toLong() ?: 15000L
  val startupWaitSec = args.find { it.startsWith("--startup-wait=") }?.substringAfter("=")?.toLong() ?: 10L
  val metricsDir = args.find { it.startsWith("--metrics=") }?.substringAfter("=") ?: "/metrics"
  val zenohConfigPath = args.find { it.startsWith("--zenoh-config=") }?.substringAfter("=")

  println("  Fire Convergence Benchmark")
  println("  Sensors:      $sensors")
  println("  Interval:     ${intervalMs}ms")
  println("  Duration:     ${durationSec}s")
  println("  Cooldown:     ${cooldownMs}ms")
  println("  Metrics:      $metricsDir")

  val config = if (zenohConfigPath != null) Config.fromFile(File(zenohConfigPath)).getOrThrow()
  else Config.default()
  val session = Zenoh.open(config).getOrThrow()
  log("Connected to Zenoh")

  val executor = Executors.newFixedThreadPool(minOf(sensors, 16))

  val zones = listOf(
    "Office", "ServerRoom", "Lobby", "Hallway", "Cafeteria",
    "MeetingRoom1", "MeetingRoom2", "Storage", "Reception", "Parking",
    "Floor1", "Floor2", "Floor3", "Floor4", "Floor5",
    "Basement", "Rooftop", "Laboratory", "Workshop", "Archive",
  )

  val simCsv = File("$metricsDir/simulation.csv")
  simCsv.parentFile.mkdirs()
  simCsv.writeText("iteration,sensors,fire_started_ns,fire_detected_ns,response_time_ns,rounds\n")

  val eventKey = KeyExpr.tryFrom("events/peripheral/sensorFireDataReceived").getOrThrow()
  val disarmFireKey = KeyExpr.tryFrom("events/peripheral/disarmFireAlarm").getOrThrow()
  val disarmSmokeKey = KeyExpr.tryFrom("events/peripheral/disarmSmokeAlert").getOrThrow()

  fun publishEvent(topic: String, key: KeyExpr, data: List<ContextVariable> = ArrayList()) {
    val event = Event(
      topic = topic,
      channel = EventChannel.PERIPHERAL,
      data = data,
      target = "fire",
      source = "simulation",
      emittedTime = epochNanos(),
    )
    session.put(key, ZBytes.from(Serializer.serialize(event))).getOrThrow()
  }

  fun publishSensorBurst() {
    val futures = (0 until sensors).map { i ->
      executor.submit {
        val zone = zones[i % zones.size]
        val data = listOf(
          ContextVariable("imageData", ByteArray(100)),
          ContextVariable("zoneId", zone),
        )
        publishEvent("sensorFireDataReceived", eventKey, data)
      }
    }
    futures.forEach { it.get() }
  }

  fun reset() {
    publishEvent("disarmFireAlarm", disarmFireKey)
    Thread.sleep(2000)
    publishEvent("disarmSmokeAlert", disarmSmokeKey)
  }

  // Subscribe to fireAlarm
  val fireAlarmDetectedNs = AtomicLong(0)

  val subscriber = session.declareAdvancedSubscriber(
    KeyExpr.tryFrom("events/**/fireAlarm").getOrThrow(),
    callback = { sample ->
      val receivedNs = epochNanos()
      fireAlarmDetectedNs.compareAndSet(0, receivedNs)
    },
  ).getOrThrow()

  log("Waiting ${startupWaitSec}s for Cirrina startup...")
  Thread.sleep(startupWaitSec * 1000)

  val deadlineNs = epochNanos() + (durationSec * 1_000_000_000L)
  var iteration = 0

  log("Starting benchmark...")

  while (epochNanos() < deadlineNs) {
    iteration++
    fireAlarmDetectedNs.set(0)

    val fireStartedNs = epochNanos()
    var round = 0

    while (fireAlarmDetectedNs.get() == 0L && epochNanos() < deadlineNs) {
      round++
      publishSensorBurst()
      log("  [$iteration] Round $round: sent $sensors sensors")
      Thread.sleep(intervalMs)
    }

    val detectedNs = fireAlarmDetectedNs.get()
    if (detectedNs == 0L) {
      log("  Time expired during iteration $iteration")
      break
    }

    val responseMs = (detectedNs - fireStartedNs) / 1_000_000.0
    log("  [$iteration] Detected after $round rounds (${String.format("%.1f", responseMs)}ms)")

    simCsv.appendText("$iteration,$sensors,$fireStartedNs,$detectedNs,${detectedNs - fireStartedNs},$round\n")

    log("  [$iteration] Cooldown ${cooldownMs}ms...")
    Thread.sleep(cooldownMs)

    if (epochNanos() < deadlineNs) {
      reset()
      log("  [$iteration] Reset, waiting for settle...")
      Thread.sleep(cooldownMs)
    }
  }

  log("Done. $iteration iterations, $sensors sensors, resetting ...")
  reset()

  subscriber.close()
  executor.shutdown()
  session.close()
}
