package fr.inertialnav.iau.imu

import fr.inertialnav.iau.config.Params
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import fr.inertialnav.iau.core.math.SensorFrame
import fr.inertialnav.iau.core.math.Vec3

/**
 * Échantillon IMU synchronisé, exprimé en repère FRD.
 * @param timestampNs horodatage capteur (base elapsedRealtimeNanos)
 * @param acc force spécifique (m/s²)
 * @param gyr vitesse angulaire (rad/s)
 */
data class ImuSample(val timestampNs: Long, val acc: Vec3, val gyr: Vec3)

/**
 * Module IMU : acquisition accéléromètre + gyromètre.
 *
 * Le gyromètre cadence la sortie : à chaque mesure gyro, on émet un échantillon avec la
 * dernière mesure accéléro disponible (les deux capteurs tournent à la même fréquence).
 * Les capteurs « calibrés » d'Android sont utilisés ; les biais résiduels sont estimés par KAL.
 */
class ImuSource(
    context: Context,
    private val handler: Handler,
    private val samplingPeriodUs: Int = Params.Imu.SAMPLING_PERIOD_US,
    private val onSample: (ImuSample) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastAcc: Vec3? = null
    private val rate = RateMeter()

    val available: Boolean get() = accelerometer != null && gyroscope != null

    /** Fréquence effective (Hz) des échantillons émis. */
    val rateHz: Double get() = rate.rateHz

    fun start(): Boolean {
        if (!available) return false
        sensorManager.registerListener(this, accelerometer, samplingPeriodUs, 0, handler)
        sensorManager.registerListener(this, gyroscope, samplingPeriodUs, 0, handler)
        return true
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        lastAcc = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> lastAcc = SensorFrame.androidToFrd(event.values)
            Sensor.TYPE_GYROSCOPE -> {
                val acc = lastAcc ?: return
                rate.tick(event.timestamp)
                onSample(ImuSample(event.timestamp, acc, SensorFrame.androidToFrd(event.values)))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}

/** Estimation de fréquence par moyenne glissante exponentielle de la période. */
class RateMeter {
    private var lastNs = 0L
    private var meanPeriodS = 0.0

    val rateHz: Double get() = if (meanPeriodS > 0) 1.0 / meanPeriodS else 0.0

    fun tick(timestampNs: Long) {
        if (lastNs != 0L) {
            val dt = (timestampNs - lastNs) * 1e-9
            if (dt in 0.0..1.0) meanPeriodS = if (meanPeriodS == 0.0) dt else 0.98 * meanPeriodS + 0.02 * dt
        }
        lastNs = timestampNs
    }
}
