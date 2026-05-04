package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import io.javalin.Javalin
import io.javalin.http.Context
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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

    // Modulo-3 counter: call 0 returns true, calls 1,2 return false
    val callCounter = AtomicInteger(0)
    val hvacReacted = AtomicBoolean(false)
    val lightReacted = AtomicBoolean(false)

    init {
        csvFile.parentFile.mkdirs()
        csvFile.writeText("epoch_ns,event_type,detail\n")
    }

    fun logEvent(eventType: String, detail: String) {
        val ts = epochNanos()
        entries.add(BenchmarkEntry(ts, eventType, detail))
        csvFile.appendText("$ts,$eventType,$detail\n")
    }

    fun reset() {
        entries.clear()
        callCounter.set(0)
        hvacReacted.set(false)
        lightReacted.set(false)
        csvFile.writeText("epoch_ns,event_type,detail\n")
    }

    fun getEntries() = entries.toList()
}

fun parseInput(ctx: Context): List<ContextVariable> {
    val body = ctx.bodyAsBytes()
    return if (body.isEmpty()) ArrayList() else Serializer.deserialize(body)
}

fun getValue(vars: List<ContextVariable>, name: String): Any? =
    vars.firstOrNull { it.name == name }?.value

fun respondWith(ctx: Context, vararg variables: ContextVariable) {
    ctx.contentType("application/x-protobuf")
    ctx.result(Serializer.serialize(variables.toList()))
}

fun respondEmpty(ctx: Context) {
    ctx.status(200)
    ctx.result("")
}

fun main() {
    println("Building Service — Scenario 3 (Comfort Convergence + Energy Saving)")

    val app = Javalin.create().start(8005)

    // Occupancy detection: true on every 3rd call (0, 3, 6, ...), false otherwise
    app.post("/detectOccupancy") { ctx ->
        val n = BenchmarkLog.callCounter.getAndIncrement()
        val result = (n % 3 == 0)
        if (result) {
            BenchmarkLog.hvacReacted.set(false)
            BenchmarkLog.lightReacted.set(false)
        }
        BenchmarkLog.logEvent("occupancy_detect", result.toString())
        respondWith(ctx, ContextVariable("occupancyDetected", result))
    }

    // HVAC consumer — only log first setHVAC(fan) after a positive detection
    app.post("/setHVAC") { ctx ->
        val vars = parseInput(ctx)
        val mode = getValue(vars, "mode") as? String ?: "unknown"
        if (mode == "fan" && BenchmarkLog.hvacReacted.compareAndSet(false, true)) {
            BenchmarkLog.logEvent("hvac_consumer", mode)
            println("[HVAC] $mode")
        }
        respondEmpty(ctx)
    }

    // Lighting consumer — in energy saving mode, occupancyDetected triggers dim not turnOn
    app.post("/dim") { ctx ->
        if (BenchmarkLog.lightReacted.compareAndSet(false, true)) {
            BenchmarkLog.logEvent("light_consumer", "dim")
            println("[LIGHT] dim")
        }
        respondEmpty(ctx)
    }

    // Other endpoints
    app.post("/turnOn") { ctx -> respondEmpty(ctx) }
    app.post("/turnOff") { ctx -> respondEmpty(ctx) }
    app.post("/evacuationLights") { ctx -> respondEmpty(ctx) }
    app.post("/userLevelLight") { ctx -> respondEmpty(ctx) }
    app.post("/getIndoorTemp") { ctx ->
        respondWith(ctx, ContextVariable("indoorTemp", 21.0))
    }
    app.post("/maintenance") { ctx -> respondEmpty(ctx) }

    // Control
    app.post("/benchmarkReset") { ctx -> BenchmarkLog.reset(); ctx.result("reset") }
    app.get("/benchmarkMetrics") { ctx ->
        val sb = StringBuilder("epoch_ns,event_type,detail\n")
        BenchmarkLog.getEntries().forEach { sb.appendLine("${it.epochNs},${it.eventType},${it.detail}") }
        ctx.contentType("text/csv")
        ctx.result(sb.toString())
    }

    println("  Running on :8005, CSV -> /metrics/building_service.csv")
}
