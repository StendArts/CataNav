package fr.inertialnav.iau.ihm.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.inertialnav.iau.core.math.Vec3
import fr.inertialnav.iau.engine.CalibrationView
import fr.inertialnav.iau.msu.MagCalibration
import fr.inertialnav.iau.ui.theme.LogoGold
import fr.inertialnav.iau.ui.theme.LogoStone
import kotlin.math.abs
import kotlin.math.max

private val RAW_COLOR = LogoStone
private val CORRECTED_COLOR = Color(0xFF43A047)
private val FIT_COLOR = LogoGold
private val CURRENT_COLOR = Color(0xFFE53935)

/**
 * Représentation en direct de la calibration magnétique : projections du nuage de mesures
 * sur les plans XY, XZ et YZ (repère FRD, µT).
 * - brutes : mesures non corrigées (nuage décentré et ellipsoïdal si fer dur / fer doux) ;
 * - ajustement : ellipse projetée de l'ellipsoïde estimé ;
 * - corrigées : mesures après compensation, attendues sur la sphère cible centrée en 0.
 */
@Composable
fun MagCalibrationPlot(view: CalibrationView, modifier: Modifier = Modifier) {
    val cal = view.preview ?: view.active
    val raw = toVecs(if (view.collected.isNotEmpty()) view.collected else view.trail)
    val trail = toVecs(view.trail)
    val corrected = cal?.let { c -> raw.map { c.apply(it) } } ?: emptyList()
    val current = trail.lastOrNull()
    val currentCorrected = current?.let { cal?.apply(it) }

    // Échelle commune aux trois vues
    var extent = 20.0
    for (v in raw + corrected) extent = max(extent, max(abs(v.x), max(abs(v.y), abs(v.z))))
    cal?.let { extent = max(extent, it.fieldRadius) }
    extent *= 1.1

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (view.collected.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Couverture ${"%.0f".format(view.coverage * 100)} %  ", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { view.coverage.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (plane in Plane.entries) {
                PlaneView(
                    plane, raw, corrected, cal, current, currentCorrected, extent,
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot("brutes", RAW_COLOR)
            LegendDot("corrigées", CORRECTED_COLOR)
            LegendDot("ellipse ajustée", FIT_COLOR)
            LegendDot("mesure courante", CURRENT_COLOR)
        }
        Text(
            "Cercle en tirets verts : sphère cible. Une bonne calibration ramène les points " +
                "corrigés sur ce cercle dans les trois vues.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Text("● $label", color = color, style = MaterialTheme.typography.labelSmall)
}

private enum class Plane(val label: String, val a: Int, val b: Int) {
    XY("X / Y", 0, 1),
    XZ("X / Z", 0, 2),
    YZ("Y / Z", 1, 2),
}

@Composable
private fun PlaneView(
    plane: Plane,
    raw: List<Vec3>,
    corrected: List<Vec3>,
    cal: MagCalibration?,
    current: Vec3?,
    currentCorrected: Vec3?,
    extent: Double,
    modifier: Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val half = size.minDimension / 2
        val k = (half / extent).toFloat()
        val o = center
        fun px(v: Vec3) = Offset(o.x + v[plane.a].toFloat() * k, o.y - v[plane.b].toFloat() * k)

        drawRect(axisColor.copy(alpha = 0.05f))
        drawLine(axisColor.copy(alpha = 0.3f), Offset(0f, o.y), Offset(size.width, o.y))
        drawLine(axisColor.copy(alpha = 0.3f), Offset(o.x, 0f), Offset(o.x, size.height))

        drawPointCloud(raw.map(::px), RAW_COLOR.copy(alpha = 0.7f))
        if (cal != null) {
            // Ellipse ajustée : centre = fer dur, demi-axes r_i = R / S_ii
            val c = cal.hardIron
            val ra = (cal.fieldRadius / cal.softIron[plane.a, plane.a]).toFloat() * k
            val rb = (cal.fieldRadius / cal.softIron[plane.b, plane.b]).toFloat() * k
            val cc = px(c)
            drawOval(FIT_COLOR, Offset(cc.x - ra, cc.y - rb), Size(2 * ra, 2 * rb), style = Stroke(width = 3f))
            // Sphère cible après correction
            val r = cal.fieldRadius.toFloat() * k
            drawCircle(
                CORRECTED_COLOR, r, o,
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
            drawPointCloud(corrected.map(::px), CORRECTED_COLOR.copy(alpha = 0.7f))
        }
        current?.let { drawCircle(CURRENT_COLOR, 7f, px(it)) }
        currentCorrected?.let { drawCircle(CURRENT_COLOR, 7f, px(it), style = Stroke(width = 3f)) }

        val label = measurer.measure(plane.label, TextStyle(color = axisColor, fontSize = 11.sp))
        drawText(label, topLeft = Offset(6f, 4f))
    }
}

private fun DrawScope.drawPointCloud(points: List<Offset>, color: Color) {
    if (points.isEmpty()) return
    drawPoints(points, PointMode.Points, color, strokeWidth = 5f, cap = StrokeCap.Round)
}

private fun toVecs(flat: DoubleArray): List<Vec3> = List(flat.size / 3) { Vec3(flat[3 * it], flat[3 * it + 1], flat[3 * it + 2]) }
