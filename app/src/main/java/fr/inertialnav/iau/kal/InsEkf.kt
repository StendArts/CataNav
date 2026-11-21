package fr.inertialnav.iau.kal

import fr.inertialnav.iau.config.Params
import fr.inertialnav.iau.core.math.Matrix
import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.toRad
import fr.inertialnav.iau.core.math.wrapPi
import kotlin.math.sqrt

/** Densités spectrales de bruit du modèle IMU (valeurs prudentes pour un smartphone). */
data class ImuNoiseModel(
    /** Bruit blanc accéléromètre (m/s²/√Hz). */
    val accNoiseDensity: Double = Params.Kal.ACC_NOISE_DENSITY,
    /** Bruit blanc gyromètre (rad/s/√Hz). */
    val gyroNoiseDensity: Double = Params.Kal.GYRO_NOISE_DENSITY,
    /** Marche aléatoire du biais accéléromètre (m/s³/√Hz). */
    val accBiasRandomWalk: Double = Params.Kal.ACC_BIAS_RANDOM_WALK,
    /** Marche aléatoire du biais gyromètre (rad/s²/√Hz). */
    val gyroBiasRandomWalk: Double = Params.Kal.GYRO_BIAS_RANDOM_WALK,
)

data class UpdateResult(val accepted: Boolean, val nis: Double)

/**
 * Filtre de Kalman étendu à erreur d'état (ES-EKF) pour la navigation inertielle à
 * gyromètres liés.
 *
 * État nominal : position NED (m, par rapport à l'origine du plan tangent local), vitesse NED,
 * attitude q_nb, biais accéléromètre et gyromètre (repère FRD).
 *
 * Vecteur d'erreur (15) : δp(0..2) δv(3..5) φ(6..8, petit angle exprimé en repère nav)
 * δb_a(9..11) δb_g(12..14).
 *
 * Simplifications assumées (échelle piéton, capteurs MEMS) : rotation terrestre et
 * transport rate négligés, gravité constante.
 */
class InsEkf(private val noise: ImuNoiseModel = ImuNoiseModel()) {

    var position: Vec3 = Vec3.ZERO
        private set
    var velocity: Vec3 = Vec3.ZERO
        private set
    var attitude: Quaternion = Quaternion.IDENTITY
        private set
    var accBias: Vec3 = Vec3.ZERO
        private set
    var gyroBias: Vec3 = Vec3.ZERO
        private set

    /** Covariance de l'erreur d'état. */
    val covariance = Matrix(N, N)

    var initialized = false
        private set
    var lastTimestampNs = 0L
        private set

    /** Force spécifique en repère nav à la dernière propagation. */
    var lastSpecificForceNav = Vec3(0.0, 0.0, -GRAVITY)
        private set

    /** Euler (roulis, tangage, lacet) en rad. */
    val euler: Vec3 get() = attitude.toEuler()

    fun initialize(
        timestampNs: Long,
        attitude: Quaternion,
        position: Vec3 = Vec3.ZERO,
        velocity: Vec3 = Vec3.ZERO,
        sigmaPosH: Double = Params.Kal.INIT_SIGMA_POS_H,
        sigmaPosV: Double = Params.Kal.INIT_SIGMA_POS_V,
        sigmaVel: Double = Params.Kal.INIT_SIGMA_VEL,
        sigmaRollPitch: Double = Params.Kal.INIT_SIGMA_ROLL_PITCH,
        sigmaYaw: Double = Params.Kal.INIT_SIGMA_YAW,
        sigmaAccBias: Double = Params.Kal.INIT_SIGMA_ACC_BIAS,
        sigmaGyroBias: Double = Params.Kal.INIT_SIGMA_GYRO_BIAS,
    ) {
        this.position = position
        this.velocity = velocity
        this.attitude = attitude.normalized()
        accBias = Vec3.ZERO
        gyroBias = Vec3.ZERO
        covariance.data.fill(0.0)
        setVar(IP, sigmaPosH, sigmaPosH, sigmaPosV)
        setVar(IV, sigmaVel, sigmaVel, sigmaVel)
        setVar(IA, sigmaRollPitch, sigmaRollPitch, sigmaYaw)
        setVar(IBA, sigmaAccBias, sigmaAccBias, sigmaAccBias)
        setVar(IBG, sigmaGyroBias, sigmaGyroBias, sigmaGyroBias)
        lastTimestampNs = timestampNs
        initialized = true
    }

