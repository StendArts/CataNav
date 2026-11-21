package fr.inertialnav.iau.ihm

import fr.inertialnav.iau.config.Params
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.inertialnav.iau.engine.GpsHistoryKey
import fr.inertialnav.iau.engine.HistoryKey
import fr.inertialnav.iau.engine.ImuHistoryKey
import fr.inertialnav.iau.ihm.charts.BandSeries
import fr.inertialnav.iau.ihm.charts.ChartColors
import fr.inertialnav.iau.ihm.charts.Panel
import fr.inertialnav.iau.ihm.charts.PointSeries
import fr.inertialnav.iau.ihm.charts.Subplot

/** Monitoring graphique des sorties du module LOC / KAL. */
@Composable
fun MonitorScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val h by vm.history.collectAsStateWithLifecycle()
    var windowIndex by rememberSaveable { mutableIntStateOf(Params.Ui.DEFAULT_TIME_WINDOW_INDEX) }
    val w = Params.Ui.TIME_WINDOWS_S[windowIndex.coerceIn(Params.Ui.TIME_WINDOWS_S.indices)]
    val t = h[HistoryKey.TIME]

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Fenêtre", style = MaterialTheme.typography.labelLarge)
            Params.Ui.TIME_WINDOWS_S.forEachIndexed { i, s ->
                FilterChip(selected = i == windowIndex, onClick = { windowIndex = i }, label = { Text(windowLabel(s)) })
            }
        }

        SectionCard("Position hybridée (sortie LOC)") {
            ValueRow("Latitude", snap.latitudeDeg.fmt(7, "°"))
            ValueRow("Longitude", snap.longitudeDeg.fmt(7, "°"))
            ValueRow("Altitude", snap.altitudeM.fmt(1, "m"))
            ValueRow("σ horizontal / vertical", "${snap.sigmaHorizontalM.fmt(1)} / ${snap.sigmaVerticalM.fmt(1)} m")
            ValueRow("Roulis / tangage", "${snap.eulerDeg.x.fmt(1)} / ${snap.eulerDeg.y.fmt(1)} °")
            ValueRow("GPS rejetés / MSU rejetés", "${snap.gpsRejected} / ${snap.magRejected}")
            if (!snap.originSet) ValueRow("Origine", "en attente d'un GPS valide")
        }

        val gpsT = h[GpsHistoryKey.TIME]
        SectionCard("Position — zones : ±3σ") {
            Subplot(
                listOf(
                    Panel("Latitude (°)", listOf(BandSeries("KAL", ChartColors.FILTER, t, h[HistoryKey.LAT], h[HistoryKey.SIGMA_LAT])),
                        listOf(PointSeries("GPS", ChartColors.GPS, gpsT, h[GpsHistoryKey.LAT])), relative = true, yDecimals = 6),
                    Panel("Longitude (°)", listOf(BandSeries("KAL", ChartColors.FILTER, t, h[HistoryKey.LON], h[HistoryKey.SIGMA_LON])),
                        listOf(PointSeries("GPS", ChartColors.GPS, gpsT, h[GpsHistoryKey.LON])), relative = true, yDecimals = 6),
                    Panel("Altitude (m)", listOf(BandSeries("KAL", ChartColors.FILTER, t, h[HistoryKey.ALT], h[HistoryKey.SIGMA_ALT])),
                        listOf(PointSeries("GPS", ChartColors.GPS, gpsT, h[GpsHistoryKey.ALT])), yDecimals = 1),
                ),
                windowS = w,
            )
        }

        SectionCard("Attitude (°) — zones : ±3σ") {
            Subplot(
                listOf(
                    Panel(
                        "Roulis (autour de X avant)",
                        listOf(BandSeries("Roulis", ChartColors.AXIS_X, t, h[HistoryKey.ROLL], h[HistoryKey.SIGMA_ROLL])),
                        yDecimals = 1,
                    ),
                    Panel(
                        "Tangage (autour de Y droite)",
                        listOf(BandSeries("Tangage", ChartColors.AXIS_Y, t, h[HistoryKey.PITCH], h[HistoryKey.SIGMA_PITCH])),
                        yDecimals = 1,
                    ),
                    Panel(
                        "Lacet / azimut 0–360 (autour de Z bas)",
                        listOf(BandSeries("KAL", ChartColors.AXIS_Z, t, h[HistoryKey.YAW], h[HistoryKey.SIGMA_YAW])),
                        listOf(PointSeries("MSU", ChartColors.MAG, t, h[HistoryKey.MAG_YAW])),
                        yDecimals = 1,
                    ),
                ),
                windowS = w,
            )
        }

        SectionCard("Vitesse (m/s) — zones : ±3σ") {
            Subplot(
                listOf(
                    Panel("Nord", listOf(BandSeries("V Nord", ChartColors.FILTER, t, h[HistoryKey.VN], h[HistoryKey.SIGMA_VN]))),
                    Panel("Est", listOf(BandSeries("V Est", ChartColors.FILTER, t, h[HistoryKey.VE], h[HistoryKey.SIGMA_VE]))),
                    Panel("Descente", listOf(BandSeries("V Descente", ChartColors.FILTER, t, h[HistoryKey.VD], h[HistoryKey.SIGMA_VD]))),
                ),
                windowS = w,
            )
        }

        SectionCard("Biais des accéléromètres (m/s²) — zones : ±3σ") {
            Subplot(
                listOf(
                    Panel("X avant", listOf(BandSeries("ba X", ChartColors.AXIS_X, t, h[HistoryKey.BAX], h[HistoryKey.SIGMA_BAX])), yDecimals = 3),
                    Panel("Y droite", listOf(BandSeries("ba Y", ChartColors.AXIS_Y, t, h[HistoryKey.BAY], h[HistoryKey.SIGMA_BAY])), yDecimals = 3),
                    Panel("Z bas", listOf(BandSeries("ba Z", ChartColors.AXIS_Z, t, h[HistoryKey.BAZ], h[HistoryKey.SIGMA_BAZ])), yDecimals = 3),
                ),
                windowS = w,
            )
        }

        SectionCard("Biais des gyromètres (mrad/s) — zones : ±3σ") {
            Subplot(
                listOf(
                    Panel("X avant", listOf(BandSeries("bg X", ChartColors.AXIS_X, t, h[HistoryKey.BGX], h[HistoryKey.SIGMA_BGX], scale = 1e3))),
                    Panel("Y droite", listOf(BandSeries("bg Y", ChartColors.AXIS_Y, t, h[HistoryKey.BGY], h[HistoryKey.SIGMA_BGY], scale = 1e3))),
                    Panel("Z bas", listOf(BandSeries("bg Z", ChartColors.AXIS_Z, t, h[HistoryKey.BGZ], h[HistoryKey.SIGMA_BGZ], scale = 1e3))),
                ),
                windowS = w,
            )
        }

        val ti = h[ImuHistoryKey.TIME]
        SectionCard("Forces spécifiques mesurées (m/s², repère FRD)") {
            Subplot(
                listOf(
                    Panel("X avant", listOf(BandSeries("fX", ChartColors.AXIS_X, ti, h[ImuHistoryKey.FX]))),
                    Panel("Y droite", listOf(BandSeries("fY", ChartColors.AXIS_Y, ti, h[ImuHistoryKey.FY]))),
                    Panel("Z bas", listOf(BandSeries("fZ", ChartColors.AXIS_Z, ti, h[ImuHistoryKey.FZ]))),
                ),
                windowS = w,
            )
        }

        SectionCard("Vitesses angulaires mesurées (°/s, repère FRD)") {
            Subplot(
                listOf(
                    Panel("X avant (roulis)", listOf(BandSeries("ωX", ChartColors.AXIS_X, ti, h[ImuHistoryKey.WX], scale = RAD_TO_DEG)), yDecimals = 1),
                    Panel("Y droite (tangage)", listOf(BandSeries("ωY", ChartColors.AXIS_Y, ti, h[ImuHistoryKey.WY], scale = RAD_TO_DEG)), yDecimals = 1),
                    Panel("Z bas (lacet)", listOf(BandSeries("ωZ", ChartColors.AXIS_Z, ti, h[ImuHistoryKey.WZ], scale = RAD_TO_DEG)), yDecimals = 1),
                ),
                windowS = w,
            )
        }
    }
}

/** Libellé d'une fenêtre de temps : « 30 s », « 2 min »… */
private fun windowLabel(s: Double) = if (s < 60) "%.0f s".format(s) else "%.0f min".format(s / 60)

private const val RAD_TO_DEG = 180.0 / Math.PI
