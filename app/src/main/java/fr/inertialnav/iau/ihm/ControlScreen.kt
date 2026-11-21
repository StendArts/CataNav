package fr.inertialnav.iau.ihm

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.inertialnav.iau.engine.FilterPhase
import fr.inertialnav.iau.loc.MockStatus

/** Écran de commande : marche/arrêt, activation des hybridations et aides piéton, LOC. */
@Composable
fun ControlScreen(vm: MainViewModel, onStartRequested: () -> Unit, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val running = snap.phase != FilterPhase.STOPPED

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBanner()

        SectionCard("Navigation") {
            ValueRow("État", snap.phase.label)
            ValueRow("IMU", "${snap.imuRateHz.fmt(0)} Hz")
            ValueRow("Magnétomètre", "${snap.magRateHz.fmt(0)} Hz")
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (running) {
                    Button(onClick = vm::stopNavigation) { Text("Arrêter") }
                    OutlinedButton(onClick = vm::resetFilter) { Text("Réinitialiser le filtre") }
                } else {
                    Button(onClick = onStartRequested) { Text("Démarrer") }
                }
            }
        }

        SectionCard("Hybridations") {
            SwitchRow(
                "Magnétomètre (azimut)",
                "${snap.magStatus.label} · ${snap.magUpdates} recalages",
                settings.magEnabled, vm::setMag,
            )
            SwitchRow(
                "GPS (lat, lon, alt)",
                "${snap.gpsStatus.label} · ${snap.gpsSatellites} sat. · ${snap.gpsUpdates} recalages",
                settings.gpsEnabled, vm::setGps,
            )
            SwitchRow(
                "Carte (catacombes de Paris)",
                if (settings.mapEnabled) {
                    "${snap.mapStatus.label} · ${snap.mapUpdates} recalages" +
                        if (snap.mapDistanceM.isFinite() && snap.mapDistanceM > 0) " · écart ${snap.mapDistanceM.fmt(1, "m")}" else ""
                } else {
                    "Recale la position sur la galerie la plus proche"
                },
                settings.mapEnabled, vm::setMap,
            )
            snap.mapError?.let {
                Text(
                    "Carte illisible : $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        SectionCard("Aides piéton") {
            SwitchRow(
                "Détection de pas (PDR)",
                "${snap.stepCount} pas · longueur ${snap.lastStepLengthM.fmt(2, "m")}",
                settings.pdrEnabled, vm::setPdr,
            )
            SwitchRow(
                "ZUPT (vitesse nulle à l'arrêt)",
                (if (snap.stationary) "Immobile" else "En mouvement") + " · ${snap.zuptCount} ZUPT",
                settings.zuptEnabled, vm::setZupt,
            )
        }

        SectionCard("Fournisseur de localisation (LOC)") {
            SwitchRow(
                "Publier la position hybridée",
                "Remplace la position vue par les autres applications (Google Maps…)",
                settings.mockEnabled, vm::setMock,
            )
            ValueRow("État", snap.mockStatus.label.substringBefore(" :"))
            ValueRow("Positions publiées", "${snap.mockPublished}")
            ValueRow(
                "Google Play Services",
                when {
                    !settings.mockEnabled -> "—"
                    snap.mockPlayServicesActive -> "mode fictif actif"
                    else -> "indisponible"
                },
            )
            if (snap.mockStatus == MockStatus.NOT_ALLOWED || snap.mockStatus == MockStatus.ERROR) {
                snap.mockError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Ouvrir les options pour les développeurs") }
            }
        }
    }
}
