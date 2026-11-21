package fr.inertialnav.iau.kal

import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.toRad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class InsEkfTest {

    private val g = InsEkf.GRAVITY
    private val dtNs = 10_000_000L // 100 Hz

    /** Force spécifique au repos pour une attitude donnée : f_b = C_bn·(0,0,-g). */
    private fun staticForce(q: Quaternion): Vec3 = q.toDcm().transpose() * Vec3(0.0, 0.0, -g)

    @Test
    fun staticAlignmentRecoversRollPitch() {
        val q = Quaternion.fromEuler(5.0.toRad(), -8.0.toRad(), 0.0)
        val (roll, pitch) = Alignment.rollPitch(staticForce(q))
        assertEquals(5.0.toRad(), roll, 1e-9)
        assertEquals(-8.0.toRad(), pitch, 1e-9)
    }

    @Test
    fun staticPropagationStaysPut() {
        val q = Quaternion.fromEuler(3.0.toRad(), 2.0.toRad(), 40.0.toRad())
        val ekf = InsEkf()
        ekf.initialize(0L, q)
        val f = staticForce(q)
        for (k in 1..1000) ekf.propagate(k * dtNs, f, Vec3.ZERO)
        assertTrue("dérive de position ${ekf.position}", ekf.position.norm() < 1e-6)
        assertTrue(ekf.velocity.norm() < 1e-6)
    }

    @Test
    fun zuptBoundsVelocityErrorWithAccelBias() {
        val q = Quaternion.IDENTITY
        val ekf = InsEkf()
        ekf.initialize(0L, q)
        val bias = Vec3(0.05, -0.03, 0.0)
        val f = staticForce(q) + bias
        for (k in 1..3000) {
            ekf.propagate(k * dtNs, f, Vec3.ZERO)
            if (k % 10 == 0) ekf.updateZeroVelocity(0.03)
        }
        assertTrue("vitesse ${ekf.velocity}", ekf.velocity.norm() < 0.02)
        // Le biais horizontal est observable via le couplage attitude/biais : on vérifie seulement la borne
        assertTrue(ekf.position.norm() < 1.0)
    }

    @Test
    fun headingUpdateConvergesYaw() {
        val ekf = InsEkf()
        ekf.initialize(0L, Quaternion.fromEuler(0.0, 0.0, 0.0), sigmaYaw = 30.0.toRad())
        val f = staticForce(Quaternion.IDENTITY)
        for (k in 1..200) {
            ekf.propagate(k * dtNs, f, Vec3.ZERO)
            if (k % 10 == 0) ekf.updateHeading(20.0.toRad(), 5.0.toRad(), gate = null)
        }
        assertEquals(20.0.toRad(), ekf.euler.z, 1.0.toRad())
    }

    @Test
    fun headingJacobianMatchesNumerical() {
        val q = Quaternion.fromEuler(20.0.toRad(), -30.0.toRad(), 60.0.toRad())
        val c = q.toDcm()
        val den = c[0, 0] * c[0, 0] + c[1, 0] * c[1, 0]
        val analytic = doubleArrayOf(-c[0, 0] * c[2, 0] / den, -c[1, 0] * c[2, 0] / den, 1.0)
        val eps = 1e-7
        val yaw0 = atan2(c[1, 0], c[0, 0])
        for (i in 0 until 3) {
            val phi = Vec3(if (i == 0) eps else 0.0, if (i == 1) eps else 0.0, if (i == 2) eps else 0.0)
            val cp = (Quaternion.fromRotationVector(phi) * q).toDcm()
            val numeric = (atan2(cp[1, 0], cp[0, 0]) - yaw0) / eps
            assertEquals("composante $i", analytic[i], numeric, 1e-5)
        }
    }

    @Test
    fun eulerJacobianMatchesNumerical() {
        val q = Quaternion.fromEuler(25.0.toRad(), -35.0.toRad(), 120.0.toRad())
        val ekf = InsEkf()
        ekf.initialize(0L, q)
        val j = ekf.eulerJacobian()
        val e0 = q.toEuler()
        val eps = 1e-7
        for (k in 0 until 3) {
            val phi = Vec3(if (k == 0) eps else 0.0, if (k == 1) eps else 0.0, if (k == 2) eps else 0.0)
            val e1 = (Quaternion.fromRotationVector(phi) * q).toEuler()
            val d = (e1 - e0) / eps
            for (i in 0 until 3) assertEquals("J[$i,$k]", d[i], j[i, k], 1e-5)
        }
    }

    @Test
    fun eulerSigmasAtLevelMatchCovariance() {
        // À plat et cap nul, φ et (roulis, tangage, lacet) coïncident
        val ekf = InsEkf()
        ekf.initialize(0L, Quaternion.IDENTITY, sigmaRollPitch = 2.0.toRad(), sigmaYaw = 30.0.toRad())
        val s = ekf.eulerSigmas()
        assertEquals(2.0.toRad(), s.x, 1e-9)
        assertEquals(2.0.toRad(), s.y, 1e-9)
        assertEquals(30.0.toRad(), s.z, 1e-9)
    }

    @Test
    fun gpsUpdateCorrectsPosition() {
        val ekf = InsEkf()
        ekf.initialize(0L, Quaternion.IDENTITY, sigmaPosH = 50.0, sigmaPosV = 50.0)
        val r = ekf.updatePosition(Vec3(10.0, -5.0, 1.0), 3.0, 5.0)
        assertTrue(r.accepted)
        assertEquals(10.0, ekf.position.x, 0.5)
        assertEquals(-5.0, ekf.position.y, 0.5)
        assertTrue(ekf.sigma(InsEkf.IP) < 3.0)
    }
}
