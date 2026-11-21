package fr.inertialnav.iau.config

import kotlin.math.PI

/**
 * ============================================================================================
 *  CataNav — PARAMÈTRES CENTRALISÉS
 * ============================================================================================
 *
 * Tous les réglages de l'application sont regroupés ici, par module. Les unités sont indiquées
 * pour chaque valeur. Modifier une valeur puis recompiler suffit : aucun autre fichier ne
 * contient de réglage en dur.
 *
 * Organisation :
 *   1. Physique        constantes physiques et géodésiques
 *   2. Imu             acquisition accéléromètre / gyromètre
 *   3. Msu             magnétomètre : acquisition, calibration, rejet des perturbations
 *   4. Gps             critères de validité des positions GNSS
 *   5. Pedestrian      aides piéton : détection de pas (PDR) et ZUPT/ZARU
 *   6. Kal             filtre de Kalman : bruits, initialisation, recalages, tests de rejet
 *   7. Map             hybridation carte (catacombes) et affichage du plan
 *   8. Loc             publication de la position hybridée
 *   9. Engine          cadences du moteur et tailles d'historique
 *  10. Ui              interface : graphiques, fenêtres de temps, carte
 *
 * Hors de ce fichier :
 *   - l'extraction du réseau et des tuiles depuis le GeoTIFF est réglée dans
 *     tools/map/catacombes_config.json (outil Python exécuté sur PC) ; les noms d'assets et
 *     niveaux de zoom ci-dessous (section Map) doivent rester cohérents avec ce fichier ;
 *   - la structure du filtre (index des états) et les formats de fichiers restent dans le code.
 * ============================================================================================
 */
object Params {

    private const val DEG = PI / 180.0

    // =========================================================================================
    // 1. PHYSIQUE
    // =========================================================================================
    object Physics {
        /** Pesanteur standard (m/s²). Utilisée par la mécanisation et la détection de pas. */
        const val GRAVITY = 9.80665

        /** Ellipsoïde WGS84 : demi-grand axe (m) et excentricité au carré. */
        const val WGS84_A = 6378137.0
        const val WGS84_E2 = 6.69437999014e-3
    }

    // =========================================================================================
    // 2. IMU — accéléromètre et gyromètre
    // =========================================================================================
    object Imu {
        /** Période d'échantillonnage demandée aux capteurs (µs). 10 000 µs = 100 Hz. */
        const val SAMPLING_PERIOD_US = 10_000

        /** Nombre d'échantillons au repos pour l'alignement initial (roulis/tangage). ~1 s à 100 Hz. */
        const val ALIGNMENT_SAMPLES = 100
    }

    // =========================================================================================
    // 3. MSU — magnétomètre
    // =========================================================================================
    object Msu {
        /** Période d'échantillonnage du magnétomètre (µs). 20 000 µs = 50 Hz. */
        const val SAMPLING_PERIOD_US = 20_000

        /** Période minimale entre deux recalages de cap (ns). 100 ms = 10 Hz. */
        const val UPDATE_PERIOD_NS = 100_000_000L

        /** Écart-type du cap magnétique avec calibration utilisateur (rad). */
        val HEADING_SIGMA_CALIBRATED = 5.0 * DEG

        /** Écart-type du cap magnétique avec la seule calibration système Android (rad). */
        val HEADING_SIGMA_SYSTEM = 10.0 * DEG

        /** Écart-type du cap initial lorsqu'il est fourni par le magnétomètre (rad). */
        val INITIAL_YAW_SIGMA_WITH_MAG = 15.0 * DEG

        /** Écart-type du cap initial sans magnétomètre (rad) : cap quasi inconnu. */
        val INITIAL_YAW_SIGMA_WITHOUT_MAG = 90.0 * DEG

        // --- Rejet des perturbations (comparaison au modèle WMM du lieu) ---
        /** Intensité de champ supposée tant que la position est inconnue (µT). */
        const val DEFAULT_FIELD_UT = 48.0

