package fr.inertialnav.iau.engine

import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.gps.GpsStatus
import fr.inertialnav.iau.loc.MockStatus

/** Commandes utilisateur (IHM). */
data class HybridationSettings(
    /** Hybridation magnétomètre (azimut). */
    val magEnabled: Boolean = true,
    /** Hybridation GPS (lat, lon, alt). */
    val gpsEnabled: Boolean = true,
    /** Hybridation carte (prochainement). */
    val mapEnabled: Boolean = false,
    /** Pseudo-mesure de vitesse par détection de pas. */
    val pdrEnabled: Boolean = true,
    /** Pseudo-mesure de vitesse nulle à l'arrêt. */
    val zuptEnabled: Boolean = true,
    /** Publication de la position hybridée par le module LOC. */
    val mockEnabled: Boolean = false,
)

enum class MapStatus(val label: String) {
    OFF("Désactivée"),
    UNAVAILABLE("Carte indisponible"),
    NO_POSITION("Position absolue inconnue (attente GPS)"),
    OUT_OF_MAP("Hors de l'emprise de la carte"),
    ON_NETWORK("Sur le réseau"),
    SNAPPED("Recalée sur le réseau"),
    NO_NETWORK("Aucune galerie à proximité"),
    REJECTED("Recalage rejeté (incohérent)"),
}

enum class MagStatus(val label: String) {
    UNAVAILABLE("Absent"),
    SYSTEM_CALIBRATION("Calibration système"),
    USER_CALIBRATION("Calibration utilisateur"),
    DISTURBED("Perturbé (rejeté)"),
}

enum class FilterPhase(val label: String) {
    STOPPED("Arrêté"),
    ALIGNING("Alignement (rester immobile)"),
    NAVIGATING("Navigation"),
}

/** Instantané de l'état du moteur, publié vers l'IHM (~5 Hz). */
data class NavSnapshot(
    val phase: FilterPhase = FilterPhase.STOPPED,
    val imuRateHz: Double = 0.0,
    val magRateHz: Double = 0.0,
    val originSet: Boolean = false,
    val latitudeDeg: Double = Double.NaN,
    val longitudeDeg: Double = Double.NaN,
    val altitudeM: Double = Double.NaN,
    val positionNed: Vec3 = Vec3.ZERO,
    val velocityNed: Vec3 = Vec3.ZERO,
    /** Roulis, tangage, lacet (degrés). */
    val eulerDeg: Vec3 = Vec3.ZERO,
    val sigmaHorizontalM: Double = Double.NaN,
    val sigmaVerticalM: Double = Double.NaN,
    val sigmaYawDeg: Double = Double.NaN,
    val sigmaRollDeg: Double = Double.NaN,
    val sigmaPitchDeg: Double = Double.NaN,
    /** Verticale (direction « bas ») mesurée par les accéléromètres, repère FRD unitaire (moyenne 40 ms). */
    val measuredDownBody: Vec3? = null,
    val accBias: Vec3 = Vec3.ZERO,
    val gyroBias: Vec3 = Vec3.ZERO,
    // GPS
    val gpsStatus: GpsStatus = GpsStatus.SEARCHING,
    val gpsAccuracyM: Double = Double.NaN,
    val gpsSatellites: Int = 0,
    val gpsUpdates: Int = 0,
    val gpsRejected: Int = 0,
    // MSU
    val magStatus: MagStatus = MagStatus.UNAVAILABLE,
    val magHeadingDeg: Double = Double.NaN,
    val magFieldNormUt: Double = Double.NaN,
    val magExpectedNormUt: Double = Double.NaN,
    val magUpdates: Int = 0,
    val magRejected: Int = 0,
    // Piéton
    val stationary: Boolean = false,
    val zuptCount: Int = 0,
    val stepCount: Int = 0,
    val lastStepLengthM: Double = Double.NaN,
    val pdrSpeedMps: Double = Double.NaN,
    // LOC
    val mockStatus: MockStatus = MockStatus.OFF,
    val mockError: String? = null,
    val mockPublished: Long = 0,
    /** Mode fictif du Fused Location Provider de Google Play (utilisé par Google Maps…). */
    val mockPlayServicesActive: Boolean = false,
    // Carte
    val mapStatus: MapStatus = MapStatus.OFF,
    val mapName: String? = null,
    val mapError: String? = null,
    val mapUpdates: Int = 0,
    val mapRejected: Int = 0,
    /** Distance au réseau praticable lors du dernier appariement (m). */
    val mapDistanceM: Double = Double.NaN,
    // Calibration
    val calibration: CalibrationInfo = CalibrationInfo(),
)

