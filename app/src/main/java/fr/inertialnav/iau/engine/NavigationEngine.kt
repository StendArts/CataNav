package fr.inertialnav.iau.engine

import fr.inertialnav.iau.config.Params
import android.content.Context
import android.hardware.GeomagneticField
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import fr.inertialnav.iau.core.geo.GeoPosition
import fr.inertialnav.iau.core.geo.LocalTangentPlane
import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.core.math.toDeg
import fr.inertialnav.iau.core.math.toRad
import fr.inertialnav.iau.core.math.wrapPi
import fr.inertialnav.iau.gps.GpsFix
import fr.inertialnav.iau.gps.GpsSource
import fr.inertialnav.iau.gps.GpsStatus
import fr.inertialnav.iau.imu.ImuSample
import fr.inertialnav.iau.imu.ImuSource
import fr.inertialnav.iau.kal.Alignment
import fr.inertialnav.iau.kal.InsEkf
import fr.inertialnav.iau.loc.MockLocationPublisher
import fr.inertialnav.iau.loc.PublishedLocation
import fr.inertialnav.iau.map.RasterNetworkMap
import fr.inertialnav.iau.msu.EllipsoidFit
import fr.inertialnav.iau.msu.MagCalibration
import fr.inertialnav.iau.msu.MagCalibrationStore
import fr.inertialnav.iau.msu.MagHeading
import fr.inertialnav.iau.msu.MagSample
import fr.inertialnav.iau.msu.MagSource
import fr.inertialnav.iau.msu.SphereCoverage
import fr.inertialnav.iau.pedestrian.StepDetector
import fr.inertialnav.iau.pedestrian.ZuptDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chef d'orchestre : relie IMU, MSU, GPS, aides piéton, KAL et LOC.
 *
 * Tout le traitement s'exécute sur un unique HandlerThread (« catanav-nav ») : callbacks capteurs,
 * GPS, filtre et publication. Aucun verrou n'est nécessaire sur l'état du filtre ; l'IHM ne
 * voit que des instantanés immuables publiés via StateFlow.
 */
class NavigationEngine(context: Context) {

    private val appContext = context.applicationContext

    private val _settings = MutableStateFlow(HybridationSettings())
    val settings: StateFlow<HybridationSettings> = _settings.asStateFlow()

    private val _snapshot = MutableStateFlow(NavSnapshot())
    val snapshot: StateFlow<NavSnapshot> = _snapshot.asStateFlow()

    private val _history = MutableStateFlow(NavHistory.EMPTY)
    val history: StateFlow<NavHistory> = _history.asStateFlow()

    private val _calibrationView = MutableStateFlow(CalibrationView.EMPTY)
    val calibrationView: StateFlow<CalibrationView> = _calibrationView.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ---- Tout ce qui suit n'est accédé que depuis le thread de navigation ----
    private var imu: ImuSource? = null
    private var mag: MagSource? = null
    private var gps: GpsSource? = null
    private val mock = MockLocationPublisher(appContext)
    private val calibrationStore = MagCalibrationStore(appContext)
    private var magCalibration: MagCalibration? = calibrationStore.load()

    /** Hybridation carte : réseau praticable chargé à la première activation. */
    private val _networkMap = MutableStateFlow<RasterNetworkMap?>(null)
    val networkMap: StateFlow<RasterNetworkMap?> = _networkMap.asStateFlow()
    private var mapLoadError: String? = null
    private var mapStatus = MapStatus.OFF
    private var mapUpdates = 0
    private var mapRejected = 0
    private var lastMapDistance = Double.NaN

    private val ekf = InsEkf()
    private val stepDetector = StepDetector()
    private val zupt = ZuptDetector()

    private val alignSamples = ArrayList<Vec3>()
    private var origin: LocalTangentPlane? = null
    private var restartPosition = Vec3.ZERO
    private var measuredDown: Vec3? = null
    private var geomag: GeomagneticField? = null
    private var lastMagBody: Vec3? = null
    private var lastMagHeading = Double.NaN
    private var lastMagNorm = Double.NaN
    private var magDisturbed = false
    private var lastMagUpdateNs = 0L
    private var lastZuptNs = 0L
    private var startNs = 0L
    private var consecutiveGpsRejects = 0
    private var lastGpsFix: GpsFix? = null

