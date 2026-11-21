package fr.inertialnav.iau.ihm.charts

import fr.inertialnav.iau.config.Params
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.inertialnav.iau.engine.GpsHistoryKey
import fr.inertialnav.iau.engine.HistoryKey
import fr.inertialnav.iau.engine.NavHistory
import fr.inertialnav.iau.engine.NavSnapshot
import fr.inertialnav.iau.map.RasterNetworkMap
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileAssetsProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.GroundOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.ceil


/** Fonds de carte disponibles. */
enum class BaseMap(val label: String) {
    CLASSIC("Plan classique"),
    CATACOMBS("Catacombes"),
}

/**
 * Carte : trajectoire hybridée KAL, positions GPS valides, position courante et cercle
 * d'incertitude à 3σ, sur fond OpenStreetMap ou plan des catacombes.
 */
@Composable
fun TrajectoryMap(
    history: NavHistory,
    snapshot: NavSnapshot,
    baseMap: BaseMap,
    follow: Boolean,
    onUserPan: () -> Unit,
    modifier: Modifier = Modifier,
    network: RasterNetworkMap? = null,
) {
    var mapRef by remember { mutableStateOf<TrajectoryMapView?>(null) }
    val currentOnUserPan by rememberUpdatedState(onUserPan)

    // Cycle de vie osmdroid (threads de chargement des tuiles)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapRef) {
        val map = mapRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map?.onResume()
                Lifecycle.Event.ON_PAUSE -> map?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx -> TrajectoryMapView(ctx) { currentOnUserPan() }.also { mapRef = it } },
        update = {
            it.setBaseMap(baseMap)
            it.setNetwork(network)
            it.render(history, snapshot, follow)
        },
        onRelease = { it.onDetach() },
        modifier = modifier,
    )
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TrajectoryMapView(context: Context, private val onUserPan: () -> Unit) : MapView(context) {

    private val gpsTrack = Polyline(this).apply {
        outlinePaint.color = ChartColors.GPS
        outlinePaint.strokeWidth = 4f
        title = "GPS"
    }
    private val kalTrack = Polyline(this).apply {
        outlinePaint.color = ChartColors.FILTER
        outlinePaint.strokeWidth = 7f
        title = "KAL"
    }
    private val uncertainty = Polygon(this).apply {
        fillPaint.color = Color.argb(0x40, 0x1E, 0x88, 0xE5)
        outlinePaint.color = ChartColors.FILTER
        outlinePaint.strokeWidth = 2f
    }
    private val positionMarker = Marker(this).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        title = "Position hybridée"
    }
    private var centeredOnce = false

    /** Plan des catacombes : tuiles embarquées (assets/catacombes_plan/z/x/y.webp). */
    private val catacombSource = XYTileSource(
        Params.Map.PLAN_TILES, Params.Map.PLAN_MIN_ZOOM, Params.Map.PLAN_MAX_ZOOM, 256, Params.Map.PLAN_TILE_EXTENSION, arrayOf(),
    )
    private val catacombProvider = SimpleRegisterReceiver(context).let { receiver ->
        MapTileProviderArray(
            catacombSource, receiver,
            arrayOf<MapTileModuleProviderBase>(MapTileAssetsProvider(receiver, context.assets, catacombSource)),
        )
    }
    private val catacombOverlay = TilesOverlay(catacombProvider, context).apply {
        // Hors de l'emprise du plan : laisser voir le fond OpenStreetMap
        setLoadingBackgroundColor(Color.TRANSPARENT)
        setLoadingLineColor(Color.TRANSPARENT)
    }
    private var baseMap: BaseMap? = null

    /** Réseau praticable de la carte d'hybridation, superposé en doré. */
    private val networkOverlay = GroundOverlay().apply { transparency = 0.15f }
    private var shownNetwork: RasterNetworkMap? = null

    init {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        controller.setZoom(3.0)
        overlays.addAll(listOf(gpsTrack, uncertainty, kalTrack, positionMarker))
        setOnTouchListener { v, e ->
            // La carte capte le geste : un éventuel parent défilant ne doit pas l'intercepter
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (e.action == MotionEvent.ACTION_MOVE) onUserPan()
            false
        }
    }

    fun setBaseMap(mode: BaseMap) {
        if (mode == baseMap) return
        baseMap = mode
        overlays.remove(catacombOverlay)
        if (mode == BaseMap.CATACOMBS) {
            overlays.add(0, catacombOverlay) // sous le réseau et les traces
            // Au-delà du zoom 18, les tuiles 18 sont agrandies (zoom fractionnaire)
            val max = Params.Map.PLAN_MAX_ZOOM + 0.99
            setMaxZoomLevel(max)
            if (zoomLevelDouble > max) controller.setZoom(max)
            // Sans position connue, montrer directement le plan
            if (!centeredOnce) centerOnCatacombs()
        } else {
            setMaxZoomLevel(null) // valeur du fond OpenStreetMap
        }
        invalidate()
    }

    fun setNetwork(map: RasterNetworkMap?) {
        if (map === shownNetwork) return
        shownNetwork = map
        overlays.remove(networkOverlay)
        if (map == null) {
            invalidate()
            return
        }
        val (w, h, mask) = map.downsample(Params.Ui.MAP_NETWORK_DOWNSAMPLE)
        val color = Color.argb(0xC0, 0xF8, 0xBE, 0x48) // or du logo
        val pixels = IntArray(w * h) { if (mask[it]) color else Color.TRANSPARENT }
        networkOverlay.image = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        networkOverlay.setPosition(
            GeoPoint(map.northLatDeg, map.westLonDeg),
            GeoPoint(map.southLatDeg, map.eastLonDeg),
        )
        val index = if (overlays.contains(catacombOverlay)) 1 else 0
        overlays.add(index, networkOverlay)
        invalidate()
    }

    fun render(h: NavHistory, snap: NavSnapshot, follow: Boolean) {
        kalTrack.setPoints(track(h[HistoryKey.LAT], h[HistoryKey.LON]))
        gpsTrack.setPoints(track(h[GpsHistoryKey.LAT], h[GpsHistoryKey.LON]))

        val lat = snap.latitudeDeg
        val lon = snap.longitudeDeg
        if (lat.isFinite() && lon.isFinite()) {
            val here = GeoPoint(lat, lon)
            positionMarker.position = here
            positionMarker.setVisible(true)
            val r = 3 * snap.sigmaHorizontalM
            uncertainty.points = if (r.isFinite()) Polygon.pointsAsCircle(here, r) else emptyList()
            if (!centeredOnce) {
                controller.setZoom(Params.Ui.MAP_FIRST_FIX_ZOOM)
                centeredOnce = true
            }
            if (follow) controller.setCenter(here)
        } else {
            positionMarker.setVisible(false)
            uncertainty.points = emptyList()
        }
        invalidate()
    }

    /** Centre sur le plan des catacombes (utile sans position, p. ex. pour consulter le plan). */
    fun centerOnCatacombs() {
        controller.setZoom(Params.Map.PLAN_OVERVIEW_ZOOM)
        controller.setCenter(GeoPoint(Params.Map.PLAN_CENTER_LAT, Params.Map.PLAN_CENTER_LON))
    }

    override fun onDetach() {
        super.onDetach()
        // Le calque peut ne pas être attaché (fond classique) : libérer son fournisseur
        catacombOverlay.onDetach(this)
    }

    private fun track(lat: DoubleArray, lon: DoubleArray): List<GeoPoint> {
        val n = minOf(lat.size, lon.size)
        val stride = ceil(n.toDouble() / Params.Ui.MAP_MAX_TRACK_POINTS).toInt().coerceAtLeast(1)
        val out = ArrayList<GeoPoint>(n / stride + 1)
        var i = 0
        while (i < n) {
            if (lat[i].isFinite() && lon[i].isFinite()) out += GeoPoint(lat[i], lon[i])
            i += stride
        }
        return out
    }
}
