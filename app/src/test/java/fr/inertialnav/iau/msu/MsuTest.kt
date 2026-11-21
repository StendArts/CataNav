package fr.inertialnav.iau.msu

import fr.inertialnav.iau.core.math.Matrix
import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.toRad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class MsuTest {

    @Test
    fun ellipsoidFitRecoversHardAndSoftIron() {
        val bias = Vec3(12.0, -30.0, 7.0)
        val scale = Vec3(1.2, 0.9, 1.05)
        val field = 48.0
        val rnd = Random(42)
        val samples = (0 until 500).map {
            val theta = rnd.nextDouble(0.0, Math.PI)
            val phi = rnd.nextDouble(0.0, 2 * Math.PI)
            val u = Vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta)) * field
            Vec3(u.x * scale.x, u.y * scale.y, u.z * scale.z) + bias +
                Vec3(rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(-0.2, 0.2), rnd.nextDouble(-0.2, 0.2))
        }
        val cal = EllipsoidFit.fitAxisAligned(samples)
        assertNotNull(cal)
        cal!!
        assertEquals(bias.x, cal.hardIron.x, 0.3)
        assertEquals(bias.y, cal.hardIron.y, 0.3)
        assertEquals(bias.z, cal.hardIron.z, 0.3)
        // Après correction, la norme est constante
        samples.take(50).forEach { assertEquals(cal.fieldRadius, cal.apply(it).norm(), 0.6) }
        assertEquals(scale.y / scale.x, cal.softIron[0, 0] / cal.softIron[1, 1], 0.01)
    }

    @Test
    fun tiltCompensatedHeading() {
        // Champ terrestre NED : 20 µT horizontal vers le nord magnétique, 43 µT vers le bas
        val declination = 2.0.toRad()
        val mN = Vec3(20.0 * cos(declination), 20.0 * sin(declination), 43.0)
        val q = Quaternion.fromEuler(15.0.toRad(), -10.0.toRad(), 70.0.toRad())
        val mBody = q.toDcm().transpose() * mN
        val level = MagHeading.levelField(mBody, 15.0.toRad(), -10.0.toRad())
        assertEquals(70.0.toRad(), MagHeading.heading(level, declination), 1e-9)
    }

    @Test
    fun sphereCoverage() {
        val center = Vec3(10.0, -5.0, 3.0)
        val rnd = Random(7)
        val full = (0 until 2000).map {
            val theta = Math.acos(rnd.nextDouble(-1.0, 1.0))
            val phi = rnd.nextDouble(0.0, 2 * Math.PI)
            center + Vec3(sin(theta) * cos(phi), sin(theta) * sin(phi), cos(theta)) * 45.0
        }
        assertEquals(1.0, SphereCoverage.compute(full, center), 1e-9)
        // Rotation autour d'un seul axe (téléphone gardé à plat) : couverture partielle
        val flat = (0 until 500).map { center + Vec3(cos(it * 0.05), sin(it * 0.05), 0.4) * 45.0 }
        assertEquals(8.0 / 32.0, SphereCoverage.compute(flat, center), 1e-9)
    }

    @Test
    fun calibrationIdentity() {
        val cal = MagCalibration(Vec3.ZERO, Matrix.identity(3), 0.0, 48.0, 0)
        assertEquals(Vec3(1.0, 2.0, 3.0), cal.apply(Vec3(1.0, 2.0, 3.0)))
    }
}
