package fr.inertialnav.iau.gps

import fr.inertialnav.iau.config.Params
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor

data class GpsFix(
    /** Horodatage de la mesure (base elapsedRealtimeNanos). */
    val timestampNs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
)

enum class GpsStatus(val label: String) {
    NO_PERMISSION("Permission refusée"),
    DISABLED("GPS désactivé"),
    SEARCHING("Recherche…"),
    VALID("Valide"),
    INACCURATE("Imprécis"),
    STALE("Pas rafraîchi"),
    MOCK_REJECTED("Position fictive rejetée"),
}

/**
 * Module GPS : ne transmet que les positions disponibles, activées, valides et rafraîchies.
 * - disponible/activé : fournisseur GPS_PROVIDER actif et permission accordée
 * - valide : précision horizontale ≤ [maxAccuracyM], position non fictive
 * - rafraîchie : âge ≤ [maxAgeS] et strictement plus récente que la précédente
 *
 * Les positions fictives (isMock) sont systématiquement rejetées pour éviter que le
 * module LOC ne se recale sur sa propre sortie.
 */
class GpsSource(
    private val context: Context,
    private val handler: Handler,
    private val maxAccuracyM: Double = Params.Gps.MAX_ACCURACY_M,
    private val maxAgeS: Double = Params.Gps.MAX_AGE_S,
    private val onFix: (GpsFix) -> Unit,
) {
    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var lastFixNs = 0L
    private var started = false

    var status: GpsStatus = GpsStatus.SEARCHING
        private set
    var satellitesUsed: Int = 0
        private set
    var lastAccuracyM: Double = Double.NaN
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)
        override fun onProviderEnabled(provider: String) {
            status = GpsStatus.SEARCHING
        }

        override fun onProviderDisabled(provider: String) {
            status = GpsStatus.DISABLED
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var used = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) used++
            satellitesUsed = used
        }
    }

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        if (!hasPermission()) {
            status = GpsStatus.NO_PERMISSION
            return
        }
        status = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) GpsStatus.SEARCHING else GpsStatus.DISABLED
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, handler.looper)
        locationManager.registerGnssStatusCallback(Executor { handler.post(it) }, gnssCallback)
        started = true
    }

    fun stop() {
        if (!started) return
        locationManager.removeUpdates(listener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        started = false
    }

    /** Dernière position connue (pour fixer l'origine au démarrage), si suffisamment récente. */
    @SuppressLint("MissingPermission")
    fun lastKnownFix(maxAgeS: Double = Params.Gps.LAST_KNOWN_MAX_AGE_S): GpsFix? {
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val now = SystemClock.elapsedRealtimeNanos()
        return providers.asSequence()
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .filter { !it.isMock && (now - it.elapsedRealtimeNanos) * 1e-9 <= maxAgeS && it.hasAccuracy() }
            .minByOrNull { it.accuracy }
            ?.toFix()
    }

    /** À appeler périodiquement pour détecter l'absence de rafraîchissement. */
    fun checkStaleness(nowNs: Long) {
        if (status == GpsStatus.VALID && (nowNs - lastFixNs) * 1e-9 > maxAgeS) status = GpsStatus.STALE
    }

    private fun handleLocation(location: Location) {
        if (location.isMock) {
            status = GpsStatus.MOCK_REJECTED
            return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val t = location.elapsedRealtimeNanos
        if (t <= lastFixNs) return
        if ((now - t) * 1e-9 > maxAgeS) {
            status = GpsStatus.STALE
            return
        }
        lastAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN
        if (!location.hasAccuracy() || location.accuracy > maxAccuracyM) {
            status = GpsStatus.INACCURATE
            return
        }
        lastFixNs = t
        status = GpsStatus.VALID
        onFix(location.toFix())
    }

    private fun Location.toFix(): GpsFix {
        val h = accuracy.toDouble()
        return GpsFix(
            timestampNs = elapsedRealtimeNanos,
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            altitudeM = if (hasAltitude()) altitude else 0.0,
            horizontalAccuracyM = h,
            verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else 2 * h,
            speedMps = if (hasSpeed()) speed.toDouble() else null,
            bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        )
    }
}
