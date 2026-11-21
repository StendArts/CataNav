package fr.inertialnav.iau.msu

import fr.inertialnav.iau.config.Params
import fr.inertialnav.iau.core.math.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/**
 * Couverture des directions d'une calibration magnétique : la sphère est découpée en
 * 4 bandes d'égale aire (selon z/|v|) × 8 secteurs d'azimut = 32 cases d'égale surface.
 * Une bonne calibration doit couvrir la quasi-totalité des cases.
 */
object SphereCoverage {
    private const val BANDS = Params.Msu.COVERAGE_BANDS
    private const val SECTORS = Params.Msu.COVERAGE_SECTORS

    fun compute(samples: List<Vec3>, center: Vec3): Double {
        if (samples.isEmpty()) return 0.0
        val hit = BooleanArray(BANDS * SECTORS)
        for (s in samples) {
            val d = s - center
            val n = d.norm()
            if (n < 1e-9) continue
            val band = floor((d.z / n + 1.0) / 2.0 * BANDS).toInt().coerceIn(0, BANDS - 1)
            val sector = floor((atan2(d.y, d.x) + PI) / (2 * PI) * SECTORS).toInt().coerceIn(0, SECTORS - 1)
            hit[band * SECTORS + sector] = true
        }
        return hit.count { it }.toDouble() / hit.size
    }
}
