package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.ContextVariable
import io.javalin.Javalin
import io.javalin.http.Context
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

data class BenchmarkEntry(val epochNs: Long, val eventType: String, val detail: String)

fun log(msg: String) {
    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    println("  [$time] $msg")
}

fun epochNanos(): Long {
    val now = Clock.System.now()
    return (now.epochSeconds * 1_000_000_000L) + now.nanosecondsOfSecond
}

object BenchmarkLog {
    private val entries = ConcurrentLinkedQueue<BenchmarkEntry>()
    private val csvFile = File("/metrics/building_service.csv")
    val callCount = AtomicInteger(0)

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
    println("  Building Service — Full BMS (S6)")

    val gasFaultProb = 0.05
    val arcFaultProb = 0.05
    val fireFaultProb = 0.05
    val highTempProb = 0.02

    val app = Javalin.create().start(8005)

    // === Gas Safety ===
    app.post("/checkGasFault") { ctx ->
        val location = if (ThreadLocalRandom.current().nextDouble() < gasFaultProb) {
            log("[GAS] FAULT detected in Zone-A")
            BenchmarkLog.logEvent("gas_fault", "Zone-A")
            "Zone-A"
        } else {
            log("[GAS] poll — no fault")
            "none"
        }
        BenchmarkLog.logEvent("gas_poll", "checkGasFault")
        respondWith(ctx, ContextVariable("gasLeakLocation", location))
    }

    app.post("/closeGasValve") { ctx ->
        log("[GAS] closeGasValve")
        BenchmarkLog.logEvent("gas_action", "closeGasValve")
        respondEmpty(ctx)
    }

    app.post("/cutPower") { ctx ->
        log("[GAS] cutPower")
        BenchmarkLog.logEvent("gas_action", "cutPower")
        respondEmpty(ctx)
    }

    app.post("/gasLeakPurged") { ctx ->
        log("[GAS] gasLeakPurged — reset complete")
        BenchmarkLog.logEvent("gas_reset", "gasLeakPurged")
        respondEmpty(ctx)
    }

    // === Electrical Safety ===
    app.post("/checkArcFault") { ctx ->
        val location = if (ThreadLocalRandom.current().nextDouble() < arcFaultProb) {
            log("[ELEC] FAULT detected at Panel-B")
            BenchmarkLog.logEvent("elec_fault", "Panel-B")
            "Panel-B"
        } else {
            log("[ELEC] poll — no fault")
            "none"
        }
        BenchmarkLog.logEvent("elec_poll", "checkArcFault")
        respondWith(ctx, ContextVariable("arcFaultLocation", location))
    }

    app.post("/tripCircuitBreaker") { ctx ->
        log("[ELEC] tripCircuitBreaker")
        BenchmarkLog.logEvent("elec_action", "tripCircuitBreaker")
        respondEmpty(ctx)
    }

    app.post("/ackEletrical") { ctx ->
        log("[ELEC] ackElectrical — reset complete")
        BenchmarkLog.logEvent("elec_reset", "ackElectrical")
        respondEmpty(ctx)
    }

    // === Fire Detection ===
    app.post("/detectFire") { ctx ->
        val vars = parseInput(ctx)
        val zone = getValue(vars, "zoneId") as? String ?: "unknown"
        val result = if (ThreadLocalRandom.current().nextDouble() < fireFaultProb) {
            log("[FIRE] DETECTED in $zone")
            BenchmarkLog.logEvent("fire_detected", zone)
            "fire"
        } else {
            log("[FIRE] poll ($zone) — no fire")
            "none"
        }
        respondWith(ctx,
            ContextVariable("fireDetectionResult", result),
            ContextVariable("emergencyInRoom", zone),
        )
    }

    app.post("/closeFireDoor") { ctx ->
        val vars = parseInput(ctx)
        val doorId = getValue(vars, "doorId") as? String ?: "unknown"
        log("[FIRE] closeFireDoor $doorId")
        BenchmarkLog.logEvent("door_close", doorId)
        respondEmpty(ctx)
    }

    app.post("/openFireDoor") { ctx ->
        val vars = parseInput(ctx)
        val doorId = getValue(vars, "doorId") as? String ?: "unknown"
        log("[FIRE] openFireDoor $doorId")
        BenchmarkLog.logEvent("door_open", doorId)
        respondEmpty(ctx)
    }

    // === HVAC ===
    app.post("/setHVAC") { ctx ->
        val vars = parseInput(ctx)
        val mode = getValue(vars, "mode") as? String ?: "unknown"
        val roomId = getValue(vars, "roomId") as? String ?: "unknown"
        log("[HVAC] setHVAC mode=$mode room=$roomId")
        BenchmarkLog.logEvent("hvac", "$mode ($roomId)")
        respondEmpty(ctx)
    }

    app.post("/getIndoorTemp") { ctx ->
        log("[HVAC] getIndoorTemp → 21.0")
        respondWith(ctx, ContextVariable("indoorTemp", 21.0))
    }

    // === Temperature Safety ===
    app.post("/getRoomTemp") { ctx ->
        val temp = if (ThreadLocalRandom.current().nextDouble() < highTempProb) {
            log("[TEMP] HIGH RISK — returning 65.0")
            BenchmarkLog.logEvent("temp_high", "65.0")
            65.0
        } else {
            log("[TEMP] poll → 22.0")
            22.0
        }
        respondWith(ctx, ContextVariable("roomTemp", temp))
    }

    app.post("/highRiskTemp") { ctx ->
        log("[TEMP] highRiskTemp action")
        BenchmarkLog.logEvent("temp_action", "highRisk")
        respondEmpty(ctx)
    }

