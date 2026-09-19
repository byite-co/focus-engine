package kr.co.byite.focus.spike

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

data class DeviceStatus(
    val thermalStatus: Int,
    val batteryPct: Int?,
    val batteryCurrentUa: Int?,
    val isInteractive: Boolean,
    val isDeviceIdle: Boolean,
)

/** thermal, 배터리, 화면, Doze 상태를 읽는다. 초당 한 번 호출된다. */
class DeviceStatusReader(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val packageName = context.packageName

    fun read(): DeviceStatus = DeviceStatus(
        thermalStatus = pm.currentThermalStatus,
        batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it != Int.MIN_VALUE },
        batteryCurrentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE },
        isInteractive = pm.isInteractive,
        isDeviceIdle = pm.isDeviceIdleMode,
    )

    fun isIgnoringBatteryOptimizations(): Boolean = pm.isIgnoringBatteryOptimizations(packageName)

    companion object {
        fun thermalName(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }
    }
}
