package fr.inertialnav.iau.core

import fr.inertialnav.iau.core.geo.GeoPosition
import fr.inertialnav.iau.core.geo.LocalTangentPlane
import fr.inertialnav.iau.core.math.Matrix
import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.toRad
import fr.inertialnav.iau.core.math.wrapPi
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class MathTest {

    @Test
    fun eulerRoundTrip() {
        val q = Quaternion.fromEuler(10.0.toRad(), -20.0.toRad(), 135.0.toRad())
        val e = q.toEuler()
        assertEquals(10.0.toRad(), e.x, 1e-12)
        assertEquals(-20.0.toRad(), e.y, 1e-12)
        assertEquals(135.0.toRad(), e.z, 1e-12)
    }

    @Test
    fun rotationVectorMatchesYaw() {
        val q = Quaternion.fromRotationVector(Vec3(0.0, 0.0, PI / 2))
        val v = q.rotate(Vec3(1.0, 0.0, 0.0)) // nez vers le nord → vers l'est
        assertEquals(0.0, v.x, 1e-12)
        assertEquals(1.0, v.y, 1e-12)
    }

    @Test
    fun inverseIsCorrect() {
        val m = Matrix(3, 3, doubleArrayOf(4.0, 1.0, 2.0, 0.0, 3.0, 1.0, 1.0, 0.0, 5.0))
        val i = m * m.inverse()
        for (r in 0 until 3) for (c in 0 until 3) assertEquals(if (r == c) 1.0 else 0.0, i[r, c], 1e-12)
    }

    @Test
    fun wrapPiRange() {
        assertEquals(-PI / 2, wrapPi(3 * PI / 2), 1e-12)
        assertEquals(PI / 2, wrapPi(-3 * PI / 2), 1e-12)
    }

    @Test
    fun localTangentPlaneRoundTrip() {
        val ltp = LocalTangentPlane(GeoPosition(48.8566, 2.3522, 35.0))
        val p = GeoPosition(48.8600, 2.3600, 40.0)
        val ned = ltp.toNed(p)
        // ~378 m au nord, ~572 m à l'est
        assertEquals(378.0, ned.x, 2.0)
        assertEquals(572.0, ned.y, 2.0)
        assertEquals(-5.0, ned.z, 1e-9)
        val back = ltp.toGeo(ned)
        assertEquals(p.latitudeDeg, back.latitudeDeg, 1e-9)
        assertEquals(p.longitudeDeg, back.longitudeDeg, 1e-9)
    }
}
