package fr.inertialnav.iau.ihm.charts

import fr.inertialnav.iau.config.Params
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import fr.inertialnav.iau.core.math.Vec3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Boussole liée au téléphone : le haut du cadran correspond au haut de l'écran (axe « avant »).
 * Les flèches indiquent la direction du Nord vrai :
 * - selon l'azimut estimé par KAL, avec le secteur d'incertitude ±3σ ;
 * - selon le magnétomètre (MSU), si disponible.
 *
 * Un azimut ψ (sens horaire depuis le Nord) place le Nord à −ψ par rapport à l'avant du téléphone.
 *
 * La verticale (direction « bas », fil à plomb) est projetée dans le plan de l'écran :
 * au centre quand le téléphone est à plat, sur le bord quand il est vertical. Une bille posée
 * sur l'écran roulerait vers ce point. Cercles en tirets : inclinaisons de référence (Params.Ui.COMPASS_TILT_RINGS_DEG).
 * - disque : verticale estimée par KAL, avec son ellipse d'incertitude ±3σ ;
 * - anneau : verticale mesurée par les accéléromètres (moyenne courte, sensible aux mouvements).
 */
@Composable
fun Compass(
    yawDeg: Double,
    sigmaYawDeg: Double,
    magYawDeg: Double,
    magReliable: Boolean,
    modifier: Modifier = Modifier,
    rollDeg: Double = Double.NaN,
    pitchDeg: Double = Double.NaN,
    sigmaRollDeg: Double = Double.NaN,
    sigmaPitchDeg: Double = Double.NaN,
    measuredDown: Vec3? = null,
    size: Dp = Params.Ui.COMPASS_SIZE_DP.dp,
) {
    val kalColor = Color(ChartColors.FILTER)
    val magColor = if (magReliable) Color(ChartColors.MAG) else Color.Gray
    val vertColor = Color(ChartColors.ACC)
    val kalDown = if (rollDeg.isFinite() && pitchDeg.isFinite()) {
        val r = Math.toRadians(rollDeg)
        val p = Math.toRadians(pitchDeg)
        Vec3(-sin(p), sin(r) * cos(p), cos(r) * cos(p)) // C_bn · (0, 0, 1)
    } else {
        null
    }
    val dialColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size)) {
            val r = min(this.size.width, this.size.height) / 2 * 0.92f
            val c = center

            // Cadran
            drawCircle(dialColor.copy(alpha = 0.6f), radius = r, center = c, style = Stroke(width = 3f))
            for (deg in 0 until 360 step 15) {
                val major = deg % 90 == 0
                rotate(deg.toFloat(), c) {
                    drawLine(
                        dialColor.copy(alpha = if (major) 0.9f else 0.4f),
                        Offset(c.x, c.y - r),
                        Offset(c.x, c.y - r + if (major) 18f else 9f),
                        strokeWidth = if (major) 4f else 2f,
                    )
                }
            }
            // Repère « avant du téléphone »
            val fwd = Path().apply {
                moveTo(c.x, c.y - r - 2f)
                lineTo(c.x - 12f, c.y - r + 22f)
                lineTo(c.x + 12f, c.y - r + 22f)
                close()
            }
            drawPath(fwd, dialColor.copy(alpha = 0.35f))

            if (yawDeg.isFinite()) {
                val north = (-yawDeg).toFloat()
                if (sigmaYawDeg.isFinite()) {
                    val sweep = (6 * sigmaYawDeg).toFloat().coerceAtMost(360f)
                    drawArc(
                        kalColor.copy(alpha = 0.18f),
                        startAngle = -90f + north - sweep / 2,
                        sweepAngle = sweep,
                        useCenter = true,
                        topLeft = Offset(c.x - r * 0.85f, c.y - r * 0.85f),
                        size = Size(r * 1.7f, r * 1.7f),
                    )
                }
                drawNorthArrow(north, r * 0.82f, kalColor, width = 26f)
                val label = measurer.measure("N", TextStyle(color = kalColor, fontSize = 14.sp))
                rotate(north, c) {
                    drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - r * 0.82f - label.size.height - 2f))
                }
            }
            if (magYawDeg.isFinite()) {
                drawNorthArrow((-magYawDeg).toFloat(), r * 0.62f, magColor, width = 16f)
            }

            // --- Verticale ---
            val rv = r * 0.9f
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            for (tilt in Params.Ui.COMPASS_TILT_RINGS_DEG) {
                drawCircle(
                    vertColor.copy(alpha = 0.35f), radius = rv * sin(Math.toRadians(tilt)).toFloat(), center = c,
                    style = Stroke(width = 1.5f, pathEffect = dash),
                )
            }
            fun screen(d: Vec3) = Offset(c.x + d.y.toFloat() * rv, c.y - d.x.toFloat() * rv)
            measuredDown?.let { d ->
                drawCircle(vertColor.copy(alpha = 0.9f), radius = 13f, center = screen(d), style = Stroke(width = 3f))
            }
            kalDown?.let { d ->
                val pt = screen(d)
                drawLine(vertColor.copy(alpha = 0.6f), c, pt, strokeWidth = 2f)
                if (sigmaRollDeg.isFinite() && sigmaPitchDeg.isFinite()) {
                    // Sensibilités de la projection : d(droite)/d(roulis) et d(haut)/d(tangage)
                    val p = Math.toRadians(pitchDeg)
                    val ax = (abs(cos(Math.toRadians(rollDeg)) * cos(p)) * Math.toRadians(3 * sigmaRollDeg) * rv).toFloat()
                    val ay = (abs(cos(p)) * Math.toRadians(3 * sigmaPitchDeg) * rv).toFloat()
                    drawOval(
                        vertColor.copy(alpha = 0.25f),
                        topLeft = Offset(pt.x - ax, pt.y - ay),
                        size = Size(2 * ax, 2 * ay),
                    )
                }
                // Disque plein si l'écran regarde vers le haut, creux s'il est retourné
                drawCircle(vertColor, radius = 9f, center = pt, style = if (d.z >= 0) Fill else Stroke(width = 4f))
            }
            drawCircle(dialColor, radius = 6f, center = c)
        }

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "▲ Nord KAL : azimut ${fmt(yawDeg)}°  (±3σ = ${if (sigmaYawDeg.isFinite()) "%.1f".format(3 * sigmaYawDeg) else "—"}°)",
                color = kalColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (magYawDeg.isFinite()) {
                    "▲ Nord MSU : azimut ${fmt(magYawDeg)}°" + if (magReliable) "" else "  (perturbé)"
                } else {
                    "▲ Nord MSU : indisponible"
                },
                color = magColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (kalDown != null) {
                    "● Verticale KAL : inclinaison ${"%.1f".format(tiltDeg(kalDown))}° " +
                        "(roulis ${"%.1f".format(rollDeg)}° ±${fmt3(sigmaRollDeg)}, tangage ${"%.1f".format(pitchDeg)}° ±${fmt3(sigmaPitchDeg)})"
                } else {
                    "● Verticale KAL : indisponible"
                },
                color = vertColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                measuredDown?.let { "○ Verticale accéléromètres : inclinaison ${"%.1f".format(tiltDeg(it))}°" }
                    ?: "○ Verticale accéléromètres : indisponible",
                color = vertColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun DrawScope.drawNorthArrow(angleDeg: Float, length: Float, color: Color, width: Float) {
    val c = center
    rotate(angleDeg, c) {
        val head = Path().apply {
            moveTo(c.x, c.y - length)
            lineTo(c.x - width / 2, c.y - length + width * 1.4f)
            lineTo(c.x + width / 2, c.y - length + width * 1.4f)
            close()
        }
        drawLine(color, c, Offset(c.x, c.y - length + width), strokeWidth = width / 3)
        drawPath(head, color)
        // Queue (Sud) discrète
        drawLine(color.copy(alpha = 0.35f), c, Offset(c.x, c.y + length * 0.45f), strokeWidth = width / 4)
    }
}

/** Angle entre la verticale et la normale à l'écran (0° = téléphone à plat). */
private fun tiltDeg(down: Vec3) = Math.toDegrees(acos(down.z.coerceIn(-1.0, 1.0)))

private fun fmt3(sigmaDeg: Double) = if (sigmaDeg.isFinite()) "%.1f°".format(3 * sigmaDeg) else "—"

private fun fmt(v: Double) = if (v.isFinite()) "%.1f".format(((v % 360) + 360) % 360) else "—"