        /** Écart relatif toléré entre la norme mesurée et la norme attendue (0,15 = ±15 %). */
        const val NORM_TOLERANCE = 0.15

        /** Écart toléré sur l'inclinaison magnétique (rad). */
        val DIP_TOLERANCE = 10.0 * DEG

        // --- Calibration fer dur / fer doux ---
        /** Nombre minimal de mesures pour l'ajustement d'ellipsoïde. */
        const val CALIBRATION_MIN_SAMPLES = 50

        /** Nombre maximal de mesures collectées (≈ 150 s à 20 Hz). */
        const val CALIBRATION_MAX_SAMPLES = 3000

        /** Période de collecte des mesures de calibration (ns). 50 ms = 20 Hz. */
        const val CALIBRATION_SAMPLE_PERIOD_NS = 50_000_000L

        /** Résidu relatif maximal pour accepter une calibration (0,10 = 10 %). */
        const val CALIBRATION_MAX_RESIDUAL = 0.10

        /** Recalcul de l'ajustement provisoire pendant la collecte : tous les N ticks moteur. */
        const val CALIBRATION_PREVIEW_EVERY_TICKS = 5L

        /** Traînée de mesures affichée dans l'onglet Calibration : période (ns) et longueur. */
        const val TRAIL_PERIOD_NS = 50_000_000L
        const val TRAIL_SIZE = 200

        /** Couverture de la sphère : bandes d'égale aire × secteurs d'azimut. */
        const val COVERAGE_BANDS = 4
        const val COVERAGE_SECTORS = 8
    }

    // =========================================================================================
    // 4. GPS
    // =========================================================================================
    object Gps {
        /** Précision horizontale maximale acceptée (m) ; au-delà, la position est « imprécise ». */
        const val MAX_ACCURACY_M = 30.0

        /** Âge maximal d'une position pour être considérée rafraîchie (s). */
        const val MAX_AGE_S = 2.0

        /** Âge maximal de la dernière position connue pour fixer l'origine au démarrage (s). */
        const val LAST_KNOWN_MAX_AGE_S = 300.0

        /** Âge maximal d'un GPS pour servir de position initiale au (re)démarrage du filtre (s). */
        const val RESTART_FIX_MAX_AGE_S = 5.0

        /** Rejets consécutifs après lesquels KAL se recale brutalement sur le GPS. */
        const val MAX_CONSECUTIVE_REJECTS = 5
    }

    // =========================================================================================
    // 5. AIDES PIÉTON
    // =========================================================================================
    object Pedestrian {
        // --- Détection de pas (PDR) ---
        /** Seuil de pic au-dessus de g sur |a| filtrée (m/s²). */
        const val STEP_PEAK_THRESHOLD = 1.2

        /** Intervalle minimal entre deux pas (s) : rejette les rebonds. */
        const val STEP_MIN_INTERVAL_S = 0.3

        /** Au-delà de cet intervalle (s), on considère une reprise de marche. */
        const val STEP_MAX_INTERVAL_S = 2.0

        /** Période supposée pour le premier pas d'une reprise (s). */
        const val STEP_DEFAULT_PERIOD_S = 0.6

        /** Constante K du modèle de Weinberg : L = K · (a_max − a_min)^¼ (a en m/s²). */
        const val WEINBERG_K = 0.47

        /** Fréquence de coupure du filtre passe-bas sur |a| (Hz). */
        const val STEP_FILTER_CUTOFF_HZ = 3.0

        /** Écart-type horizontal de la vitesse PDR : A + B · vitesse (m/s). */
        const val PDR_SIGMA_BASE = 0.15
        const val PDR_SIGMA_PER_MPS = 0.25

        /** Écart-type de la pseudo-mesure de vitesse verticale nulle pendant la marche (m/s). */
        const val PDR_SIGMA_VERTICAL = 0.1

        // --- Détection d'immobilité (ZUPT / ZARU) ---
        /** Fenêtre glissante (échantillons IMU). 25 = 0,25 s à 100 Hz. */
        const val ZUPT_WINDOW = 25

