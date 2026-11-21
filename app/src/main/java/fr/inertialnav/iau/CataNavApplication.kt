package fr.inertialnav.iau

import android.app.Application
import fr.inertialnav.iau.engine.NavigationEngine
import org.osmdroid.config.Configuration
import java.io.File

/** Point d'accès unique au moteur de navigation, partagé entre le service et l'IHM. */
class CataNavApplication : Application() {
    val engine: NavigationEngine by lazy { NavigationEngine(this) }

    override fun onCreate() {
        super.onCreate()
        // Carte OpenStreetMap : user-agent identifiant l'app (politique d'usage des tuiles OSM)
        // et cache de tuiles dans le stockage privé de l'application.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
    }
}
