package co.byite.focus.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import co.byite.focus.core.aggregate.ImuSample
import co.byite.focus.engine.timebase.Timebase

/**
 * Accelerometer at 5 Hz (spec 1장 파이프라인 IMU 5Hz). Sensor events arrive on [handler]'s thread (the status
 * thread) and leave as [ImuSample] scalars through [onSample], which the service posts to the aggregation
 * queue (directive D 정정 3: the IMU never touches the aggregator). Pickup / shake / re-dock classification is
 * V0-F's and needs the dock posture from V0-C.
 */
class MotionPipeline(
    context: Context,
    private val handler: Handler,
    private val timebase: Timebase,
    private val onSample: (ImuSample) -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    @Volatile private var stopped = false
    var samples: Long = 0L
        private set

    /** True when the sensor exists and registration succeeded. */
    fun start(): Boolean {
        val s = accel ?: return false
        return sm.registerListener(this, s, PERIOD_US, handler)
    }

    /** Stop step 1: no more samples leave after this returns (events already queued on [handler] are dropped). */
    fun stop() {
        stopped = true
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (stopped || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val cb = SystemClock.elapsedRealtimeNanos()
        val mono = timebase.imuToMono(event.timestamp, cb)
        samples++
        onSample(ImuSample(mono, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val PERIOD_US = 200_000
    }
}