        /** Écart-type maximal de |a| sur la fenêtre pour déclarer l'immobilité (m/s²). */
        const val ZUPT_ACC_STD_THRESHOLD = 0.25

        /** |ω| maximal sur la fenêtre pour déclarer l'immobilité (rad/s). */
        const val ZUPT_GYRO_THRESHOLD = 0.3

        /** Période minimale entre deux recalages ZUPT (ns). 100 ms = 10 Hz. */
        const val ZUPT_PERIOD_NS = 100_000_000L

        /** Écart-type de la pseudo-mesure de vitesse nulle (m/s). */
        const val ZUPT_SIGMA = 0.03

        /** Écart-type de la pseudo-mesure de vitesse angulaire nulle, ZARU (rad/s). */
        const val ZARU_SIGMA = 0.02
    }

    // =========================================================================================
    // 6. KAL — filtre de Kalman étendu à erreur d'état (15 états)
    // =========================================================================================
    object Kal {
        // --- Modèle de bruit IMU (densités spectrales) ---
        /** Bruit blanc accéléromètre (m/s²/√Hz). */
        const val ACC_NOISE_DENSITY = 0.02

        /** Bruit blanc gyromètre (rad/s/√Hz). */
        const val GYRO_NOISE_DENSITY = 2e-3

        /** Marche aléatoire du biais accéléromètre (m/s³/√Hz). */
        const val ACC_BIAS_RANDOM_WALK = 5e-4

        /** Marche aléatoire du biais gyromètre (rad/s²/√Hz). */
        const val GYRO_BIAS_RANDOM_WALK = 5e-5

        /** Intervalle maximal entre deux échantillons IMU propagés (s) ; au-delà : trou ignoré. */
        const val MAX_DT_S = 0.2

        // --- Incertitudes initiales (1σ) ---
        /** Position horizontale / verticale sans GPS récent (m). */
        const val INIT_SIGMA_POS_H = 10.0
        const val INIT_SIGMA_POS_V = 10.0

        /** Vitesse (m/s). */
        const val INIT_SIGMA_VEL = 0.1

        /** Roulis et tangage après alignement statique (rad). */
        val INIT_SIGMA_ROLL_PITCH = 2.0 * DEG

        /** Cap par défaut (rad) ; voir aussi Msu.INITIAL_YAW_SIGMA_*. */
        val INIT_SIGMA_YAW = 30.0 * DEG

        /** Biais accéléromètre (m/s²) et gyromètre (rad/s). */
        const val INIT_SIGMA_ACC_BIAS = 0.2
        const val INIT_SIGMA_GYRO_BIAS = 0.01

        // --- Tests de rejet des mesures (χ² sur la NIS, risque 0,1 %) ---
        const val CHI2_1DOF = 10.83
        const val CHI2_2DOF = 13.82
        const val CHI2_3DOF = 16.27
    }

    // =========================================================================================
    // 7. CARTE — hybridation et plan des catacombes
    // =========================================================================================
    object Map {
        /** Réseau praticable (produit par tools/map/build_network_map.py). */
        const val NETWORK_ASSET = "maps/catacombes.iaumap"

        /** Recalage carte : tous les N ticks moteur. 5 × 200 ms = 1 Hz. */
        const val UPDATE_EVERY_TICKS = 5L

        /** Écart-type du recalage carte (m) : demi-largeur de galerie + erreur de géoréférencement. */
        const val SNAP_SIGMA_M = 1.5

        /** Rayon de recherche du point praticable : 3σ horizontal borné à [MIN, MAX] (m). */
        const val SEARCH_SIGMA_FACTOR = 3.0
        const val MIN_SEARCH_RADIUS_M = 5.0
        const val MAX_SEARCH_RADIUS_M = 40.0

        // --- Plan affiché (tuiles produites par tools/map/build_plan_tiles.py) ---
        /** Dossier des tuiles dans les assets : <nom>/<z>/<x>/<y>.webp. */
        const val PLAN_TILES = "catacombes_plan"
        const val PLAN_TILE_EXTENSION = ".webp"
        const val PLAN_MIN_ZOOM = 14
        const val PLAN_MAX_ZOOM = 18

