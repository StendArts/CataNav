package fr.inertialnav.iau.pedestrian

import fr.inertialnav.iau.config.Params
import kotlin.math.PI
import kotlin.math.pow

data class Step(
    val timestampNs: Long,
    /** Longueur de pas estimée (m). */
    val lengthM: Double,
    /** Durée depuis le pas précédent (s). */
    val periodS: Double,
) {
    val speedMps: Double get() = lengthM / periodS
}

/**
 * Détection de pas (PDR) sur la norme de l'accélération :
 * filtre passe-bas, détection de pic au-dessus de g + [peakThreshold], intervalle minimal
 * entre deux pas, longueur de pas par le modèle de Weinberg : L = K · (a_max − a_min)^¼.
 */
class StepDetector(
    private val peakThreshold: Double = Params.Pedestrian.STEP_PEAK_THRESHOLD,
    private val minStepIntervalS: Double = Params.Pedestrian.STEP_MIN_INTERVAL_S,
    private val maxStepIntervalS: Double = Params.Pedestrian.STEP_MAX_INTERVAL_S,
    private val defaultPeriodS: Double = Params.Pedestrian.STEP_DEFAULT_PERIOD_S,
    private val weinbergK: Double = Params.Pedestrian.WEINBERG_K,
    cutoffHz: Double = Params.Pedestrian.STEP_FILTER_CUTOFF_HZ,
    private val gravity: Double = Params.Physics.GRAVITY,
) {
    private val rc = 1.0 / (2 * PI * cutoffHz)

    private var lastT = 0L
    private var filtered = 0.0
    private var rising = false
    private var valley = Double.MAX_VALUE
    private var lastStepT = 0L

    var stepCount = 0
        private set

    fun reset() {
        lastT = 0L
        rising = false
        valley = Double.MAX_VALUE
        lastStepT = 0L
        stepCount = 0
    }

    /** @return un [Step] si un pas vient d'être détecté. */
    fun process(timestampNs: Long, accNorm: Double): Step? {
        if (lastT == 0L) {
            lastT = timestampNs
            filtered = accNorm
            return null
        }
        val dt = (timestampNs - lastT) * 1e-9
        lastT = timestampNs
        if (dt <= 0) return null

        val prev = filtered
        filtered += dt / (rc + dt) * (accNorm - filtered)
        if (filtered < valley) valley = filtered

        if (filtered > prev) {
            rising = true
            return null
        }
        if (!rising) return null
        rising = false

        // prev est un maximum local
        if (prev < gravity + peakThreshold) return null
        val sinceLast = if (lastStepT == 0L) Double.MAX_VALUE else (timestampNs - lastStepT) * 1e-9
        if (sinceLast < minStepIntervalS) return null

        val amplitude = (prev - valley).coerceAtLeast(0.0)
        val length = weinbergK * amplitude.pow(0.25)
        val period = if (sinceLast > maxStepIntervalS) defaultPeriodS else sinceLast
        lastStepT = timestampNs
        valley = filtered
        stepCount++
        return Step(timestampNs, length, period)
    }
}
