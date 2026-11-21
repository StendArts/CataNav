package fr.inertialnav.iau.msu

import fr.inertialnav.iau.config.Params
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import fr.inertialnav.iau.core.math.SensorFrame
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.imu.RateMeter

/**
 * Mesure magnétomètre brute (FRD, µT).
 * @param systemBias biais de fer dur estimé par Android (nul si capteur déjà calibré)
 */
data class MagSample(val timestampNs: Long, val raw: Vec3, val systemBias: Vec3)

/**
 * Module MSU (acquisition) : magnétomètre non calibré si disponible, afin d'appliquer
 * notre propre compensation fer dur / fer doux ([MagCalibration]).
 */
class MagSource(
    context: Context,
    private val handler: Handler,
    private val samplingPeriodUs: Int = Params.Msu.SAMPLING_PERIOD_US,
    private val onSample: (MagSample) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rate = RateMeter()

    val available: Boolean get() = sensor != null
    val rateHz: Double get() = rate.rateHz

    fun start(): Boolean {
        val s = sensor ?: return false
        return sensorManager.registerListener(this, s, samplingPeriodUs, 0, handler)
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        rate.tick(event.timestamp)
        val raw = SensorFrame.androidToFrd(event.values)
        val bias = if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED && event.values.size >= 6) {
            SensorFrame.androidToFrd(event.values, 3)
        } else {
            Vec3.ZERO
        }
        onSample(MagSample(event.timestamp, raw, bias))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
