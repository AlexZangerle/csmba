package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.annotations.Unstable
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Clock

fun log(msg: String) {
    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    println("  [$time] $msg")
}

fun epochNanos(): Long {
    val now = Clock.System.now()
    return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

fun cv(name: String, value: Any?) = ContextVariable(name, value)

@OptIn(Unstable::class)
fun main(args: Array<String>) {
    val durationMs = args.find { it.startsWith("--duration=") }?.substringAfter("=")?.toLong() ?: 900_000L
    val intervalMs = args.find { it.startsWith("--interval=") }?.substringAfter("=")?.toLong() ?: 500L
    val metricsDir = args.find { it.startsWith("--metrics=") }?.substringAfter("=") ?: "/metrics"

    println("  Scenario 3 — Comfort Convergence + Energy Saving")
    println("  Duration:  ${durationMs / 1000}s")
    println("  Interval:  ${intervalMs}ms between sensor events")

    val session = Zenoh.open(Config.default()).getOrThrow()
    log("Connected to Zenoh")

    val simCsv = File("$metricsDir/simulation.csv")
    simCsv.parentFile.mkdirs()
    simCsv.writeText("epoch_ns,event_type\n")

    val sensorKey = KeyExpr.tryFrom("events/peripheral/sensorOccupancyReceived").getOrThrow()

    fun publishEvent(topic: String, data: List<ContextVariable> = ArrayList(), target: String = "") {
        val event = Event(
            topic = topic,
            channel = EventChannel.PERIPHERAL,
            data = data,
            source = "peripheral",
            target = target,
            emittedTime = epochNanos(),
        )
        val key = KeyExpr.tryFrom("events/peripheral/$topic").getOrThrow()
        session.put(key, ZBytes.from(Serializer.serialize(event))).getOrThrow()
    }

    fun publishSensor() {
        publishEvent("sensorOccupancyReceived", listOf(cv("imageData", "bench")), "roomOccupancy")
    }

    // Wait for Cirrina to be ready
    log("Waiting 15s for Cirrina startup...")
    Thread.sleep(15000)

    // Activate energy saving mode on lighting
    log("Activating energy saving mode...")
    publishEvent("energySaving")
    Thread.sleep(1000)

    log("Sending sensor events every ${intervalMs}ms...")

    val endTime = System.currentTimeMillis() + durationMs
    var sent = 0

    while (System.currentTimeMillis() < endTime) {
        publishSensor()
        sent++
        val phase = (sent - 1) % 3
        val label = when (phase) {
            0 -> "sensor_detect"
            1 -> "sensor_transient"
            2 -> "sensor_vacant"
            else -> "sensor"
        }
        simCsv.appendText("${epochNanos()},$label\n")
        Thread.sleep(intervalMs)
    }

    log("Done — sent $sent sensor events (${sent / 3} cycles)")

    session.close()
}
