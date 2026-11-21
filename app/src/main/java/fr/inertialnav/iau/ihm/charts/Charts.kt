package fr.inertialnav.iau.ihm.charts

import fr.inertialnav.iau.config.Params
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.renderer.LineChartRenderer
import kotlin.math.ceil

/** Palette des séries (lisible en thème clair et sombre). */
object ChartColors {
    val FILTER = Color.rgb(0x1E, 0x88, 0xE5)
    val GPS = Color.rgb(0xE5, 0x39, 0x35)
    val MAG = Color.rgb(0x43, 0xA0, 0x47)
    val AXIS_X = Color.rgb(0xE5, 0x39, 0x35)
    val AXIS_Y = Color.rgb(0x43, 0xA0, 0x47)
    val AXIS_Z = Color.rgb(0x1E, 0x88, 0xE5)
    val ACC = Color.rgb(0x8E, 0x24, 0xAA)
    val GYRO = Color.rgb(0xF5, 0x7C, 0x00)
}

/**
 * Série tracée (estimée ou mesurée), éventuellement avec son écart-type pour l'enveloppe ±3σ.
 * Les valeurs sont alignées sur le tableau [time] (secondes).
 */
class BandSeries(
    val label: String,
    val color: Int,
    val time: DoubleArray,
    val values: DoubleArray,
    val sigmas: DoubleArray? = null,
    val scale: Double = 1.0,
)

/** Mesures ponctuelles (p. ex. GPS), tracées en points. */
class PointSeries(val label: String, val color: Int, val time: DoubleArray, val values: DoubleArray)

/** Un panneau d'un sous-graphe. */
class Panel(
    val title: String,
    val series: List<BandSeries>,
    val points: List<PointSeries> = emptyList(),
    val relative: Boolean = false,
    val yDecimals: Int = 2,
)


/**
 * Équivalent d'un « subplot » : panneaux empilés partageant la même fenêtre de temps,
 * axes verticaux de largeur fixe pour que les axes de temps soient alignés.
 * Seul le dernier panneau porte les graduations de temps.
 */
@Composable
fun Subplot(panels: List<Panel>, windowS: Double, modifier: Modifier = Modifier, panelHeight: Dp = Params.Ui.SUBPLOT_PANEL_HEIGHT_DP.dp) {
    Column(modifier) {
        panels.forEachIndexed { i, p ->
            Text(
                p.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 0.dp else 6.dp),
            )
            BandChart(
                panel = p,
                windowS = windowS,
                showTimeAxis = i == panels.lastIndex,
                height = panelHeight + if (i == panels.lastIndex) 18.dp else 0.dp,
            )
        }
    }
}

/**
 * Courbe temporelle défilante : seule la fenêtre [t_fin − windowS, t_fin] est affichée.
 * Chaque série est tracée en trait plein ; son enveloppe ±3σ est une zone colorée transparente.
 *
 * En mode [Panel.relative], on trace les écarts à une valeur de référence (dernière estimée)
 * que l'on réajoute dans les libellés : indispensable pour latitude/longitude, que la
 * précision d'un Float (~0,4 m) ne permet pas de tracer directement.
 */
@Composable
fun BandChart(
    panel: Panel,
    windowS: Double,
    modifier: Modifier = Modifier,
    showTimeAxis: Boolean = true,
    height: Dp = 160.dp,
) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    AndroidView(
        factory = { ctx ->
            LineChart(ctx).apply {
                renderer = BandLineRenderer(this)
                description.isEnabled = false
                setNoDataText("En attente de données")
                setNoDataTextColor(textColor)
                legend.textColor = textColor
                legend.isWordWrapEnabled = true
                legend.verticalAlignment = Legend.LegendVerticalAlignment.TOP
                legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
                legend.setDrawInside(true)
                xAxis.textColor = textColor
                xAxis.gridColor = gridColor
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float) = "%.0f s".format(value)
                }
                axisLeft.textColor = textColor
                axisLeft.gridColor = gridColor
                axisLeft.minWidth = Params.Ui.CHART_AXIS_WIDTH_DP
                axisLeft.maxWidth = Params.Ui.CHART_AXIS_WIDTH_DP
                axisLeft.setLabelCount(4, false)
                axisRight.isEnabled = false
                // Le défilement est piloté par la fenêtre de temps : pas d'interaction tactile
                setTouchEnabled(false)
            }
        },
        update = { chart ->
            chart.xAxis.setDrawLabels(showTimeAxis)
            render(chart, panel, windowS)
        },
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 4.dp),
    )
}

/** Jeu de données invisible portant la borne +3σ ; [lower] porte la borne −3σ. */
private class BandDataSet(upper: List<Entry>, val lower: LineDataSet, val bandColor: Int) : LineDataSet(upper, "") {
    init {
        isVisible = false
        setDrawValues(false)
        setDrawCircles(false)
        form = Legend.LegendForm.NONE
        isHighlightEnabled = false
    }
}

private fun invisibleSet(entries: List<Entry>) = LineDataSet(entries, "").apply {
    isVisible = false // reste pris en compte dans l'échelle de l'axe Y
    setDrawValues(false)
    setDrawCircles(false)
    form = Legend.LegendForm.NONE
    isHighlightEnabled = false
}

