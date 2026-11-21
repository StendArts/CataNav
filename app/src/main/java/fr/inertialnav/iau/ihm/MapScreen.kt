package fr.inertialnav.iau.ihm

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.inertialnav.iau.engine.MapStatus
import fr.inertialnav.iau.ihm.charts.BaseMap
import fr.inertialnav.iau.ihm.charts.TrajectoryMap

/** Écran carte plein écran : fond OpenStreetMap ou plan des catacombes. */
@Composable
fun MapScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val h by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val networkMap by vm.networkMap.collectAsStateWithLifecycle()

    var baseMap by rememberSaveable { mutableStateOf(BaseMap.CLASSIC) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var showNetwork by rememberSaveable { mutableStateOf(true) }

    Box(modifier.fillMaxSize()) {
        TrajectoryMap(
            history = h,
            snapshot = snap,
            baseMap = baseMap,
            follow = follow,
            onUserPan = { follow = false },
            network = if (settings.mapEnabled && showNetwork) networkMap else null,
            modifier = Modifier.fillMaxSize(),
        )

        // Commandes en surimpression (haut)
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp),
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SingleChoiceSegmentedButtonRow {
                    BaseMap.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = baseMap == mode,
                            onClick = { baseMap = mode },
                            shape = SegmentedButtonDefaults.itemShape(i, BaseMap.entries.size),
                        ) { Text(mode.label) }
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Suivre") })
                    if (settings.mapEnabled && networkMap != null) {
                        FilterChip(
                            selected = showNetwork,
                            onClick = { showNetwork = !showNetwork },
                            label = { Text("Galeries d'hybridation") },
                        )
                    }
                }
            }
        }

        // Bandeau d'état (bas)
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    "${snap.latitudeDeg.fmt(7, "°")}  ${snap.longitudeDeg.fmt(7, "°")}  ±${(3 * snap.sigmaHorizontalM).fmt(1, "m")} (3σ)",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        append("● KAL  ● GPS  ○ 3σ")
                        if (settings.mapEnabled) {
                            append("   Carte : ").append(snap.mapStatus.label)
                            if (snap.mapStatus == MapStatus.SNAPPED || snap.mapStatus == MapStatus.REJECTED) {
                                append(" (").append(snap.mapDistanceM.fmt(1, "m")).append(")")
                            }
                        }
                        if (!snap.originSet) append("   Position absolue : en attente d'un GPS")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