    fun reset() {
        initialized = false
        lastTimestampNs = 0L
    }

    /**
     * Propagation (mécanisation + covariance) avec une mesure IMU.
     * @param acc force spécifique mesurée (FRD, m/s²)
     * @param gyr vitesse angulaire mesurée (FRD, rad/s)
     */
    fun propagate(timestampNs: Long, acc: Vec3, gyr: Vec3) {
        check(initialized) { "Filtre non initialisé" }
        val dt = (timestampNs - lastTimestampNs) * 1e-9
        lastTimestampNs = timestampNs
        if (dt <= 0.0 || dt > MAX_DT) return

        // --- Mécanisation (état nominal) ---
        val omega = gyr - gyroBias
        val f = acc - accBias
        val cPrev = attitude.toDcm()
        attitude = (attitude * Quaternion.fromRotationVector(omega * dt)).normalized()
        val c = attitude.toDcm()
        // Force spécifique moyennée sur l'intervalle (rotation début/fin)
        val fN = ((cPrev * f) + (c * f)) * 0.5
        lastSpecificForceNav = fN
        val a = fN + Vec3(0.0, 0.0, GRAVITY)
        position += velocity * dt + a * (0.5 * dt * dt)
        velocity += a * dt

        // --- Matrice de transition Φ = I + F·dt ---
        val phi = Matrix.identity(N)
        for (i in 0 until 3) phi[IP + i, IV + i] = dt
        phi.setBlock(IV, IA, Matrix.skew(fN) * (-dt))
        phi.setBlock(IV, IBA, c * (-dt))
        phi.setBlock(IA, IBG, c * (-dt))

        val p = phi * covariance * phi.transpose()

        // --- Bruit de processus discret (G·Qc·Gᵀ·dt, C·Cᵀ = I) ---
        val qv = sq(noise.accNoiseDensity) * dt
        val qa = sq(noise.gyroNoiseDensity) * dt
        val qba = sq(noise.accBiasRandomWalk) * dt
        val qbg = sq(noise.gyroBiasRandomWalk) * dt
        for (i in 0 until 3) {
            p[IV + i, IV + i] += qv
            p[IA + i, IA + i] += qa
            p[IBA + i, IBA + i] += qba
            p[IBG + i, IBG + i] += qbg
        }
        p.symmetrize()
        System.arraycopy(p.data, 0, covariance.data, 0, p.data.size)
    }

    /**
     * Mise à jour générique : innovation y = z - h(x), y ≈ H·δx + v, v ~ N(0, R).
     * @param gate seuil du test du χ² sur la NIS (null = pas de rejet)
     */
    fun update(h: Matrix, innovation: DoubleArray, r: Matrix, gate: Double? = null): UpdateResult {
        val pht = covariance * h.transpose()
        val s = h * pht + r
        val sInv = try {
            s.inverse()
        } catch (_: ArithmeticException) {
            return UpdateResult(false, Double.NaN)
        }
        val sy = sInv * innovation
        var nis = 0.0
        for (i in innovation.indices) nis += innovation[i] * sy[i]
        if (gate != null && nis > gate) return UpdateResult(false, nis)

        val k = pht * sInv
        val dx = k * innovation

        // Forme de Joseph : P = (I-KH)P(I-KH)ᵀ + KRKᵀ
        val ikh = Matrix.identity(N) - k * h
        val p = ikh * covariance * ikh.transpose() + k * r * k.transpose()
        p.symmetrize()
        System.arraycopy(p.data, 0, covariance.data, 0, p.data.size)

        inject(dx)
        return UpdateResult(true, nis)
    }

