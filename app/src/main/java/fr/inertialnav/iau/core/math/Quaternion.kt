package fr.inertialnav.iau.core.math

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternion de Hamilton (w, x, y, z).
 * Dans toute l'application, l'attitude est représentée par q_nb : rotation du repère
 * porteur (body, FRD) vers le repère de navigation (NED).
 */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(q: Quaternion) = Quaternion(
        w * q.w - x * q.x - y * q.y - z * q.z,
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
    )

    fun conjugate() = Quaternion(w, -x, -y, -z)

    /** Normalise et force w ≥ 0 (représentation unique). */
    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        val s = if (w < 0) -1.0 / n else 1.0 / n
        return Quaternion(w * s, x * s, y * s, z * s)
    }

    /** Matrice de rotation C_nb (body → nav). */
    fun toDcm(): Matrix {
        val ww = w * w
        val xx = x * x
        val yy = y * y
        val zz = z * z
        return Matrix(
            3, 3, doubleArrayOf(
                ww + xx - yy - zz, 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), ww - xx + yy - zz, 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), ww - xx - yy + zz,
            )
        )
    }

    fun rotate(v: Vec3): Vec3 = toDcm() * v

    /** Angles d'Euler (roulis, tangage, lacet) en radians, convention ZYX aéronautique. */
    fun toEuler(): Vec3 {
        val c = toDcm()
        val roll = atan2(c[2, 1], c[2, 2])
        val pitch = asin((-c[2, 0]).coerceIn(-1.0, 1.0))
        val yaw = atan2(c[1, 0], c[0, 0])
        return Vec3(roll, pitch, yaw)
    }

    companion object {
        val IDENTITY = Quaternion(1.0, 0.0, 0.0, 0.0)

        /** Quaternion associé au vecteur rotation [v] (axe × angle, rad). */
        fun fromRotationVector(v: Vec3): Quaternion {
            val angle = v.norm()
            if (angle < 1e-12) return Quaternion(1.0, 0.5 * v.x, 0.5 * v.y, 0.5 * v.z).normalized()
            val s = sin(0.5 * angle) / angle
            return Quaternion(cos(0.5 * angle), v.x * s, v.y * s, v.z * s)
        }

        /** Construction depuis roulis/tangage/lacet (rad), convention ZYX. */
        fun fromEuler(roll: Double, pitch: Double, yaw: Double): Quaternion {
            val cr = cos(0.5 * roll)
            val sr = sin(0.5 * roll)
            val cp = cos(0.5 * pitch)
            val sp = sin(0.5 * pitch)
            val cy = cos(0.5 * yaw)
            val sy = sin(0.5 * yaw)
            return Quaternion(
                cr * cp * cy + sr * sp * sy,
                sr * cp * cy - cr * sp * sy,
                cr * sp * cy + sr * cp * sy,
                cr * cp * sy - sr * sp * cy,
            ).normalized()
        }
    }
}

/** Ramène un angle dans ]-π, π]. */
fun wrapPi(a: Double): Double {
    var r = a % (2 * PI)
    if (r > PI) r -= 2 * PI
    if (r <= -PI) r += 2 * PI
    return r
}

fun Double.toRad() = this * PI / 180.0
fun Double.toDeg() = this * 180.0 / PI
