package fr.inertialnav.iau.msu

import fr.inertialnav.iau.config.Params
import fr.inertialnav.iau.core.math.Matrix
import fr.inertialnav.iau.core.math.Vec3
import kotlin.math.sqrt

/**
 * Ajustement d'ellipsoïde aux mesures magnétiques brutes collectées en faisant tourner le
 * téléphone dans toutes les directions.
 *
 * Modèle à axes alignés : A x² + B y² + C z² + D x + E y + F z = 1 (moindres carrés).
 * → centre (fer dur) et rayons (fer doux diagonal).
 * TODO : passer à l'ellipsoïde général (9 paramètres) pour estimer aussi les couplages.
 */
object EllipsoidFit {

    const val MIN_SAMPLES = Params.Msu.CALIBRATION_MIN_SAMPLES

    fun fitAxisAligned(samples: List<Vec3>): MagCalibration? {
        if (samples.size < MIN_SAMPLES) return null
        // Centrage préalable pour le conditionnement numérique
        var mean = Vec3.ZERO
        samples.forEach { mean += it }
        mean /= samples.size.toDouble()

        val ata = Matrix(6, 6)
        val atb = DoubleArray(6)
        val row = DoubleArray(6)
        for (s in samples) {
            val p = s - mean
            row[0] = p.x * p.x; row[1] = p.y * p.y; row[2] = p.z * p.z
            row[3] = p.x; row[4] = p.y; row[5] = p.z
            for (i in 0 until 6) {
                atb[i] += row[i]
                for (j in 0 until 6) ata[i, j] += row[i] * row[j]
            }
        }
        val sol = try {
            ata.inverse() * atb
        } catch (_: ArithmeticException) {
            return null
        }
        val (a, b, c, d, e) = sol
        val f = sol[5]
        if (a <= 0 || b <= 0 || c <= 0) return null

        val cx = -d / (2 * a)
        val cy = -e / (2 * b)
        val cz = -f / (2 * c)
        val g = 1 + a * cx * cx + b * cy * cy + c * cz * cz
        if (g <= 0) return null
        val rx = sqrt(g / a)
        val ry = sqrt(g / b)
        val rz = sqrt(g / c)
        if (!rx.isFinite() || !ry.isFinite() || !rz.isFinite()) return null
        val r = (rx + ry + rz) / 3.0

        val center = mean + Vec3(cx, cy, cz)
        val soft = Matrix.diag(r / rx, r / ry, r / rz)
        val provisional = MagCalibration(center, soft, 0.0, r, samples.size)

        var sumSq = 0.0
        for (s in samples) {
            val err = (provisional.apply(s).norm() - r) / r
            sumSq += err * err
        }
        return MagCalibration(center, soft, sqrt(sumSq / samples.size), r, samples.size)
    }
}