    /** Recalage en position NED (GPS, carte…). */
    fun updatePosition(measured: Vec3, sigmaH: Double, sigmaV: Double, gate: Double? = CHI2_3DOF): UpdateResult {
        val h = Matrix(3, N)
        for (i in 0 until 3) h[i, IP + i] = 1.0
        val y = doubleArrayOf(measured.x - position.x, measured.y - position.y, measured.z - position.z)
        return update(h, y, Matrix.diag(sq(sigmaH), sq(sigmaH), sq(sigmaV)), gate)
    }

    /** Recalage en position horizontale seule (carte). */
    fun updateHorizontalPosition(north: Double, east: Double, sigma: Double, gate: Double? = CHI2_2DOF): UpdateResult {
        val h = Matrix(2, N)
        h[0, IP] = 1.0
        h[1, IP + 1] = 1.0
        val y = doubleArrayOf(north - position.x, east - position.y)
        return update(h, y, Matrix.diag(sq(sigma), sq(sigma)), gate)
    }

    /**
     * Recalage de position projeté sur une direction horizontale unitaire (nN, nE) :
     * mesure scalaire n·p = n·q, d'innovation [distance] = n·(q − p).
     * Utilisé par l'hybridation carte : seule la composante vers le point praticable le plus
     * proche est observée (la carte ne dit rien le long de la galerie).
     */
    fun updateAlongDirection(
        nN: Double,
        nE: Double,
        distance: Double,
        sigma: Double,
        gate: Double? = CHI2_1DOF,
    ): UpdateResult {
        val h = Matrix(1, N)
        h[0, IP] = nN
        h[0, IP + 1] = nE
        return update(h, doubleArrayOf(distance), Matrix.diag(sq(sigma)), gate)
    }

    /**
     * Recalage en cap (lacet vrai, rad), p. ex. issu du magnétomètre.
     * Jacobien exact de ψ = atan2(C10, C00) par rapport à l'erreur d'attitude φ (repère nav).
     */
    fun updateHeading(yawMeasured: Double, sigma: Double, gate: Double? = CHI2_1DOF): UpdateResult {
        val c = attitude.toDcm()
        val den = sq(c[0, 0]) + sq(c[1, 0])
        if (den < 1e-6) return UpdateResult(false, Double.NaN) // tangage ±90° : cap indéfini
        val h = Matrix(1, N)
        h[0, IA] = -c[0, 0] * c[2, 0] / den
        h[0, IA + 1] = -c[1, 0] * c[2, 0] / den
        h[0, IA + 2] = 1.0
        val yaw = kotlin.math.atan2(c[1, 0], c[0, 0])
        val y = doubleArrayOf(wrapPi(yawMeasured - yaw))
        return update(h, y, Matrix.diag(sq(sigma)), gate)
    }

    /** ZUPT : pseudo-mesure de vitesse nulle. */
    fun updateZeroVelocity(sigma: Double): UpdateResult {
        val h = Matrix(3, N)
        for (i in 0 until 3) h[i, IV + i] = 1.0
        val y = doubleArrayOf(-velocity.x, -velocity.y, -velocity.z)
        return update(h, y, Matrix.diag(sq(sigma), sq(sigma), sq(sigma)))
    }

    /** ZARU : à l'arrêt, la vitesse angulaire mesurée est égale au biais gyromètre. */
    fun updateZeroAngularRate(gyroMeasured: Vec3, sigma: Double): UpdateResult {
        val h = Matrix(3, N)
        for (i in 0 until 3) h[i, IBG + i] = 1.0
        val d = gyroMeasured - gyroBias
        return update(h, doubleArrayOf(d.x, d.y, d.z), Matrix.diag(sq(sigma), sq(sigma), sq(sigma)))
    }