data class CalibrationInfo(
    val collecting: Boolean = false,
    val samples: Int = 0,
    val hasUserCalibration: Boolean = false,
    val hardIron: Vec3? = null,
    val softIronDiag: Vec3? = null,
    val residualPct: Double = Double.NaN,
    val fieldRadiusUt: Double = Double.NaN,
    val lastResult: String? = null,
)

/**
 * Vue « en direct » de la calibration magnétique pour l'IHM (mesures brutes, repère FRD, µT).
 * Tableaux x, y, z entrelacés : [x0, y0, z0, x1, y1, z1, …].
 */
class CalibrationView(
    /** Mesures collectées pendant la calibration en cours (ou la dernière terminée). */
    val collected: DoubleArray,
    /** Traînée des dernières mesures brutes, collectées ou non. */
    val trail: DoubleArray,
    /** Ajustement provisoire sur les mesures collectées (mis à jour ~1 Hz). */
    val preview: fr.inertialnav.iau.msu.MagCalibration?,
    /** Calibration utilisateur active. */
    val active: fr.inertialnav.iau.msu.MagCalibration?,
    /** Couverture des directions de la sphère par les mesures collectées (0..1). */
    val coverage: Double,
) {
    companion object {
        val EMPTY = CalibrationView(DoubleArray(0), DoubleArray(0), null, null, 0.0)
    }
}

/** Variables historisées (une valeur par tick, 5 Hz). Temps en s depuis le démarrage. */
enum class HistoryKey {
    TIME,
    LAT, LON, ALT, SIGMA_LAT, SIGMA_LON, SIGMA_ALT, // degrés, degrés, m ; σ dans la même unité
    VN, VE, VD, SIGMA_VN, SIGMA_VE, SIGMA_VD, // m/s
    ROLL, PITCH, YAW, SIGMA_ROLL, SIGMA_PITCH, SIGMA_YAW, // degrés (lacet = azimut 0..360)
    MAG_YAW, // cap magnétomètre (degrés, NaN si indisponible ou perturbé)
    BAX, BAY, BAZ, SIGMA_BAX, SIGMA_BAY, SIGMA_BAZ, // m/s²
    BGX, BGY, BGZ, SIGMA_BGX, SIGMA_BGY, SIGMA_BGZ, // rad/s
}

/** Positions GPS valides reçues. */
enum class GpsHistoryKey { TIME, LAT, LON, ALT }

/** Mesures IMU (repère FRD) moyennées à 25 Hz : forces spécifiques (m/s²), vitesses angulaires (rad/s). */
enum class ImuHistoryKey { TIME, FX, FY, FZ, WX, WY, WZ }

/** Historique pour les graphiques et la carte (tableaux alignés sur TIME). */
class NavHistory(
    private val main: Map<HistoryKey, DoubleArray>,
    private val gps: Map<GpsHistoryKey, DoubleArray>,
    private val imu: Map<ImuHistoryKey, DoubleArray> = emptyMap(),
) {
    operator fun get(key: HistoryKey): DoubleArray = main[key] ?: EMPTY_ARRAY
    operator fun get(key: GpsHistoryKey): DoubleArray = gps[key] ?: EMPTY_ARRAY
    operator fun get(key: ImuHistoryKey): DoubleArray = imu[key] ?: EMPTY_ARRAY
    val size: Int get() = this[HistoryKey.TIME].size

    companion object {
        private val EMPTY_ARRAY = DoubleArray(0)
        val EMPTY = NavHistory(emptyMap(), emptyMap())
    }
}

/** Tampon circulaire de doubles. */
internal class DoubleRing(private val capacity: Int) {
    private val buf = DoubleArray(capacity)
    private var start = 0
    var size = 0
        private set

    fun add(v: Double) {
        if (size < capacity) {
            buf[(start + size) % capacity] = v
            size++
        } else {
            buf[start] = v
            start = (start + 1) % capacity
        }
    }

    fun clear() {
        start = 0
        size = 0
    }

    fun toArray() = DoubleArray(size) { buf[(start + it) % capacity] }
}
