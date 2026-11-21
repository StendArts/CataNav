package fr.inertialnav.iau.msu

import android.content.Context
import androidx.core.content.edit
import fr.inertialnav.iau.core.math.Matrix
import fr.inertialnav.iau.core.math.Vec3

/** Persistance de la calibration magnétique (SharedPreferences). */
class MagCalibrationStore(context: Context) {

    private val prefs = context.getSharedPreferences("msu_calibration", Context.MODE_PRIVATE)

    fun load(): MagCalibration? {
        if (!prefs.getBoolean(KEY_VALID, false)) return null
        val v = DoubleArray(12) { java.lang.Double.longBitsToDouble(prefs.getLong("v$it", 0L)) }
        return MagCalibration(
            hardIron = Vec3(v[0], v[1], v[2]),
            softIron = Matrix(3, 3, v.copyOfRange(3, 12)),
            fitResidual = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_RESIDUAL, 0L)),
            fieldRadius = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_RADIUS, 0L)),
            sampleCount = prefs.getInt(KEY_COUNT, 0),
        )
    }

    fun save(cal: MagCalibration) {
        val v = doubleArrayOf(cal.hardIron.x, cal.hardIron.y, cal.hardIron.z) + cal.softIron.data
        prefs.edit {
            v.forEachIndexed { i, d -> putLong("v$i", java.lang.Double.doubleToRawLongBits(d)) }
            putLong(KEY_RESIDUAL, java.lang.Double.doubleToRawLongBits(cal.fitResidual))
            putLong(KEY_RADIUS, java.lang.Double.doubleToRawLongBits(cal.fieldRadius))
            putInt(KEY_COUNT, cal.sampleCount)
            putBoolean(KEY_VALID, true)
        }
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val KEY_VALID = "valid"
        const val KEY_RESIDUAL = "residual"
        const val KEY_RADIUS = "radius"
        const val KEY_COUNT = "count"
    }
}
