package fr.inertialnav.iau.msu

import fr.inertialnav.iau.core.math.Matrix
import fr.inertialnav.iau.core.math.Vec3

/**
 * Compensation magnétique : m_corrigé = S · (m_brut − b)
 * - b : fer dur (biais constant, µT)
 * - S : fer doux (matrice 3×3 de correction d'échelle / couplage)
 */
class MagCalibration(
    val hardIron: Vec3,
    val softIron: Matrix,
    /** Résidu RMS relatif de l'ajustement (0 = parfait). */
    val fitResidual: Double,
    /** Rayon moyen de la sphère après correction (µT). */
    val fieldRadius: Double,
    val sampleCount: Int,
) {
    init {
        require(softIron.rows == 3 && softIron.cols == 3)
    }

    fun apply(raw: Vec3): Vec3 = softIron * (raw - hardIron)
}
