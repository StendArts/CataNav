package fr.inertialnav.iau.map

import fr.inertialnav.iau.core.geo.GeoPosition
import fr.inertialnav.iau.core.geo.LocalTangentPlane
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Réseau praticable sous forme de masque binaire géoréférencé (WGS84, pixels alignés sur
 * les méridiens/parallèles), produit par tools/map/build_network_map.py.
 *
 * Format .iaumap (gros-boutiste) :
 * "IAUMAP01" | int largeur | int hauteur | double lon_gauche | double lat_haut
 * | double dlon_par_px | double dlat_par_px | UTF nom | gzip(bitset ligne par ligne, MSB d'abord)
 */
class RasterNetworkMap(
    override val name: String,
    val width: Int,
    val height: Int,
    /** Bord gauche (longitude) et bord haut (latitude) du pixel (0, 0). */
    val westLonDeg: Double,
    val northLatDeg: Double,
    val pixelLonDeg: Double,
    val pixelLatDeg: Double,
    private val bits: ByteArray,
) : MapMatcher {

    private val bytesPerRow = (width + 7) / 8

    val eastLonDeg: Double get() = westLonDeg + width * pixelLonDeg
    val southLatDeg: Double get() = northLatDeg - height * pixelLatDeg

    /** Taille d'un pixel en mètres (évaluée au centre de la carte). */
    val pixelWidthM: Double
    val pixelHeightM: Double

    init {
        require(bits.size >= bytesPerRow * height) { "Masque tronqué" }
        val center = LocalTangentPlane(GeoPosition((northLatDeg + southLatDeg) / 2, (westLonDeg + eastLonDeg) / 2, 0.0))
        pixelWidthM = pixelLonDeg * center.metersPerDegreeLon
        pixelHeightM = pixelLatDeg * center.metersPerDegreeLat
    }

    fun isWalkable(row: Int, col: Int): Boolean {
        if (row !in 0 until height || col !in 0 until width) return false
        val b = bits[row * bytesPerRow + (col ushr 3)].toInt()
        return (b shr (7 - (col and 7))) and 1 == 1
    }

    override fun covers(latitudeDeg: Double, longitudeDeg: Double) =
        latitudeDeg in southLatDeg..northLatDeg && longitudeDeg in westLonDeg..eastLonDeg

    /** Position fractionnaire en pixels (colonne, ligne). */
    private fun colOf(lon: Double) = (lon - westLonDeg) / pixelLonDeg
    private fun rowOf(lat: Double) = (northLatDeg - lat) / pixelLatDeg

    /** Centre géographique d'un pixel. */
    fun pixelCenter(row: Int, col: Int) = GeoPosition(
        northLatDeg - (row + 0.5) * pixelLatDeg,
        westLonDeg + (col + 0.5) * pixelLonDeg,
        0.0,
    )

    /**
     * Recherche par anneaux carrés croissants autour de la position ; on s'arrête dès que
     * l'anneau courant est plus loin que le meilleur candidat trouvé (distance euclidienne en m).
     */
    override fun nearest(latitudeDeg: Double, longitudeDeg: Double, searchRadiusM: Double): MapMatch? {
        val cx = colOf(longitudeDeg)
        val cy = rowOf(latitudeDeg)
        val c0 = floor(cx).toInt()
        val r0 = floor(cy).toInt()
        val here = GeoPosition(latitudeDeg, longitudeDeg, 0.0)
        if (isWalkable(r0, c0)) return MapMatch(here, 0.0, onNetwork = true)

        val minPx = min(pixelWidthM, pixelHeightM)
        val maxRing = ceil(searchRadiusM / minPx).toInt()
        var bestD = Double.MAX_VALUE
        var bestR = -1
        var bestC = -1

        fun consider(r: Int, c: Int) {
            if (!isWalkable(r, c)) return
            val d = hypot((c + 0.5 - cx) * pixelWidthM, (r + 0.5 - cy) * pixelHeightM)
            if (d < bestD) {
                bestD = d
                bestR = r
                bestC = c
            }
        }

        for (k in 1..maxRing) {
            // Distance minimale possible des pixels de l'anneau k
            if ((k - 1) * minPx > bestD) break
            for (dc in -k..k) {
                consider(r0 - k, c0 + dc)
                consider(r0 + k, c0 + dc)
            }
            for (dr in -k + 1 until k) {
                consider(r0 + dr, c0 - k)
                consider(r0 + dr, c0 + k)
            }
        }
        if (bestR < 0 || bestD > searchRadiusM) return null
        return MapMatch(pixelCenter(bestR, bestC), bestD, onNetwork = false)
    }

    /**
     * Masque sous-échantillonné (un pixel de sortie est praticable si l'un des pixels du bloc
     * l'est), pour l'affichage.
     */
    fun downsample(factor: Int): Triple<Int, Int, BooleanArray> {
        val w = (width + factor - 1) / factor
        val h = (height + factor - 1) / factor
        val out = BooleanArray(w * h)
        for (r in 0 until height) {
            val rowBase = r * bytesPerRow
            val orow = (r / factor) * w
            for (byteIdx in 0 until bytesPerRow) {
                val b = bits[rowBase + byteIdx].toInt() and 0xFF
                if (b == 0) continue
                for (bit in 0 until 8) {
                    if ((b shr (7 - bit)) and 1 == 0) continue
                    val c = byteIdx * 8 + bit
                    if (c < width) out[orow + c / factor] = true
                }
            }
        }
        return Triple(w, h, out)
    }

    companion object {
        private const val MAGIC = "IAUMAP01"

        @Throws(IOException::class)
        fun read(input: InputStream): RasterNetworkMap {
            val din = DataInputStream(input.buffered())
            val magic = ByteArray(MAGIC.length).also { din.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw IOException("Format de carte inconnu")
            val w = din.readInt()
            val h = din.readInt()
            val lon0 = din.readDouble()
            val lat0 = din.readDouble()
            val dLon = din.readDouble()
            val dLat = din.readDouble()
            val name = din.readUTF()
            if (w <= 0 || h <= 0 || dLon <= 0 || dLat <= 0) throw IOException("En-tête de carte invalide")
            val bits = ByteArray(((w + 7) / 8) * h)
            DataInputStream(GZIPInputStream(din)).use { it.readFully(bits) }
            return RasterNetworkMap(name, w, h, lon0, lat0, dLon, dLat, bits)
        }
    }
}
