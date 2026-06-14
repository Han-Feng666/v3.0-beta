package com.HanFeng.core.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.HanFeng.data.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

object ThermalThrottleManager {
    enum class Level { NORMAL, LIGHT, HEAVY }

    data class Snapshot(
        val level: Level = Level.NORMAL,
        val batteryTempC: Float? = null,
        val maxThermalZoneTempC: Float? = null,
        val currentMa: Int? = null,
        val charging: Boolean = false,
        val responseBodyLimitBytes: Int = NORMAL_RESPONSE_BODY_LIMIT_BYTES,
        val reason: String = "normal",
        val updatedAt: Long = System.currentTimeMillis()
    )

    private const val SAMPLE_INTERVAL_MILLIS = 30_000L
    private const val CURRENT_HIGH_MA = 500
    private const val LIGHT_BATTERY_TEMP_C = 42f
    private const val HEAVY_BATTERY_TEMP_C = 48f
    private const val RECOVER_TEMP_C = 40f
    private const val RECOVER_HOLD_MILLIS = 120_000L
    const val NORMAL_RESPONSE_BODY_LIMIT_BYTES = 64 * 1024
    const val DEGRADED_RESPONSE_BODY_LIMIT_BYTES = 16 * 1024

    @Volatile private var currentSnapshot = Snapshot()
    private var job: Job? = null
    private var highCurrentStartedAt = 0L
    private var recoverStartedAt = 0L
    private val tempSamples = ArrayDeque<Float>()
    private val currentSamples = ArrayDeque<Int>()

    fun start(context: Context, scope: CoroutineScope, onChanged: (Snapshot) -> Unit) {
        stop()
        job = scope.launch {
            while (isActive) {
                val next = evaluate(context.applicationContext)
                val previous = currentSnapshot
                currentSnapshot = next
                if (next.level != previous.level || next.reason != previous.reason) {
                    LogRepository.append(
                        context,
                        "Thermal throttle state=${next.level} batteryTemp=${next.batteryTempC ?: "unknown"} thermalMax=${next.maxThermalZoneTempC ?: "unknown"} currentMa=${next.currentMa ?: "unknown"} charging=${next.charging} reason=${next.reason} responseLimit=${next.responseBodyLimitBytes}"
                    )
                    onChanged(next)
                }
                delay(SAMPLE_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        currentSnapshot = Snapshot()
        highCurrentStartedAt = 0L
        recoverStartedAt = 0L
        tempSamples.clear()
        currentSamples.clear()
    }

    fun snapshot(): Snapshot = currentSnapshot

    fun responseBodyLimitBytes(): Int = currentSnapshot.responseBodyLimitBytes

    fun isMitmLearningAllowed(): Boolean = currentSnapshot.level == Level.NORMAL

    fun shouldBypassMitmBodyInspection(): Boolean = currentSnapshot.level == Level.HEAVY

    private fun evaluate(context: Context): Snapshot {
        val now = System.currentTimeMillis()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryTempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }
            ?.let { it / 10f }
        val charging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } == true
        val currentMa = readCurrentMa(context)
        val thermalMax = readMaxThermalZoneTempC()
        val maxObservedTemp = listOfNotNull(batteryTempC, thermalMax).maxOrNull()
        maxObservedTemp?.let { addBounded(tempSamples, it, 4) }
        currentMa?.let { addBounded(currentSamples, it, 4) }
        val avgTemp = averageFloatOrNull(tempSamples)
        val avgCurrent = averageIntOrNull(currentSamples)
        if ((avgCurrent ?: 0f) >= CURRENT_HIGH_MA) {
            if (highCurrentStartedAt == 0L) highCurrentStartedAt = now
        } else {
            highCurrentStartedAt = 0L
        }
        val currentHighLongEnough = highCurrentStartedAt > 0L && now - highCurrentStartedAt >= 120_000L
        val previousLevel = currentSnapshot.level
        val nextLevel = when {
            (avgTemp ?: 0f) >= HEAVY_BATTERY_TEMP_C -> Level.HEAVY
            charging && (avgTemp ?: 0f) >= LIGHT_BATTERY_TEMP_C -> Level.HEAVY
            (avgTemp ?: 0f) >= LIGHT_BATTERY_TEMP_C || currentHighLongEnough -> Level.LIGHT
            previousLevel != Level.NORMAL && (avgTemp ?: 0f) <= RECOVER_TEMP_C -> recoverIfHeld(now)
            previousLevel != Level.NORMAL -> previousLevel
            else -> Level.NORMAL
        }
        if (nextLevel != Level.NORMAL && (avgTemp ?: 0f) > RECOVER_TEMP_C) recoverStartedAt = 0L
        val reason = when (nextLevel) {
            Level.HEAVY -> "overheat-heavy"
            Level.LIGHT -> if (currentHighLongEnough) "high-current" else "overheat-light"
            Level.NORMAL -> "normal"
        }
        return Snapshot(
            level = nextLevel,
            batteryTempC = batteryTempC,
            maxThermalZoneTempC = thermalMax,
            currentMa = currentMa,
            charging = charging,
            responseBodyLimitBytes = if (nextLevel == Level.NORMAL) NORMAL_RESPONSE_BODY_LIMIT_BYTES else DEGRADED_RESPONSE_BODY_LIMIT_BYTES,
            reason = reason,
            updatedAt = now
        )
    }

    private fun recoverIfHeld(now: Long): Level {
        if (recoverStartedAt == 0L) recoverStartedAt = now
        return if (now - recoverStartedAt >= RECOVER_HOLD_MILLIS) Level.NORMAL else currentSnapshot.level
    }

    private fun readCurrentMa(context: Context): Int? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val currentMicroA = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (currentMicroA == Int.MIN_VALUE || currentMicroA == 0) return null
        return abs(currentMicroA / 1000)
    }

    private fun readMaxThermalZoneTempC(): Float? {
        val zones = File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") }
            ?: return null
        return zones.asSequence()
            .mapNotNull { zone ->
                runCatching {
                    val raw = File(zone, "temp").readText().trim().toFloat()
                    if (raw > 1000f) raw / 1000f else raw
                }.getOrNull()
            }
            .filter { it in 1f..120f }
            .maxOrNull()
    }

    private fun <T> addBounded(queue: ArrayDeque<T>, value: T, maxSize: Int) {
        queue.addLast(value)
        while (queue.size > maxSize) queue.removeFirst()
    }

    private fun averageFloatOrNull(values: ArrayDeque<Float>): Float? {
        if (values.isEmpty()) return null
        return values.sum() / values.size
    }

    private fun averageIntOrNull(values: ArrayDeque<Int>): Float? {
        if (values.isEmpty()) return null
        return values.sum().toFloat() / values.size
    }
}
