package fr.inertialnav.iau.ihm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.inertialnav.iau.R
import fr.inertialnav.iau.ui.theme.LogoCream
import fr.inertialnav.iau.ui.theme.LogoGold
import fr.inertialnav.iau.ui.theme.LogoNight
import fr.inertialnav.iau.ui.theme.LogoStone
import fr.inertialnav.iau.ui.theme.LogoVault

/**
 * Bannière d'en-tête reprenant l'univers du logo : voûte de pierre sombre, lueur dorée,
 * titre crème et tracé doré.
 */
@Composable
fun AppBanner(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(124.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(LogoVault, LogoNight))),
    ) {
        // Arches concentriques (tunnel) et lueur dorée derrière le logo
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(LogoGold.copy(alpha = 0.28f), LogoGold.copy(alpha = 0f)),
                    center = Offset(size.height * 0.62f, size.height * 0.62f),
                    radius = size.height * 0.9f,
                )
            )
            val cx = size.width * 0.82f
            for (k in 1..5) {
                val r = size.height * (0.28f + 0.22f * k)
                drawArc(
                    LogoStone.copy(alpha = 0.32f - k * 0.045f),
                    startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(cx - r, size.height * 0.95f - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width = 5f),
                )
            }
            // Tracé doré ondulant, comme le chemin du logo
            val path = Path().apply {
                val y0 = size.height * 0.80f
                moveTo(size.height * 1.15f, y0)
                cubicTo(size.width * 0.45f, y0 - 26f, size.width * 0.6f, y0 + 22f, size.width * 0.96f, y0 - 6f)
            }
            drawPath(path, LogoGold.copy(alpha = 0.25f), style = Stroke(width = 10f))
            drawPath(path, LogoGold, style = Stroke(width = 3f))
        }

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Column(Modifier.padding(bottom = 18.dp)) {
                Text(
                    stringResource(R.string.app_name).uppercase(),
                    style = TextStyle(
                        color = LogoCream,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 32.sp,
                        letterSpacing = 4.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Localisation inertielle hybridée",
                    style = TextStyle(
                        color = LogoGold,
                        fontSize = 13.sp,
                        letterSpacing = 0.8.sp,
                        textAlign = TextAlign.Start,
                    ),
                )
            }
        }
    }
}
