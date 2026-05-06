package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import io.javalin.Javalin
import io.javalin.http.Context
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

data class BenchmarkEntry(val epochNs: Long, val eventType: String, val detail: String)

fun epochNanos(): Long {
    val now = Clock.System.now()
    return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

object BenchmarkLog {
    private val entries = ConcurrentLinkedQueue<BenchmarkEntry>()
    private val csvFile = File("/metrics/building_service.csv")
    val detectFireCalls = AtomicInteger(0)
    val fireDetections = AtomicInteger(0)

    init {
        csvFile.parentFile.mkdirs()
        csvFile.writeText("epoch_ns,event_type,detail\n")
    }

    fun logEvent(eventType: String, detail: String) {
        val ts = epochNanos()
        entries.add(BenchmarkEntry(ts, eventType, detail))
        csvFile.appendText("$ts,$eventType,$detail\n")
    }
}

fun parseInput(ctx: Context): List<ContextVariable> {
    val body = ctx.bodyAsBytes()
    return if (body.isEmpty()) ArrayList()
    else Serializer.deserialize(body)
}

fun getValue(vars: List<ContextVariable>, name: String): Any? =
    vars.firstOrNull { it.name == name }?.value

fun respondWith(ctx: Context, vararg variables: ContextVariable) {
    val bytes = Serializer.serialize(variables.toList())
    ctx.contentType("application/x-protobuf")
    ctx.result(bytes)
}

fun respondEmpty(ctx: Context) {
    ctx.status(200)
    ctx.result("")
}

fun main() {
    println("  Building Service — Fire Convergence")

    val fireProbability = 0.05

    val app = Javalin.create().start(8005)

    app.post("/detectFire") { ctx ->
        val vars = parseInput(ctx)
        val zone = getValue(vars, "zoneId") as? String ?: "unknown"
        BenchmarkLog.detectFireCalls.incrementAndGet()

        val result = if (ThreadLocalRandom.current().nextDouble() < fireProbability) {
            BenchmarkLog.fireDetections.incrementAndGet()
            BenchmarkLog.logEvent("fire_detected", zone)
            "fire"
        } else {
            "none"
        }

        respondWith(
            ctx,
            ContextVariable("fireDetectionResult", result),
            ContextVariable("emergencyInRoom", zone),
        )
    }

    app.post("/closeFireDoor") { ctx ->
        val vars = parseInput(ctx)
        val doorId = getValue(vars, "doorId") as? String ?: "unknown"
        BenchmarkLog.logEvent("door_close", doorId)
        respondEmpty(ctx)
    }

    app.post("/openFireDoor") { ctx ->
        val vars = parseInput(ctx)
        val doorId = getValue(vars, "doorId") as? String ?: "unknown"
        BenchmarkLog.logEvent("door_open", doorId)
        respondEmpty(ctx)
    }

    println("  Running on port 8005")
    println("  Fire probability: ${(fireProbability * 100).toInt()}%")
    println("  CSV: /metrics/building_service.csv")
}