        /** Centre du plan (pour l'afficher sans position connue). */
        const val PLAN_CENTER_LAT = 48.8341
        const val PLAN_CENTER_LON = 2.3242

        /** Zoom d'ouverture sur le plan sans position connue. */
        const val PLAN_OVERVIEW_ZOOM = 15.0
    }

    // =========================================================================================
    // 8. LOC — publication de la position hybridée
    // =========================================================================================
    object Loc {
        /** Précision minimale annoncée aux autres applications (m). */
        const val MIN_HORIZONTAL_ACCURACY_M = 1.0
        const val MIN_VERTICAL_ACCURACY_M = 1.0

        /** Précision minimale de vitesse annoncée (m/s). */
        const val MIN_SPEED_ACCURACY_MPS = 0.1

        /** En dessous de cette vitesse (m/s), le cap de déplacement est déclaré inconnu (±180°). */
        const val MIN_SPEED_FOR_BEARING_MPS = 0.3
    }

    // =========================================================================================
    // 9. MOTEUR — cadences et historiques
    // =========================================================================================
    object Engine {
        /** Période du tick moteur (ms) : instantanés IHM, historique, LOC. 200 ms = 5 Hz. */
        const val TICK_MS = 200L

        /** Publication de l'historique vers l'IHM : tous les N ticks. 2 → 2,5 Hz. */
        const val HISTORY_PUBLISH_EVERY_TICKS = 2L

        /** Historique filtre : 1 point par tick. 3000 = 10 min à 5 Hz. */
        const val HISTORY_SIZE = 3000

        /** Historique des positions GPS valides (points). */
        const val GPS_HISTORY_SIZE = 1200

        /** Moyennage des mesures IMU historisées : 4 → 100 Hz ramené à 25 Hz. */
        const val IMU_HISTORY_DECIMATION = 4

        /** Historique IMU (points). 15 000 = 10 min à 25 Hz. */
        const val IMU_HISTORY_SIZE = 15_000
    }

    // =========================================================================================
    // 10. IHM
    // =========================================================================================
    object Ui {
        /** Fenêtres de défilement proposées dans le Moniteur (s) ; ne pas dépasser l'historique. */
        val TIME_WINDOWS_S = listOf(30.0, 60.0, 120.0, 300.0, 600.0)

        /** Fenêtre sélectionnée à l'ouverture (index dans TIME_WINDOWS_S). */
        const val DEFAULT_TIME_WINDOW_INDEX = 1

        // --- Graphiques ---
        /** Nombre maximal de points tracés par courbe (décimation au-delà). */
        const val CHART_MAX_POINTS = 600

        /** Largeur fixe de l'axe vertical (dp) : aligne les panneaux d'un sous-graphe. */
        const val CHART_AXIS_WIDTH_DP = 62f

        /** Hauteur d'un panneau de sous-graphe (dp). */
        const val SUBPLOT_PANEL_HEIGHT_DP = 130

        /** Opacité des zones ±3σ (0–255). */
        const val SIGMA_BAND_ALPHA = 0x38

        // --- Carte ---
        /** Nombre maximal de points des traces KAL / GPS sur la carte. */
        const val MAP_MAX_TRACK_POINTS = 1500

        /** Sous-échantillonnage du réseau d'hybridation affiché (4 → ≈ 2 m/pixel). */
        const val MAP_NETWORK_DOWNSAMPLE = 4

        /** Zoom appliqué lors de la première position connue. */
        const val MAP_FIRST_FIX_ZOOM = 18.0

        // --- Boussole ---
        /** Taille du cadran (dp). */
        const val COMPASS_SIZE_DP = 300

        /** Inclinaisons repérées par des cercles sur la boussole (degrés). */
        val COMPASS_TILT_RINGS_DEG = listOf(30.0, 60.0)
    }
}