    /** Pseudo-mesure de vitesse NED (PDR : vitesse de marche dans la direction du cap). */
    fun updateVelocity(measured: Vec3, sigmaH: Double, sigmaV: Double, gate: Double? = CHI2_3DOF): UpdateResult {
        val h = Matrix(3, N)
        for (i in 0 until 3) h[i, IV + i] = 1.0
        val y = doubleArrayOf(measured.x - velocity.x, measured.y - velocity.y, measured.z - velocity.z)
        return update(h, y, Matrix.diag(sq(sigmaH), sq(sigmaH), sq(sigmaV)), gate)
    }

    /** Réinitialise la position (et décorrèle les états de position). */
    fun resetPosition(p: Vec3, sigmaH: Double, sigmaV: Double) {
        position = p
        for (i in 0 until 3) for (j in 0 until N) {
            covariance[IP + i, j] = 0.0
            covariance[j, IP + i] = 0.0
        }
        setVar(IP, sigmaH, sigmaH, sigmaV)
    }

    fun sigma(index: Int) = sqrt(covariance[index, index].coerceAtLeast(0.0))

    /**
     * Jacobien des angles d'Euler (roulis, tangage, lacet) par rapport à l'erreur d'attitude φ
     * (petit angle en repère nav) : δeuler = E⁻¹ · C_bn · φ, avec E la matrice reliant les
     * dérivées des angles ZYX à la vitesse angulaire en repère porteur.
     */
    fun eulerJacobian(): Matrix {
        val e = euler
        val sr = kotlin.math.sin(e.x)
        val cr = kotlin.math.cos(e.x)
        val cp = kotlin.math.cos(e.y).let { if (kotlin.math.abs(it) < 1e-6) 1e-6 else it }
        val tp = kotlin.math.sin(e.y) / cp
        val eInv = Matrix(
            3, 3, doubleArrayOf(
                1.0, sr * tp, cr * tp,
                0.0, cr, -sr,
                0.0, sr / cp, cr / cp,
            )
        )
        return eInv * attitude.toDcm().transpose()
    }

    /** Écarts-types (rad) du roulis, du tangage et du lacet. */
    fun eulerSigmas(): Vec3 {
        val j = eulerJacobian()
        val s = j * covariance.block(IA, IA, 3, 3) * j.transpose()
        return Vec3(sqrt(s[0, 0].coerceAtLeast(0.0)), sqrt(s[1, 1].coerceAtLeast(0.0)), sqrt(s[2, 2].coerceAtLeast(0.0)))
    }

    private fun inject(dx: DoubleArray) {
        position += Vec3(dx[IP], dx[IP + 1], dx[IP + 2])
        velocity += Vec3(dx[IV], dx[IV + 1], dx[IV + 2])
        attitude = (Quaternion.fromRotationVector(Vec3(dx[IA], dx[IA + 1], dx[IA + 2])) * attitude).normalized()
        accBias += Vec3(dx[IBA], dx[IBA + 1], dx[IBA + 2])
        gyroBias += Vec3(dx[IBG], dx[IBG + 1], dx[IBG + 2])
    }

    private fun setVar(start: Int, s0: Double, s1: Double, s2: Double) {
        covariance[start, start] = sq(s0)
        covariance[start + 1, start + 1] = sq(s1)
        covariance[start + 2, start + 2] = sq(s2)
    }

    private fun sq(v: Double) = v * v

    companion object {
        const val N = 15
        const val IP = 0
        const val IV = 3
        const val IA = 6
        const val IBA = 9
        const val IBG = 12
        const val GRAVITY = Params.Physics.GRAVITY
        const val MAX_DT = Params.Kal.MAX_DT_S

        /** Seuils χ² à 99,9 %. */
        const val CHI2_1DOF = Params.Kal.CHI2_1DOF
        const val CHI2_2DOF = Params.Kal.CHI2_2DOF
        const val CHI2_3DOF = Params.Kal.CHI2_3DOF
    }
}