    private var gpsUpdates = 0
    private var gpsRejected = 0
    private var magUpdates = 0
    private var magRejected = 0
    private var zuptCount = 0
    private var lastStepLength = Double.NaN
    private var pdrSpeed = Double.NaN

    private var calibrating = false
    private val calibrationSamples = ArrayList<Vec3>()
    private var lastCalibrationSampleNs = 0L
    private var calibrationResult: String? = null
    private var calibrationPreview: MagCalibration? = null
    private val magTrail = ArrayDeque<Vec3>()
    private var lastTrailNs = 0L

    private var tickCount = 0L
    private val historyRings = HistoryKey.entries.associateWith { DoubleRing(Params.Engine.HISTORY_SIZE) }
    private val gpsRings = GpsHistoryKey.entries.associateWith { DoubleRing(Params.Engine.GPS_HISTORY_SIZE) }
    private val imuRings = ImuHistoryKey.entries.associateWith { DoubleRing(Params.Engine.IMU_HISTORY_SIZE) }
    private var imuAccSum = Vec3.ZERO
    private var imuGyrSum = Vec3.ZERO
    private var imuSumCount = 0

    val isRunning: Boolean get() = thread != null

    // ===================================================================== cycle de vie

    @Synchronized
    fun start() {
        if (thread != null) return
        val t = HandlerThread("catanav-nav", Process.THREAD_PRIORITY_FOREGROUND).also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post {
            // Nouvelle session : nouvelle origine (premier GPS valide ou dernière position connue)
            origin = null
            geomag = null
            lastGpsFix = null
            ekf.reset()
            restartPosition = Vec3.ZERO
            resetFilterInternal()
            startNs = SystemClock.elapsedRealtimeNanos()
            imu = ImuSource(appContext, h, onSample = ::onImu).also { it.start() }
            mag = MagSource(appContext, h, onSample = ::onMag).also { it.start() }
            gps = GpsSource(appContext, h, onFix = ::onGpsFix).also { src ->
                src.start()
                if (origin == null) src.lastKnownFix(Params.Gps.LAST_KNOWN_MAX_AGE_S)?.let { setOrigin(it) }
            }
            applySettings()
            h.postDelayed(tickRunnable, Params.Engine.TICK_MS)
        }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        val h = handler ?: return
        h.removeCallbacks(tickRunnable)
        h.post {
            imu?.stop()
            mag?.stop()
            gps?.stop()
            mock.disable()
            imu = null
            mag = null
            gps = null
            ekf.reset()
            _snapshot.value = buildSnapshot().copy(phase = FilterPhase.STOPPED)
        }
        t.quitSafely()
        thread = null
        handler = null
    }

    // ===================================================================== commandes IHM

    fun updateSettings(transform: (HybridationSettings) -> HybridationSettings) {
        _settings.update(transform)
        post { applySettings() }
    }

    fun resetFilter() = post { resetFilterInternal() }

    fun startMagCalibration() = post {
        calibrationSamples.clear()
        calibrationPreview = null
        calibrating = true
        calibrationResult = null
        publishSnapshot()
    }

    fun finishMagCalibration() = post {
        calibrating = false
        val cal = EllipsoidFit.fitAxisAligned(calibrationSamples)
        calibrationResult = when {
            cal == null -> "Échec : pas assez de mesures ou couverture insuffisante (min ${Params.Msu.CALIBRATION_MIN_SAMPLES})"
            cal.fitResidual > Params.Msu.CALIBRATION_MAX_RESIDUAL -> "Rejetée : résidu trop élevé (${"%.1f".format(cal.fitResidual * 100)} %)"
            else -> {
                magCalibration = cal
                calibrationStore.save(cal)
                "Calibration enregistrée (${cal.sampleCount} mesures)"
            }
        }
        // Les mesures sont conservées pour l'affichage du résultat
        calibrationPreview = cal
        publishSnapshot()
        publishCalibrationView()
    }

