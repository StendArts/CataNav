package fr.inertialnav.iau.kal

import fr.inertialnav.iau.core.math.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Alignement grossier statique : roulis et tangage à partir de la force spécifique moyenne
 * mesurée au repos (FRD). Au repos, f_b = C_bn·(0, 0, -g).
 */
object Alignment {
    fun rollPitch(meanSpecificForce: Vec3): Pair<Double, Double> {
        val f = meanSpecificForce
        val roll = atan2(-f.y, -f.z)
        val pitch = atan2(f.x, sqrt(f.y * f.y + f.z * f.z))
        return roll to pitch
    }
}
