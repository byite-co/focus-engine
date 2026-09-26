package co.byite.focus.engine

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import co.byite.focus.core.aggregate.DeviceSample
import co.byite.focus.core.model.AppState
import co.byite.focus.core.model.ScreenState

/**
 * Thermal, battery, screen/keyguard, app-visibility and (foldables) hinge-angle state, read once per second on the
 * status thread (binder calls never run on the analysis or aggregation thread; directive D 정정 3).
 *
 * Hinge angle (directive E 6장): `TYPE_HINGE_ANGLE` reports on change only, so a session that starts with the phone
 * already folded may never get an event. After [HINGE_INITIAL_WAIT_MS] without one the last value known to this
 * process or persisted by an earlier session is used; without any, the fold state stays "미상" (null).
 */
class DeviceStatusReader(private val context: Context) : SensorEventListener {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val km = context.getSystemService(KeyguardManager::class.java)
    private val sm = context.getSystemService(SensorManager::class.java)
    private val hinge: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val packageName = context.packageName
    @Volatile private var hingeAngleDeg: Double? = null
    /** "event", "last_known" or "unknown": where the current hinge value came from (event log). */
    @Volatile var hingeSource: String = "unknown"
        private set
    private var hingePrefs: EnginePrefs? = null
    private var hingeHandler: Handler? = null
    private var hingeGeneration = 0
    private var hingeInitialReport: ((source: String, value: Double?) -> Unit)? = null

    /** True when a hinge-angle sensor was detected; false means "no sensor, fold state unknown", not "not foldable". */
    val hingeSensor: Boolean get() = hinge != null

    /**
     * Listen to the hinge angle on [handler]'s thread for the session; no-op without the sensor. [prefs] keeps the
     * last known angle across sessions and process restarts; [onInitial] is called once, after the first event or after
     * the 2 s wait, with the source of the value in use.
     */
    fun startHingeMonitor(handler: Handler, prefs: EnginePrefs? = null, onInitial: ((source: String, value: Double?) -> Unit)? = null): Boolean {
        val s = hinge ?: return false
        hingePrefs = prefs
        hingeHandler = handler
        hingeInitialReport = onInitial
        hingeAngleDeg = null
        hingeSource = "unknown"
        val generation = ++hingeGeneration
        val ok = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
        handler.postDelayed({
            if (generation != hingeGeneration) return@postDelayed
            if (hingeAngleDeg == null) {
                val last = lastKnownHingeAngleDeg ?: prefs?.lastHingeAngleDeg
                if (last != null) {
                    hingeAngleDeg = last
                    hingeSource = "last_known"
                } else {
                    hingeSource = "unknown"
                }
                hingeInitialReport?.invoke(hingeSource, hingeAngleDeg)
                hingeInitialReport = null
            }
        }, HINGE_INITIAL_WAIT_MS)
        return ok
    }

    fun stopHingeMonitor() {
        hingeGeneration++
        hingeInitialReport = null
        if (hinge != null) sm?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        val v = event.values[0].toDouble()
        hingeAngleDeg = v
        hingeSource = "event"
        lastKnownHingeAngleDeg = v
        hingePrefs?.let { p -> if (p.lastHingeAngleDeg?.let { kotlin.math.abs(it - v) >= 1.0 } != false) p.lastHingeAngleDeg = v }
        hingeInitialReport?.let { report ->
            hingeInitialReport = null
            report("event", v)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
            hingeAngleDeg = hingeAngleDeg,
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

    companion object {
        /** Directive E 6장: wait this long for a change event before falling back to the last known angle. */
        const val HINGE_INITIAL_WAIT_MS: Long = 2_000L

        /** Last hinge angle seen by this process (any session); persisted copies live in [EnginePrefs.lastHingeAngleDeg]. */
        @Volatile var lastKnownHingeAngleDeg: Double? = null
            private set
    }
}
