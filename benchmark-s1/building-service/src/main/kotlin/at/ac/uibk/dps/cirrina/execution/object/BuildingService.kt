package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import io.javalin.Javalin
import io.javalin.http.Context
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Clock

data class BenchmarkEntry(val epochNs: Long, val eventType: String, val detail: String)

fun epochNanos(): Long {
  val now = Clock.System.now()
  return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

object BenchmarkLog {
  private val entries = ConcurrentLinkedQueue<BenchmarkEntry>()
  private val csvFile = File("/metrics/building_service.csv")
  var gasExpectsConsumer = false
  var elecExpectsConsumer = false

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
    csvFile.writeText("epoch_ns,event_type,detail\n")
  }

  fun getEntries(): List<BenchmarkEntry> = entries.toList()
}

fun parseInput(ctx: Context): List<ContextVariable> {
  val body = ctx.bodyAsBytes()
  return if (body.isEmpty()) ArrayList() else Serializer.deserialize(body)
}

fun getValue(vars: List<ContextVariable>, name: String): Any? =
  vars.firstOrNull { it.name == name }?.value

fun respondWith(ctx: Context, vararg variables: ContextVariable) {
  val list = variables.toList()
  val bytes = Serializer.serialize(list)
  ctx.contentType("application/x-protobuf")
  ctx.result(bytes)
}

fun respondEmpty(ctx: Context) {
  ctx.status(200)
  ctx.result("")
}

fun main() {
  println("Building Service — Scenario 1")

  val app = Javalin.create().start(8005)

  // Gas Detection
  app.post("/checkGasFault") { ctx ->
    BenchmarkLog.gasExpectsConsumer = true
    BenchmarkLog.logEvent("gas_poll", "checkGasFault")
    respondWith(ctx, ContextVariable("gasLeakLocation", "Zone-A"))
  }

  // Gas Actions
  app.post("/closeGasValve") { ctx ->
    BenchmarkLog.logEvent("gas_action", "closeGasValve")
    respondEmpty(ctx)
  }

  app.post("/cutPower") { ctx ->
    BenchmarkLog.logEvent("gas_action", "cutPower")
    respondEmpty(ctx)
  }

  app.post("/gasLeakPurged") { ctx ->
    BenchmarkLog.logEvent("gas_reset", "gasLeakPurged")
    respondEmpty(ctx)
  }

  // HVAC Consumer
  app.post("/setHVAC") { ctx ->
    val vars = parseInput(ctx)
    val mode = getValue(vars, "mode") as? String ?: "unknown"
    if (BenchmarkLog.gasExpectsConsumer) {
      BenchmarkLog.gasExpectsConsumer = false
      BenchmarkLog.logEvent("gas_consumer", mode)
      println("[HVAC] $mode")
    }
    respondEmpty(ctx)
  }

  // Arc Fault Detection
  app.post("/checkArcFault") { ctx ->
    BenchmarkLog.elecExpectsConsumer = true
    BenchmarkLog.logEvent("elec_poll", "checkArcFault")
    respondWith(ctx, ContextVariable("arcFaultLocation", "Panel-B"))
  }

  // Electrical Actions
  app.post("/tripCircuitBreaker") { ctx ->
    BenchmarkLog.logEvent("elec_action", "tripCircuitBreaker")
    respondEmpty(ctx)
  }

  app.post("/ackEletrical") { ctx ->
    BenchmarkLog.logEvent("elec_reset", "ackElectrical")
    respondEmpty(ctx)
  }

  // Lighting Consumer
  app.post("/turnOff") { ctx ->
    if (BenchmarkLog.elecExpectsConsumer) {
      BenchmarkLog.elecExpectsConsumer = false
      BenchmarkLog.logEvent("elec_consumer", "turnOff")
      println("[LIGHT] off")
    }
    respondEmpty(ctx)
  }

  app.post("/turnOn") { ctx ->
    if (BenchmarkLog.elecExpectsConsumer) {
      BenchmarkLog.elecExpectsConsumer = false
      BenchmarkLog.logEvent("elec_consumer", "turnOn")
      println("[LIGHT] on")
    }
    respondEmpty(ctx)
  }

  // Unused
  app.post("/dim") { ctx -> respondEmpty(ctx) }
  app.post("/evacuationLights") { ctx -> respondEmpty(ctx) }
  app.post("/userLevelLight") { ctx -> respondEmpty(ctx) }
  app.post("/getIndoorTemp") { ctx -> respondWith(ctx, ContextVariable("indoorTemp", 21.0)) }

  // Benchmark control
  app.post("/benchmarkReset") { ctx ->
    BenchmarkLog.reset()
    println("[BENCHMARK] Reset")
    ctx.result("reset")
  }

  app.get("/benchmarkMetrics") { ctx ->
    val entries = BenchmarkLog.getEntries()
    val sb = StringBuilder()
    sb.appendLine("epoch_ns,event_type,detail")
    entries.forEach { sb.appendLine("${it.epochNs},${it.eventType},${it.detail}") }
    ctx.contentType("text/csv")
    ctx.result(sb.toString())
  }

  println("  Building service running on port 8005")
  println("  CSV logging to /metrics/building_service.csv")
}
