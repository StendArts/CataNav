package fr.inertialnav.iau.loc

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationServices

enum class MockStatus(val label: String) {
    OFF("Inactif"),
    ACTIVE("Publication en cours"),
    NOT_ALLOWED("Non autorisé : choisir l'app comme « application de position fictive » (options développeur)"),
    ERROR("Erreur"),
}

data class PublishedLocation(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    val speedMps: Double,
    val speedAccuracyMps: Double,
    val bearingDeg: Double,
    val bearingAccuracyDeg: Double,
)

/**
 * Module LOC : publie la position hybridée comme position système. L'app doit être
 * sélectionnée comme « application de position fictive » dans les options pour les développeurs.
 *
 * Deux canaux sont alimentés :
 * 1. Le Fused Location Provider de Google Play Services en mode fictif : c'est la source
 *    utilisée par Google Maps et la grande majorité des applications.
 * 2. Les fournisseurs de test de LocationManager (FUSED_PROVIDER, NETWORK_PROVIDER), pour les
 *    applications qui interrogent directement le framework Android.
 *
 * GPS_PROVIDER n'est volontairement jamais remplacé : le module GPS y lit les vraies mesures
 * (le mode fictif de Play Services n'affecte pas les accès directs à LocationManager).
 */
class MockLocationPublisher(context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    // Modifiés depuis les callbacks Play Services (thread principal) : @Volatile
    @Volatile
    var status: MockStatus = MockStatus.OFF
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Mode fictif Play Services effectivement actif (indisponible sans Google Play). */
    @Volatile
    var playServicesMockActive = false
        private set

    @Volatile
    private var playServicesWanted = false
    var activeProviders: Set<String> = emptySet()
        private set
    var publishedCount = 0L
        private set

    fun enable(providers: Set<String>) {
        disable()
        val props = ProviderProperties.Builder()
            .setHasAltitudeSupport(true)
            .setHasSpeedSupport(true)
            .setHasBearingSupport(true)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()
        val added = mutableSetOf<String>()
        try {
            for (p in providers) {
                runCatching { locationManager.removeTestProvider(p) }
                locationManager.addTestProvider(p, props)
                locationManager.setTestProviderEnabled(p, true)
                added += p
            }
            activeProviders = added
            status = MockStatus.ACTIVE
            lastError = null
            enablePlayServicesMock()
        } catch (e: SecurityException) {
            activeProviders = added
            disable()
            status = MockStatus.NOT_ALLOWED
            lastError = e.message
        } catch (e: IllegalArgumentException) {
            activeProviders = added
            disable()
            status = MockStatus.ERROR
            lastError = e.message
        }
    }

    fun publish(loc: PublishedLocation) {
        if (status != MockStatus.ACTIVE) return
        val nowMs = System.currentTimeMillis()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        for (p in activeProviders) {
            val l = Location(p).apply {
                latitude = loc.latitudeDeg
                longitude = loc.longitudeDeg
                altitude = loc.altitudeM
                accuracy = loc.horizontalAccuracyM.toFloat()
                verticalAccuracyMeters = loc.verticalAccuracyM.toFloat()
                speed = loc.speedMps.toFloat()
                speedAccuracyMetersPerSecond = loc.speedAccuracyMps.toFloat()
                bearing = loc.bearingDeg.toFloat()
                bearingAccuracyDegrees = loc.bearingAccuracyDeg.toFloat()
                time = nowMs
                elapsedRealtimeNanos = nowNs
            }
            try {
                locationManager.setTestProviderLocation(p, l)
            } catch (e: RuntimeException) {
                // SecurityException si l'autorisation a été retirée, IllegalArgumentException sinon
                Log.w(TAG, "Publication impossible sur $p", e)
                status = if (e is SecurityException) MockStatus.NOT_ALLOWED else MockStatus.ERROR
                lastError = e.message
                return
            }
        }
        if (playServicesMockActive) {
            val fl = Location("fused").apply {
                latitude = loc.latitudeDeg
                longitude = loc.longitudeDeg
                altitude = loc.altitudeM
                accuracy = loc.horizontalAccuracyM.toFloat()
                verticalAccuracyMeters = loc.verticalAccuracyM.toFloat()
                speed = loc.speedMps.toFloat()
                speedAccuracyMetersPerSecond = loc.speedAccuracyMps.toFloat()
                bearing = loc.bearingDeg.toFloat()
                bearingAccuracyDegrees = loc.bearingAccuracyDeg.toFloat()
                time = nowMs
                elapsedRealtimeNanos = nowNs
            }
            fusedClient.setMockLocation(fl).addOnFailureListener { e ->
                Log.w(TAG, "Play Services : setMockLocation refusé", e)
                playServicesMockActive = false
            }
        }
        publishedCount++
    }

    private fun enablePlayServicesMock() {
        playServicesWanted = true
        try {
            fusedClient.setMockMode(true)
                .addOnSuccessListener { playServicesMockActive = playServicesWanted }
                .addOnFailureListener { e ->
                    // Appareil sans Google Play, ou app non choisie comme app de position fictive
                    Log.w(TAG, "Play Services : mode fictif indisponible", e)
                    playServicesMockActive = false
                }
        } catch (e: SecurityException) {
            Log.w(TAG, "Play Services : mode fictif refusé", e)
            playServicesMockActive = false
        }
    }

    fun disable() {
        if (playServicesWanted) {
            playServicesWanted = false
            playServicesMockActive = false
            runCatching { fusedClient.setMockMode(false) }
        }
        for (p in activeProviders) {
            runCatching { locationManager.setTestProviderEnabled(p, false) }
            runCatching { locationManager.removeTestProvider(p) }
        }
        activeProviders = emptySet()
        status = MockStatus.OFF
    }

    private companion object {
        const val TAG = "LOC"
    }
}
