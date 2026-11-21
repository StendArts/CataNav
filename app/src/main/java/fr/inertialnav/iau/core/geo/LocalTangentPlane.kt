package fr.inertialnav.iau.core.geo

import fr.inertialnav.iau.config.Params
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.toDeg
import fr.inertialnav.iau.core.math.toRad
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPosition(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeM: Double)

/**
 * Plan tangent local NED centré sur une origine géodésique (WGS84).
 * Approximation « terre plate » au premier ordre, largement suffisante à l'échelle piéton
 * (erreur < 1 m pour quelques km autour de l'origine).
 */
class LocalTangentPlane(val origin: GeoPosition) {

    private val lat0 = origin.latitudeDeg.toRad()
    private val sinLat = sin(lat0)
    private val den = sqrt(1 - E2 * sinLat * sinLat)

    /** Rayon de courbure méridien (M) et dans le premier vertical (N). */
    private val meridianRadius = A * (1 - E2) / (den * den * den)
    private val normalRadius = A / den

    private val metersPerRadLat = meridianRadius + origin.altitudeM
    private val metersPerRadLon = (normalRadius + origin.altitudeM) * cos(lat0)

    /** Facteurs d'échelle pour convertir un écart-type en mètres vers des degrés. */
    val metersPerDegreeLat: Double get() = metersPerRadLat * Math.PI / 180.0
    val metersPerDegreeLon: Double get() = metersPerRadLon * Math.PI / 180.0

    fun toNed(p: GeoPosition) = Vec3(
        (p.latitudeDeg - origin.latitudeDeg).toRad() * metersPerRadLat,
        (p.longitudeDeg - origin.longitudeDeg).toRad() * metersPerRadLon,
        -(p.altitudeM - origin.altitudeM),
    )

    fun toGeo(ned: Vec3) = GeoPosition(
        origin.latitudeDeg + (ned.x / metersPerRadLat).toDeg(),
        origin.longitudeDeg + (ned.y / metersPerRadLon).toDeg(),
        origin.altitudeM - ned.z,
    )

    companion object {
        const val A = Params.Physics.WGS84_A
        const val E2 = Params.Physics.WGS84_E2
    }
}
