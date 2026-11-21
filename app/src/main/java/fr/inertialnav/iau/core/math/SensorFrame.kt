package fr.inertialnav.iau.core.math

/**
 * Conversion du repère capteur Android vers le repère porteur FRD utilisé par la navigation.
 *
 * Repère Android (téléphone à plat, écran vers le haut) : x vers la droite, y vers le haut
 * de l'écran, z sortant de l'écran.
 * Repère FRD : x = Forward (haut de l'écran), y = Right, z = Down (vers le dos du téléphone).
 *
 * FRD = (y_android, x_android, -z_android) — rotation propre (déterminant +1).
 */
object SensorFrame {
    fun androidToFrd(values: FloatArray, offset: Int = 0) = Vec3(
        values[offset + 1].toDouble(),
        values[offset].toDouble(),
        -values[offset + 2].toDouble(),
    )
}
