package fr.inertialnav.iau.ihm

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.inertialnav.iau.engine.FilterPhase
import fr.inertialnav.iau.ihm.charts.MagCalibrationPlot
import fr.inertialnav.iau.msu.EllipsoidFit

/** Calibration fer dur / fer doux du magnétomètre (module MSU). */
@Composable
fun CalibrationScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val cal = snap.calibration
    val view by vm.calibrationView.collectAsStateWithLifecycle()
    val running = snap.phase != FilterPhase.STOPPED

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Calibration magnétomètre") {
            Text(
                "Éloignez-vous des masses métalliques, démarrez la collecte puis faites tourner " +
                    "lentement le téléphone dans toutes les orientations (mouvement en « 8 » sur les trois axes) " +
                    "pendant 30 à 60 s.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (!running) {
                Text(
                    "Démarrez la navigation (onglet Commande) pour accéder au magnétomètre.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ValueRow("Mesures collectées", "${cal.samples} (min ${EllipsoidFit.MIN_SAMPLES})")
            ValueRow("Champ mesuré", snap.magFieldNormUt.fmt(1, "µT"))
            ValueRow("Champ attendu (WMM)", snap.magExpectedNormUt.fmt(1, "µT"))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cal.collecting) {
                    Button(onClick = vm::finishCalibration) { Text("Terminer") }
                    OutlinedButton(onClick = vm::cancelCalibration) { Text("Annuler") }
                } else {
                    Button(onClick = vm::startCalibration, enabled = running) { Text("Démarrer la collecte") }
                }
            }
            cal.lastResult?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard("Visualisation en direct") {
            if (!running) {
                Text(
                    "Démarrez la navigation pour afficher les mesures.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                when {
                    cal.collecting -> "Collecte en cours : ajustement provisoire mis à jour chaque seconde"
                    view.collected.isNotEmpty() -> "Dernière collecte et son ajustement"
                    else -> "Dernières mesures, corrigées avec la calibration active"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            MagCalibrationPlot(view, Modifier.padding(vertical = 8.dp))
        }

        SectionCard("Calibration active") {
            if (cal.hasUserCalibration) {
                val b = cal.hardIron!!
                val s = cal.softIronDiag!!
                ValueRow("Fer dur (µT)", "${b.x.fmt(1)} ${b.y.fmt(1)} ${b.z.fmt(1)}")
                ValueRow("Fer doux (diag.)", "${s.x.fmt(3)} ${s.y.fmt(3)} ${s.z.fmt(3)}")
                ValueRow("Résidu", cal.residualPct.fmt(1, "%"))
                ValueRow("Rayon", cal.fieldRadiusUt.fmt(1, "µT"))
                OutlinedButton(
                    onClick = vm::clearCalibration,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("Effacer (revenir à la calibration système)") }
            } else {
                Text(
                    "Aucune calibration utilisateur : la compensation de fer dur estimée par Android est utilisée.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}
