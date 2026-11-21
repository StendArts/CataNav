package fr.inertialnav.iau.pedestrian

import fr.inertialnav.iau.config.Params
import fr.inertialnav.iau.core.math.Vec3
import kotlin.math.sqrt

/**
 * Détecteur d'immobilité (ZUPT) sur fenêtre glissante :
 * écart-type de |a| < [accStdThreshold] et max |ω| < [gyroThreshold].
 * Seuils adaptés à un téléphone tenu en main (moins strict qu'un capteur au pied).
 */
class ZuptDetector(
    private val windowSize: Int = Params.Pedestrian.ZUPT_WINDOW,
    private val accStdThreshold: Double = Params.Pedestrian.ZUPT_ACC_STD_THRESHOLD,
    private val gyroThreshold: Double = Params.Pedestrian.ZUPT_GYRO_THRESHOLD,
) {
    private val accNorms = DoubleArray(windowSize)
    private val gyrNorms = DoubleArray(windowSize)
    private var index = 0
    private var count = 0
    private var sum = 0.0
    private var sumSq = 0.0

    var stationary = false
        private set

    fun reset() {
        index = 0
        count = 0
        sum = 0.0
        sumSq = 0.0
        stationary = false
    }

    fun process(acc: Vec3, gyr: Vec3): Boolean {
        val a = acc.norm()
        if (count == windowSize) {
            val old = accNorms[index]
            sum -= old
            sumSq -= old * old
        } else {
            count++
        }
        accNorms[index] = a
        gyrNorms[index] = gyr.norm()
        sum += a
        sumSq += a * a
        index = (index + 1) % windowSize

        if (count < windowSize) {
            stationary = false
            return false
        }
        val mean = sum / count
        val std = sqrt((sumSq / count - mean * mean).coerceAtLeast(0.0))
        var gMax = 0.0
        for (g in gyrNorms) if (g > gMax) gMax = g
        stationary = std < accStdThreshold && gMax < gyroThreshold
        return stationary
    }
}
