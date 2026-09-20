package co.byite.focus.engine

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import co.byite.focus.core.aggregate.DeviceSample
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ScreenState

/** Thermal, battery, screen/keyguard and app-visibility state, read once per bucket close. */
class DeviceStatusReader(private val context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val km = context.getSystemService(KeyguardManager::class.java)
    private val packageName = context.packageName

    fun read(): DeviceSample {
        val interactive = pm.isInteractive
        val locked = km?.isKeyguardLocked ?: false
        val screen = when {
            !interactive -> ScreenState.OFF
            locked -> ScreenState.ON_LOCKED
            else -> ScreenState.ON_UNLOCKED
        }
        val app = if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) AppState.FOREGROUND else AppState.BACKGROUND
        return DeviceSample(
            thermalStatus = pm.currentThermalStatus,
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it != Int.MIN_VALUE },
            batteryCurrentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE },
            batteryVoltageMv = batteryVoltageMv(),
            isInteractive = interactive,
            isDeviceIdle = pm.isDeviceIdleMode,
            screenState = screen,
            appState = app,
        )
    }

    /** Sticky ACTION_BATTERY_CHANGED extra, mV. Null when the system has not broadcast it yet. */
    private fun batteryVoltageMv(): Int? = try {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
    } catch (e: Exception) {
        null
    }

    fun isIgnoringBatteryOptimizations(): Boolean = pm.isIgnoringBatteryOptimizations(packageName)
}
