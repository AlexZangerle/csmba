package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.csm.Csml.EventChannel
import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import at.ac.uibk.dps.cirrina.spec.Event
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.bytes.ZBytes
import io.zenoh.keyexpr.KeyExpr
import io.zenoh.sample.Sample
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

fun main(args: Array<String>) {
    val durationSec = args.find { it.startsWith("--duration=") }?.substringAfter("=")?.toLong() ?: 900L
    val startupWaitSec = args.find { it.startsWith("--startup-wait=") }?.substringAfter("=")?.toLong() ?: 15L
    val metricsDir = args.find { it.startsWith("--metrics=") }?.substringAfter("=") ?: "/metrics"
    val zenohConfigPath = args.find { it.startsWith("--zenoh-config=") }?.substringAfter("=")

    println("  Full BMS Simulation (S6)")
    println("  Duration:     ${durationSec}s")
    println("  Metrics:      $metricsDir")

    val config = if (zenohConfigPath != null) Config.fromFile(File(zenohConfigPath)).getOrThrow()
                 else Config.default()
    val session = Zenoh.open(config).getOrThrow()
    log("Connected to Zenoh")

    val scheduler = Executors.newScheduledThreadPool(4)

    // Emergency state tracking
    val gasLeakActive = AtomicBoolean(false)
    val arcFaultActive = AtomicBoolean(false)
    val fireAlarmActive = AtomicBoolean(false)
    val smokeAlertActive = AtomicBoolean(false)

    fun publish(topic: String, data: List<ContextVariable> = ArrayList()) {
        val event = Event(
            topic = topic,
            channel = EventChannel.PERIPHERAL,
            data = data,
            source = "simulation",
            emittedTime = epochNanos(),
        )
        val key = KeyExpr.tryFrom("events/peripheral/$topic").getOrThrow()
        session.put(key, ZBytes.from(Serializer.serialize(event))).getOrThrow()
    }

    fun cv(name: String, value: Any?) = ContextVariable(name, value)

    // Subscribe to emergency events for automatic reset
    val subscriber = session.declareAdvancedSubscriber(
        KeyExpr.tryFrom("events/**").getOrThrow(),
        callback = { sample: Sample ->
            try {
                val event: Event = Serializer.deserialize(sample.payload.toBytes())
                when (event.topic) {
                    "gasLeakDetected" -> {
                        if (gasLeakActive.compareAndSet(false, true)) {
                            log("[EMERGENCY] Gas leak detected — will reset in 10s")
                            scheduler.schedule({
                                publish("gasPurged")
                                gasLeakActive.set(false)
                                log("[RESET] gasPurged sent")
                            }, 10, TimeUnit.SECONDS)
                        }
                    }
                    "arcFaultDetected" -> {
                        if (arcFaultActive.compareAndSet(false, true)) {
                            log("[EMERGENCY] Arc fault detected — will reset in 10s")
                            scheduler.schedule({
                                publish("electricalReset")
                                arcFaultActive.set(false)
                                log("[RESET] electricalReset sent")
                            }, 10, TimeUnit.SECONDS)
                        }
                    }
                    "fireAlarm" -> {
                        if (fireAlarmActive.compareAndSet(false, true)) {
                            log("[EMERGENCY] Fire alarm — will disarm in 10s")
                            scheduler.schedule({
                                publish("disarmFireAlarm")
                                fireAlarmActive.set(false)
                                log("[RESET] disarmFireAlarm sent")
                            }, 10, TimeUnit.SECONDS)
                        }
                    }
                    "smokeAlert" -> {
                        if (smokeAlertActive.compareAndSet(false, true)) {
                            log("[EMERGENCY] Smoke alert — will disarm in 10s")
                            scheduler.schedule({
                                publish("disarmSmokeAlert")
                                smokeAlertActive.set(false)
                                log("[RESET] disarmSmokeAlert sent")
                            }, 10, TimeUnit.SECONDS)
                        }
                    }
                }
            } catch (_: Exception) {}
        },
    ).getOrThrow()

    log("Waiting ${startupWaitSec}s for Cirrina startup...")
    Thread.sleep(startupWaitSec * 1000)

    log("Starting sensor loops...")

    // Occupancy sensor — every 5s
    scheduler.scheduleAtFixedRate({
        try {
            publish("sensorOccupancyReceived", listOf(cv("imageData", ByteArray(100))))
        } catch (e: Exception) {
            log("[WARN] occupancy publish failed: ${e.message}")
        }
    }, 0, 5, TimeUnit.SECONDS)

    // Fire sensor — every 10s
    scheduler.scheduleAtFixedRate({
        try {
            publish("sensorFireDataReceived", listOf(
                cv("imageData", ByteArray(100)),
                cv("zoneId", "Office"),
            ))
        } catch (e: Exception) {
            log("[WARN] fire sensor publish failed: ${e.message}")
        }
    }, 2, 10, TimeUnit.SECONDS)

    // Authentication request — every 15s
    scheduler.scheduleAtFixedRate({
        try {
            publish("authenticationRequest", listOf(cv("cardId", "CARD-001")))
        } catch (e: Exception) {
            log("[WARN] auth publish failed: ${e.message}")
        }
    }, 5, 15, TimeUnit.SECONDS)

    log("All loops running. Waiting ${durationSec}s...")
    Thread.sleep(durationSec * 1000)

    log("Duration complete. Shutting down.")
    scheduler.shutdown()
    scheduler.awaitTermination(10, TimeUnit.SECONDS)
    subscriber.close()
    session.close()
}
