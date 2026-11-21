package fr.inertialnav.iau.ihm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.inertialnav.iau.engine.FilterPhase
import fr.inertialnav.iau.engine.MagStatus
import fr.inertialnav.iau.ihm.charts.Compass

/** Écran Boussole : Nord (KAL et magnétomètre) et verticale, avec les valeurs d'attitude. */
@Composable
fun CompassScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val navigating = snap.phase == FilterPhase.NAVIGATING

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Boussole et verticale") {
            Compass(
                yawDeg = if (navigating) snap.eulerDeg.z else Double.NaN,
                sigmaYawDeg = if (navigating) snap.sigmaYawDeg else Double.NaN,
                magYawDeg = snap.magHeadingDeg,
                magReliable = snap.magStatus != MagStatus.DISTURBED && snap.magStatus != MagStatus.UNAVAILABLE,
                rollDeg = if (navigating) snap.eulerDeg.x else Double.NaN,
                pitchDeg = if (navigating) snap.eulerDeg.y else Double.NaN,
                sigmaRollDeg = if (navigating) snap.sigmaRollDeg else Double.NaN,
                sigmaPitchDeg = if (navigating) snap.sigmaPitchDeg else Double.NaN,
                measuredDown = snap.measuredDownBody,
                modifier = Modifier.padding(8.dp),
            )
        }

        SectionCard("Attitude estimée (±3σ)") {
            if (!navigating) ValueRow("État", snap.phase.label)
            ValueRow("Roulis", "${snap.eulerDeg.x.fmtIf(navigating)} ± ${(3 * snap.sigmaRollDeg).fmt(1)} °")
            ValueRow("Tangage", "${snap.eulerDeg.y.fmtIf(navigating)} ± ${(3 * snap.sigmaPitchDeg).fmt(1)} °")
            ValueRow("Lacet (azimut)", "${azimuth(snap.eulerDeg.z).fmtIf(navigating)} ± ${(3 * snap.sigmaYawDeg).fmt(1)} °")
            ValueRow("Cap magnétomètre", azimuth(snap.magHeadingDeg).fmt(1, "°"))
            ValueRow("Magnétomètre", snap.magStatus.label)
            ValueRow("Champ mesuré / attendu", "${snap.magFieldNormUt.fmt(1)} / ${snap.magExpectedNormUt.fmt(1)} µT")
        }
    }
}

private fun Double.fmtIf(ok: Boolean) = if (ok) fmt(1) else "—"

private fun azimuth(deg: Double) = if (deg.isFinite()) (deg % 360 + 360) % 360 else Double.NaN