/**
 * Rendu MPAndroidChart remplissant la zone comprise entre les bornes +3σ et −3σ, avant le
 * tracé des courbes (qui restent ainsi au premier plan).
 */
private class BandLineRenderer(private val lineChart: LineChart) :
    LineChartRenderer(lineChart, lineChart.animator, lineChart.viewPortHandler) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun drawData(c: Canvas) {
        val data = lineChart.lineData
        if (data != null) {
            val trans = lineChart.getTransformer(YAxis.AxisDependency.LEFT)
            c.save()
            c.clipRect(mViewPortHandler.contentRect)
            for (set in data.dataSets) {
                if (set !is BandDataSet) continue
                val n = minOf(set.entryCount, set.lower.entryCount)
                if (n < 2) continue
                val pts = FloatArray(4 * n)
                for (i in 0 until n) {
                    val u = set.getEntryForIndex(i)
                    pts[2 * i] = u.x
                    pts[2 * i + 1] = u.y
                    // Borne basse parcourue à rebours pour fermer le polygone
                    val l = set.lower.getEntryForIndex(n - 1 - i)
                    pts[2 * (n + i)] = l.x
                    pts[2 * (n + i) + 1] = l.y
                }
                trans.pointValuesToPixel(pts)
                path.reset()
                path.moveTo(pts[0], pts[1])
                var k = 2
                while (k < pts.size) {
                    path.lineTo(pts[k], pts[k + 1])
                    k += 2
                }
                path.close()
                fillPaint.color = set.bandColor
                c.drawPath(path, fillPaint)
            }
            c.restore()
        }
        super.drawData(c)
    }
}

private fun render(chart: LineChart, panel: Panel, windowS: Double) {
    // Fin de fenêtre = instant le plus récent disponible
    val tEnd = (panel.series.mapNotNull { it.time.lastOrNull() } + panel.points.mapNotNull { it.time.lastOrNull() })
        .maxOrNull()
    if (tEnd == null) {
        chart.clear()
        return
    }
    val tStart = tEnd - windowS

    val ref = if (panel.relative) {
        panel.series.firstOrNull()?.values?.lastOrNull { it.isFinite() }
            ?: panel.points.firstOrNull()?.values?.lastOrNull { it.isFinite() } ?: 0.0
    } else {
        0.0
    }

    val sets = ArrayList<LineDataSet>()
    val lines = ArrayList<LineDataSet>()
    for (s in panel.series) {
        val time = s.time
        val first = lowerBound(time, tStart)
        val stride = ceil((time.size - first).toDouble() / Params.Ui.CHART_MAX_POINTS).toInt().coerceAtLeast(1)
        val mid = ArrayList<Entry>()
        val hi = ArrayList<Entry>()
        val lo = ArrayList<Entry>()
        var i = first
        while (i < time.size && i < s.values.size) {
            val v = s.values[i]
            if (v.isFinite()) {
                val t = time[i].toFloat()
                mid += Entry(t, ((v - ref) * s.scale).toFloat())
                val sg = s.sigmas?.getOrNull(i)
                if (sg != null && sg.isFinite()) {
                    hi += Entry(t, ((v + 3 * sg - ref) * s.scale).toFloat())
                    lo += Entry(t, ((v - 3 * sg - ref) * s.scale).toFloat())
                }
            }
            i += stride
        }
        if (mid.isEmpty()) continue
        if (hi.size >= 2) {
            val lower = invisibleSet(lo)
            sets += BandDataSet(hi, lower, withAlpha(s.color, Params.Ui.SIGMA_BAND_ALPHA))
            sets += lower
        }
        lines += LineDataSet(mid, s.label).apply {
            color = s.color
            lineWidth = 1.6f
            setDrawCircles(false)
            setDrawValues(false)
            isHighlightEnabled = false
        }
    }
    for (p in panel.points) {
        val e = ArrayList<Entry>()
        for (k in lowerBound(p.time, tStart) until p.time.size) {
            val v = p.values[k]
            if (v.isFinite()) e += Entry(p.time[k].toFloat(), (v - ref).toFloat())
        }
        if (e.isEmpty()) continue
        lines += LineDataSet(e, p.label).apply {
            color = Color.TRANSPARENT // points seuls, sans ligne
            setCircleColor(p.color)
            circleRadius = 2.5f
            setDrawCircleHole(false)
            setDrawValues(false)
            form = Legend.LegendForm.CIRCLE
            formSize = 6f
            isHighlightEnabled = false
        }
    }
    sets += lines // courbes après les enveloppes

    if (lines.isEmpty()) {
        chart.clear()
        return
    }
    // Légende utile seulement s'il y a plusieurs courbes
    chart.legend.isEnabled = lines.size > 1
    chart.xAxis.axisMinimum = tStart.toFloat()
    chart.xAxis.axisMaximum = tEnd.toFloat()
    val decimals = panel.yDecimals
    chart.axisLeft.valueFormatter = object : ValueFormatter() {
        override fun getFormattedValue(value: Float) = "%.${decimals}f".format(ref + value)
    }
    chart.data = LineData(sets.toList())
    chart.notifyDataSetChanged()
    chart.invalidate()
}

private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

/** Premier indice i tel que a[i] ≥ x (a trié). */
private fun lowerBound(a: DoubleArray, x: Double): Int {
    var lo = 0
    var hi = a.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < x) lo = mid + 1 else hi = mid
    }
    return lo
}