    // === Occupancy ===
    app.post("/detectOccupancy") { ctx ->
        val detected = ThreadLocalRandom.current().nextDouble() < 0.7
        log("[OCCUPANCY] detectOccupancy → $detected")
        respondWith(ctx, ContextVariable("occupancyDetected", detected))
    }

    app.post("/maintenance") { ctx ->
        log("[OCCUPANCY] maintenance")
        BenchmarkLog.logEvent("occupancy", "maintenance")
        respondEmpty(ctx)
    }

    // === Lighting ===
    app.post("/turnOn") { ctx ->
        log("[LIGHT] turnOn")
        BenchmarkLog.logEvent("lighting", "on")
        respondEmpty(ctx)
    }

    app.post("/turnOff") { ctx ->
        log("[LIGHT] turnOff")
        BenchmarkLog.logEvent("lighting", "off")
        respondEmpty(ctx)
    }

    app.post("/dim") { ctx ->
        log("[LIGHT] dim")
        BenchmarkLog.logEvent("lighting", "dim")
        respondEmpty(ctx)
    }

    app.post("/evacuationLights") { ctx ->
        log("[LIGHT] evacuationLights")
        BenchmarkLog.logEvent("lighting", "evacuation")
        respondEmpty(ctx)
    }

    app.post("/userLevelLight") { ctx ->
        log("[LIGHT] userLevelLight")
        BenchmarkLog.logEvent("lighting", "userLevel")
        respondEmpty(ctx)
    }

    // === Shading ===
    app.post("/blindsOpen") { ctx ->
        log("[SHADING] blindsOpen")
        BenchmarkLog.logEvent("shading", "open")
        respondEmpty(ctx)
    }

    app.post("/blindsHalf") { ctx ->
        log("[SHADING] blindsHalf")
        BenchmarkLog.logEvent("shading", "half")
        respondEmpty(ctx)
    }

    app.post("/blindsClose") { ctx ->
        log("[SHADING] blindsClose")
        BenchmarkLog.logEvent("shading", "close")
        respondEmpty(ctx)
    }

    app.post("/userLevelBlinds") { ctx ->
        log("[SHADING] userLevelBlinds")
        BenchmarkLog.logEvent("shading", "userLevel")
        respondEmpty(ctx)
    }

    app.post("/getOutdoorTemp") { ctx ->
        val temp = 10.0 + ThreadLocalRandom.current().nextDouble() * 15.0
        log("[SHADING] getOutdoorTemp → ${String.format("%.1f", temp)}")
        respondWith(ctx, ContextVariable("outdoorTemp", temp))
    }

    // === Energy Management ===
    app.post("/getEnergyPrice") { ctx ->
        val price = 20.0 + ThreadLocalRandom.current().nextDouble() * 40.0
        log("[ENERGY] getEnergyPrice → ${String.format("%.1f", price)}")
        respondWith(ctx, ContextVariable("energyPrice", price))
    }

    app.post("/checkGridStatus") { ctx ->
        val status = if (ThreadLocalRandom.current().nextDouble() < 0.05) {
            log("[ENERGY] checkGridStatus → demandResponse")
            "demandResponse"
        } else {
            log("[ENERGY] checkGridStatus → normal")
            "normal"
        }
        respondWith(ctx, ContextVariable("gridStatus", status))
    }

    app.post("/normal") { ctx ->
        log("[ENERGY] normal mode")
        BenchmarkLog.logEvent("energy", "normal")
        respondEmpty(ctx)
    }

    app.post("/response") { ctx ->
        log("[ENERGY] response mode")
        BenchmarkLog.logEvent("energy", "response")
        respondEmpty(ctx)
    }

    app.post("/peak") { ctx ->
        log("[ENERGY] peak mode")
        BenchmarkLog.logEvent("energy", "peak")
        respondEmpty(ctx)
    }

    // === Building Schedule ===
    app.post("/getScheduleMode") { ctx ->
        log("[SCHEDULE] getScheduleMode → businessHours")
        respondWith(ctx, ContextVariable("currentSchedule", "businessHours"))
    }

    // === Security ===
    app.post("/authenticateUser") { ctx ->
        log("[SECURITY] authenticateUser → Success")
        respondWith(ctx,
            ContextVariable("userId", "user1"),
            ContextVariable("userRole", "employee"),
            ContextVariable("authenticationStatus", "Success"),
        )
    }

    app.post("/checkAccessRule") { ctx ->
        log("[SECURITY] checkAccessRule → allow")
        respondWith(ctx, ContextVariable("accessDecision", "allow"))
    }

    app.post("/controlDoorLock") { ctx ->
        val vars = parseInput(ctx)
        val command = getValue(vars, "command") as? String ?: "unknown"
        log("[SECURITY] controlDoorLock command=$command")
        BenchmarkLog.logEvent("security", "doorLock:$command")
        respondEmpty(ctx)
    }

    app.post("/notifySecurity") { ctx ->
        log("[SECURITY] notifySecurityPersonnel")
        BenchmarkLog.logEvent("security", "notify")
        respondEmpty(ctx)
    }

    app.post("/initializeZone") { ctx ->
        log("[SECURITY] initializeZone")
        BenchmarkLog.logEvent("security", "initZone")
        respondEmpty(ctx)
    }

    app.post("/getDoorRouteType") { ctx ->
        log("[SECURITY] getDoorRouteType → evacuation=false, zone=zone1")
        respondWith(ctx,
            ContextVariable("isEvacuationRoute", false),
            ContextVariable("zoneId", "zone1"),
        )
    }

    println("  Running on port 8005")
    println("  Fault probabilities: gas=${(gasFaultProb*100).toInt()}%, arc=${(arcFaultProb*100).toInt()}%, fire=${(fireFaultProb*100).toInt()}%, temp=${(highTempProb*100).toInt()}%")
    println("  CSV: /metrics/building_service.csv")
}
