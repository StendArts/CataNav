package fr.inertialnav.iau.msu

import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.wrapPi
import kotlin.math.atan2
import kotlin.math.hypot

/** Calcul du cap magnétique compensé en inclinaison. */
object MagHeading {

    /** Projette le champ mesuré (FRD) dans le repère « nivelé » (lacet nul). */
    fun levelField(magBody: Vec3, roll: Double, pitch: Double): Vec3 =
        Quaternion.fromEuler(roll, pitch, 0.0).toDcm() * magBody

    /**
     * Cap vrai (rad) : le champ horizontal pointe vers le nord magnétique, situé à
     * [declination] à l'est du nord vrai.
     */
    fun heading(levelField: Vec3, declination: Double): Double =
        wrapPi(declination - atan2(levelField.y, levelField.x))

    /** Inclinaison magnétique (rad, positive vers le bas). */
    fun inclination(levelField: Vec3): Double =
        atan2(levelField.z, hypot(levelField.x, levelField.y))
}