    fun cancelMagCalibration() = post {
        calibrating = false
        calibrationSamples.clear()
        calibrationPreview = null
        publishSnapshot()
    }

    fun clearMagCalibration() {
        val h = handler
        if (h == null) {
            calibrationStore.clear()
            magCalibration = null
            calibrationResult = "Calibration utilisateur effacée"
            _calibrationView.value.let { _calibrationView.value = CalibrationView(it.collected, it.trail, it.preview, null, it.coverage) }
            return
        }
        h.post {
            calibrationStore.clear()
            magCalibration = null
            calibrationResult = "Calibration utilisateur effacée"
            publishSnapshot()
            publishCalibrationView()
        }
    }

    private fun post(block: () -> Unit) {
        handler?.post(block)
    }

    private fun applySettings() {
        val s = _settings.value
        val wanted: Set<String> = if (s.mockEnabled) {
            buildSet {
                add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
        } else {
            emptySet()
        }
        if (wanted != mock.activeProviders || (wanted.isNotEmpty() && mock.status != fr.inertialnav.iau.loc.MockStatus.ACTIVE)) {
            if (wanted.isEmpty()) mock.disable() else mock.enable(wanted)
        }
        if (s.mapEnabled) loadNetworkMap() else mapStatus = MapStatus.OFF
    }

    private fun loadNetworkMap() {
        if (_networkMap.value != null || mapLoadError != null) return
        try {
            _networkMap.value = appContext.assets.open(Params.Map.NETWORK_ASSET).use { RasterNetworkMap.read(it) }
        } catch (e: java.io.IOException) {
            mapLoadError = e.message ?: "Lecture impossible"
            mapStatus = MapStatus.UNAVAILABLE
        }
    }

    private fun resetFilterInternal() {
        // L'origine est conservée ; le filtre repartira de la dernière position estimée
        if (ekf.initialized) restartPosition = ekf.position
        ekf.reset()
        alignSamples.clear()
        stepDetector.reset()
        zupt.reset()
        consecutiveGpsRejects = 0
        gpsUpdates = 0
        gpsRejected = 0
        magUpdates = 0
        magRejected = 0
        mapUpdates = 0
        mapRejected = 0
        zuptCount = 0
        lastStepLength = Double.NaN
        pdrSpeed = Double.NaN
        historyRings.values.forEach { it.clear() }
        gpsRings.values.forEach { it.clear() }
        imuRings.values.forEach { it.clear() }
        _history.value = NavHistory.EMPTY
    }

    // ===================================================================== IMU

    private fun onImu(s: ImuSample) {
        recordImu(s)
        val st = _settings.value
        if (!ekf.initialized) {
            alignSamples += s.acc
            if (alignSamples.size >= Params.Imu.ALIGNMENT_SAMPLES) initializeFilter(s.timestampNs)
            return
        }

        ekf.propagate(s.timestampNs, s.acc, s.gyr)

        // --- ZUPT ---
        val stationary = zupt.process(s.acc, s.gyr)
        if (st.zuptEnabled && stationary && s.timestampNs - lastZuptNs >= Params.Pedestrian.ZUPT_PERIOD_NS) {
            lastZuptNs = s.timestampNs
            ekf.updateZeroVelocity(sigma = Params.Pedestrian.ZUPT_SIGMA)
            ekf.updateZeroAngularRate(s.gyr, sigma = Params.Pedestrian.ZARU_SIGMA)
            zuptCount++
        }

        // --- Détection de pas (PDR) ---
        val step = stepDetector.process(s.timestampNs, s.acc.norm())
        if (step != null) {
            lastStepLength = step.lengthM
            pdrSpeed = step.speedMps
            if (st.pdrEnabled && !stationary) {
                // Hypothèse : téléphone tenu devant soi, axe « haut de l'écran » dans le sens de marche
                val yaw = ekf.euler.z
                val v = step.speedMps
                ekf.updateVelocity(
                    Vec3(v * cos(yaw), v * sin(yaw), 0.0),
                    sigmaH = Params.Pedestrian.PDR_SIGMA_BASE + Params.Pedestrian.PDR_SIGMA_PER_MPS * v,
                    sigmaV = Params.Pedestrian.PDR_SIGMA_VERTICAL,
                )
            }
        }
    }

    private fun initializeFilter(timestampNs: Long) {
        var mean = Vec3.ZERO
        alignSamples.forEach { mean += it }
        mean /= alignSamples.size.toDouble()
        alignSamples.clear()

        val (roll, pitch) = Alignment.rollPitch(mean)
        val magBody = lastMagBody
        val useMag = magBody != null && _settings.value.magEnabled
        val yaw = if (useMag) {
            MagHeading.heading(MagHeading.levelField(magBody!!, roll, pitch), declination())
        } else {
            0.0
        }
        // Position initiale : dernier GPS récent, sinon dernière position estimée
        val o = origin
        val fix = lastGpsFix?.takeIf { o != null && fixAgeS(it) < Params.Gps.RESTART_FIX_MAX_AGE_S }
        val position = if (fix != null && o != null) {
            o.toNed(GeoPosition(fix.latitudeDeg, fix.longitudeDeg, fix.altitudeM))
        } else {
            restartPosition
        }
        ekf.initialize(
            timestampNs = timestampNs,
            attitude = Quaternion.fromEuler(roll, pitch, yaw),
            position = position,
            sigmaPosH = fix?.horizontalAccuracyM ?: Params.Kal.INIT_SIGMA_POS_H,
            sigmaPosV = fix?.verticalAccuracyM ?: Params.Kal.INIT_SIGMA_POS_V,
            sigmaYaw = if (useMag) Params.Msu.INITIAL_YAW_SIGMA_WITH_MAG else Params.Msu.INITIAL_YAW_SIGMA_WITHOUT_MAG,
        )
    }

    // ===================================================================== MSU

    private fun onMag(s: MagSample) {
        if (calibrating && s.timestampNs - lastCalibrationSampleNs >= Params.Msu.CALIBRATION_SAMPLE_PERIOD_NS &&
            calibrationSamples.size < Params.Msu.CALIBRATION_MAX_SAMPLES
        ) {
            lastCalibrationSampleNs = s.timestampNs
            calibrationSamples += s.raw
        }
        if (s.timestampNs - lastTrailNs >= Params.Msu.TRAIL_PERIOD_NS) {
            lastTrailNs = s.timestampNs
            magTrail.addLast(s.raw)
            if (magTrail.size > Params.Msu.TRAIL_SIZE) magTrail.removeFirst()
        }
        val m = magCalibration?.apply(s.raw) ?: (s.raw - s.systemBias)
        lastMagBody = m
        lastMagNorm = m.norm()

        if (!ekf.initialized) return
        val e = ekf.euler
        val level = MagHeading.levelField(m, e.x, e.y)
        lastMagHeading = MagHeading.heading(level, declination())

        if (!_settings.value.magEnabled || calibrating) return
        if (s.timestampNs - lastMagUpdateNs < Params.Msu.UPDATE_PERIOD_NS) return
        lastMagUpdateNs = s.timestampNs

        // Rejet des perturbations : norme et inclinaison comparées au modèle WMM
        val gm = geomag
        val expectedNorm = gm?.fieldStrength?.div(1000.0) ?: Params.Msu.DEFAULT_FIELD_UT
        val normOk = abs(lastMagNorm - expectedNorm) / expectedNorm < Params.Msu.NORM_TOLERANCE
        val dipOk = gm == null ||
            abs(MagHeading.inclination(level) - gm.inclination.toDouble().toRad()) < Params.Msu.DIP_TOLERANCE
        magDisturbed = !(normOk && dipOk)
        if (magDisturbed) {
            magRejected++
            return
        }
        val sigma = if (magCalibration != null) Params.Msu.HEADING_SIGMA_CALIBRATED else Params.Msu.HEADING_SIGMA_SYSTEM
        if (ekf.updateHeading(lastMagHeading, sigma).accepted) magUpdates++ else magRejected++
    }

    private fun declination(): Double = geomag?.declination?.toDouble()?.toRad() ?: 0.0

    // ===================================================================== GPS

    private fun onGpsFix(fix: GpsFix) {
        lastGpsFix = fix
        val o = origin
        if (o == null) {
            setOrigin(fix)
            recordGps(fix)
            if (ekf.initialized) ekf.resetPosition(Vec3.ZERO, fix.horizontalAccuracyM, fix.verticalAccuracyM)
            return
        }
        val ned = o.toNed(GeoPosition(fix.latitudeDeg, fix.longitudeDeg, fix.altitudeM))
        recordGps(fix)

        if (!_settings.value.gpsEnabled || !ekf.initialized) return
        val r = ekf.updatePosition(ned, fix.horizontalAccuracyM, fix.verticalAccuracyM)
        if (r.accepted) {
            gpsUpdates++
            consecutiveGpsRejects = 0
        } else {
            gpsRejected++
            // Après une longue dérive, on accepte de se recaler brutalement sur le GPS
            if (++consecutiveGpsRejects >= Params.Gps.MAX_CONSECUTIVE_REJECTS) {
                ekf.resetPosition(ned, fix.horizontalAccuracyM, fix.verticalAccuracyM)
                consecutiveGpsRejects = 0
            }
        }
    }

    private fun setOrigin(fix: GpsFix) {
        val geo = GeoPosition(fix.latitudeDeg, fix.longitudeDeg, fix.altitudeM)
        origin = LocalTangentPlane(geo)
        geomag = GeomagneticField(
            fix.latitudeDeg.toFloat(), fix.longitudeDeg.toFloat(), fix.altitudeM.toFloat(), System.currentTimeMillis()
        )
    }

    private fun fixAgeS(fix: GpsFix) = (SystemClock.elapsedRealtimeNanos() - fix.timestampNs) * 1e-9

    // ===================================================================== tâche périodique

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler?.postDelayed(this, Params.Engine.TICK_MS)
        }
    }

    private fun tick() {
        tickCount++
        val now = SystemClock.elapsedRealtimeNanos()
        gps?.checkStaleness(now)

        if (ekf.initialized) {
            recordHistory(now)
            if (_settings.value.mapEnabled && tickCount % Params.Map.UPDATE_EVERY_TICKS == 0L) mapUpdate()
            if (_settings.value.mockEnabled) publishMock()
        }
        publishSnapshot()
        if (tickCount % Params.Engine.HISTORY_PUBLISH_EVERY_TICKS == 0L) {
            publishHistory()
            publishCalibrationView()
        }
        // Ajustement provisoire pendant la collecte (~1 Hz)
        if (calibrating && tickCount % Params.Msu.CALIBRATION_PREVIEW_EVERY_TICKS == 0L) {
            calibrationPreview = EllipsoidFit.fitAxisAligned(calibrationSamples)
        }
    }

    /**
     * Hybridation carte (~1 Hz) : si la position estimée sort du réseau praticable, recalage
     * vers le point praticable le plus proche, dans la seule direction de ce point.
     */
    private fun mapUpdate() {
        val map = _networkMap.value
        if (map == null) {
            mapStatus = MapStatus.UNAVAILABLE
            return
        }
        val o = origin
        if (o == null) {
            mapStatus = MapStatus.NO_POSITION
            return
        }
        val geo = o.toGeo(ekf.position)
        if (!map.covers(geo.latitudeDeg, geo.longitudeDeg)) {
            mapStatus = MapStatus.OUT_OF_MAP
            return
        }
        val radius = (Params.Map.SEARCH_SIGMA_FACTOR * horizontalSigma()).coerceIn(Params.Map.MIN_SEARCH_RADIUS_M, Params.Map.MAX_SEARCH_RADIUS_M)
        val match = map.nearest(geo.latitudeDeg, geo.longitudeDeg, radius)
        if (match == null) {
            mapStatus = MapStatus.NO_NETWORK
            lastMapDistance = Double.NaN
            return
        }
        lastMapDistance = match.distanceM
        if (match.onNetwork) {
            mapStatus = MapStatus.ON_NETWORK
            return
        }
        val target = o.toNed(GeoPosition(match.position.latitudeDeg, match.position.longitudeDeg, geo.altitudeM))
        val p = ekf.position
        val dN = target.x - p.x
        val dE = target.y - p.y
        val d = hypot(dN, dE)
        if (d < 1e-3) return
        val r = ekf.updateAlongDirection(dN / d, dE / d, d, Params.Map.SNAP_SIGMA_M)
        if (r.accepted) {
            mapUpdates++
            mapStatus = MapStatus.SNAPPED
        } else {
            mapRejected++
            mapStatus = MapStatus.REJECTED
        }
    }

    private fun publishMock() {
        val o = origin ?: return
        val geo = o.toGeo(ekf.position)
        val v = ekf.velocity
        val speed = hypot(v.x, v.y)
        val bearing = (atan2(v.y, v.x).toDeg() + 360.0) % 360.0
        val sigmaV = sqrt(ekf.sigma(InsEkf.IV) * ekf.sigma(InsEkf.IV) + ekf.sigma(InsEkf.IV + 1) * ekf.sigma(InsEkf.IV + 1))
        mock.publish(
            PublishedLocation(
                latitudeDeg = geo.latitudeDeg,
                longitudeDeg = geo.longitudeDeg,
                altitudeM = geo.altitudeM,
                horizontalAccuracyM = horizontalSigma().coerceAtLeast(Params.Loc.MIN_HORIZONTAL_ACCURACY_M),
                verticalAccuracyM = ekf.sigma(InsEkf.IP + 2).coerceAtLeast(Params.Loc.MIN_VERTICAL_ACCURACY_M),
                speedMps = speed,
                speedAccuracyMps = sigmaV.coerceAtLeast(Params.Loc.MIN_SPEED_ACCURACY_MPS),
                bearingDeg = bearing,
                bearingAccuracyDeg = if (speed > Params.Loc.MIN_SPEED_FOR_BEARING_MPS) (sigmaV / speed).toDeg().coerceIn(1.0, 180.0) else 180.0,
            )
        )
    }

    private fun horizontalSigma() = sqrt(
        ekf.covariance[InsEkf.IP, InsEkf.IP] + ekf.covariance[InsEkf.IP + 1, InsEkf.IP + 1]
    )

    private fun recordHistory(now: Long) {
        val o = origin
        val geo = o?.toGeo(ekf.position)
        val v = ekf.velocity
        val ba = ekf.accBias
        val bg = ekf.gyroBias
        fun add(key: HistoryKey, value: Double) = historyRings.getValue(key).add(value)
        fun sig(i: Int) = ekf.sigma(i)

        add(HistoryKey.TIME, elapsedS(now))
        add(HistoryKey.LAT, geo?.latitudeDeg ?: Double.NaN)
        add(HistoryKey.LON, geo?.longitudeDeg ?: Double.NaN)
        add(HistoryKey.ALT, geo?.altitudeM ?: Double.NaN)
        add(HistoryKey.SIGMA_LAT, if (o != null) sig(InsEkf.IP) / o.metersPerDegreeLat else Double.NaN)
        add(HistoryKey.SIGMA_LON, if (o != null) sig(InsEkf.IP + 1) / o.metersPerDegreeLon else Double.NaN)
        add(HistoryKey.SIGMA_ALT, sig(InsEkf.IP + 2))
        add(HistoryKey.VN, v.x); add(HistoryKey.VE, v.y); add(HistoryKey.VD, v.z)
        add(HistoryKey.SIGMA_VN, sig(InsEkf.IV)); add(HistoryKey.SIGMA_VE, sig(InsEkf.IV + 1)); add(HistoryKey.SIGMA_VD, sig(InsEkf.IV + 2))
        val e = ekf.euler
        val se = ekf.eulerSigmas()
        add(HistoryKey.ROLL, e.x.toDeg()); add(HistoryKey.PITCH, e.y.toDeg()); add(HistoryKey.YAW, azimuthDeg(e.z))
        add(HistoryKey.SIGMA_ROLL, se.x.toDeg()); add(HistoryKey.SIGMA_PITCH, se.y.toDeg()); add(HistoryKey.SIGMA_YAW, se.z.toDeg())
        add(HistoryKey.MAG_YAW, if (!magDisturbed && lastMagHeading.isFinite()) azimuthDeg(lastMagHeading) else Double.NaN)
        add(HistoryKey.BAX, ba.x); add(HistoryKey.BAY, ba.y); add(HistoryKey.BAZ, ba.z)
        add(HistoryKey.SIGMA_BAX, sig(InsEkf.IBA)); add(HistoryKey.SIGMA_BAY, sig(InsEkf.IBA + 1)); add(HistoryKey.SIGMA_BAZ, sig(InsEkf.IBA + 2))
        add(HistoryKey.BGX, bg.x); add(HistoryKey.BGY, bg.y); add(HistoryKey.BGZ, bg.z)
        add(HistoryKey.SIGMA_BGX, sig(InsEkf.IBG)); add(HistoryKey.SIGMA_BGY, sig(InsEkf.IBG + 1)); add(HistoryKey.SIGMA_BGZ, sig(InsEkf.IBG + 2))
    }

    /** Moyenne glissante par paquets de [Params.Engine.IMU_HISTORY_DECIMATION] échantillons (100 Hz → 25 Hz). */
    private fun recordImu(s: ImuSample) {
        imuAccSum += s.acc
        imuGyrSum += s.gyr
        if (++imuSumCount < Params.Engine.IMU_HISTORY_DECIMATION) return
        val a = imuAccSum / imuSumCount.toDouble()
        // Au repos la force spécifique pointe vers le haut : la verticale « bas » est son opposé
        if (a.norm() > 1.0) measuredDown = (-a).normalized()
        val w = imuGyrSum / imuSumCount.toDouble()
        imuAccSum = Vec3.ZERO
        imuGyrSum = Vec3.ZERO
        imuSumCount = 0
        fun add(k: ImuHistoryKey, v: Double) = imuRings.getValue(k).add(v)
        add(ImuHistoryKey.TIME, elapsedS(s.timestampNs))
        add(ImuHistoryKey.FX, a.x); add(ImuHistoryKey.FY, a.y); add(ImuHistoryKey.FZ, a.z)
        add(ImuHistoryKey.WX, w.x); add(ImuHistoryKey.WY, w.y); add(ImuHistoryKey.WZ, w.z)
    }

    /** Angle (rad) → azimut en degrés dans [0, 360[. */
    private fun azimuthDeg(rad: Double) = (rad.toDeg() % 360.0 + 360.0) % 360.0

    private fun recordGps(fix: GpsFix) {
        gpsRings.getValue(GpsHistoryKey.TIME).add(elapsedS(fix.timestampNs))
        gpsRings.getValue(GpsHistoryKey.LAT).add(fix.latitudeDeg)
        gpsRings.getValue(GpsHistoryKey.LON).add(fix.longitudeDeg)
        gpsRings.getValue(GpsHistoryKey.ALT).add(fix.altitudeM)
    }

    private fun elapsedS(ns: Long) = (ns - startNs) * 1e-9

    private fun publishHistory() {
        _history.value = NavHistory(
            historyRings.mapValues { it.value.toArray() },
            gpsRings.mapValues { it.value.toArray() },
            imuRings.mapValues { it.value.toArray() },
        )
    }

    private fun publishCalibrationView() {
        val collected = calibrationSamples
        val center = calibrationPreview?.hardIron ?: magCalibration?.hardIron ?: mean(collected)
        _calibrationView.value = CalibrationView(
            collected = flatten(collected),
            trail = flatten(magTrail),
            preview = calibrationPreview,
            active = magCalibration,
            coverage = SphereCoverage.compute(collected, center),
        )
    }

    private fun flatten(v: Collection<Vec3>): DoubleArray {
        val out = DoubleArray(v.size * 3)
        var i = 0
        for (p in v) {
            out[i++] = p.x; out[i++] = p.y; out[i++] = p.z
        }
        return out
    }

    private fun mean(v: List<Vec3>): Vec3 {
        if (v.isEmpty()) return Vec3.ZERO
        var m = Vec3.ZERO
        v.forEach { m += it }
        return m / v.size.toDouble()
    }

    private fun publishSnapshot() {
        _snapshot.value = buildSnapshot()
    }

    private fun buildSnapshot(): NavSnapshot {
        val o = origin
        val g = gps
        val cal = magCalibration
        val calInfo = CalibrationInfo(
            collecting = calibrating,
            samples = calibrationSamples.size,
            hasUserCalibration = cal != null,
            hardIron = cal?.hardIron,
            softIronDiag = cal?.let { Vec3(it.softIron[0, 0], it.softIron[1, 1], it.softIron[2, 2]) },
            residualPct = cal?.fitResidual?.times(100) ?: Double.NaN,
            fieldRadiusUt = cal?.fieldRadius ?: Double.NaN,
            lastResult = calibrationResult,
        )
        val magStatus = when {
            mag?.available != true -> MagStatus.UNAVAILABLE
            magDisturbed -> MagStatus.DISTURBED
            cal != null -> MagStatus.USER_CALIBRATION
            else -> MagStatus.SYSTEM_CALIBRATION
        }
        val base = NavSnapshot(
            phase = when {
                thread == null -> FilterPhase.STOPPED
                ekf.initialized -> FilterPhase.NAVIGATING
                else -> FilterPhase.ALIGNING
            },
            imuRateHz = imu?.rateHz ?: 0.0,
            magRateHz = mag?.rateHz ?: 0.0,
            originSet = o != null,
            gpsStatus = g?.status ?: GpsStatus.SEARCHING,
            gpsAccuracyM = g?.lastAccuracyM ?: Double.NaN,
            gpsSatellites = g?.satellitesUsed ?: 0,
            gpsUpdates = gpsUpdates,
            gpsRejected = gpsRejected,
            magStatus = magStatus,
            magHeadingDeg = wrapPi(lastMagHeading).toDeg(),
            magFieldNormUt = lastMagNorm,
            magExpectedNormUt = geomag?.fieldStrength?.div(1000.0) ?: Double.NaN,
            magUpdates = magUpdates,
            magRejected = magRejected,
            stationary = zupt.stationary,
            zuptCount = zuptCount,
            stepCount = stepDetector.stepCount,
            lastStepLengthM = lastStepLength,
            pdrSpeedMps = pdrSpeed,
            mockStatus = mock.status,
            mockError = mock.lastError,
            mockPublished = mock.publishedCount,
            mockPlayServicesActive = mock.playServicesMockActive,
            mapStatus = if (_settings.value.mapEnabled) mapStatus else MapStatus.OFF,
            mapName = _networkMap.value?.name,
            mapError = mapLoadError,
            mapUpdates = mapUpdates,
            mapRejected = mapRejected,
            mapDistanceM = lastMapDistance,
            measuredDownBody = measuredDown,
            calibration = calInfo,
        )
        if (!ekf.initialized) return base
        val geo = o?.toGeo(ekf.position)
        val e = ekf.euler
        val sigmaEuler = ekf.eulerSigmas()
        return base.copy(
            latitudeDeg = geo?.latitudeDeg ?: Double.NaN,
            longitudeDeg = geo?.longitudeDeg ?: Double.NaN,
            altitudeM = geo?.altitudeM ?: Double.NaN,
            positionNed = ekf.position,
            velocityNed = ekf.velocity,
            eulerDeg = Vec3(e.x.toDeg(), e.y.toDeg(), e.z.toDeg()),
            sigmaHorizontalM = horizontalSigma(),
            sigmaVerticalM = ekf.sigma(InsEkf.IP + 2),
            sigmaYawDeg = sigmaEuler.z.toDeg(),
            sigmaRollDeg = sigmaEuler.x.toDeg(),
            sigmaPitchDeg = sigmaEuler.y.toDeg(),
            accBias = ekf.accBias,
            gyroBias = ekf.gyroBias,
        )
    }

}
