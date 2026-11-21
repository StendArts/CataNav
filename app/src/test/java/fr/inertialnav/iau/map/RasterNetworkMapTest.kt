package fr.inertialnav.iau.map

import fr.inertialnav.iau.core.math.Quaternion
import fr.inertialnav.iau.kal.InsEkf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.math.hypot
import kotlin.random.Random

class RasterNetworkMapTest {

    /** Carte synthétique 0,5 m/px environ, au-dessus de Paris. */
    private fun build(w: Int, h: Int, walkable: (Int, Int) -> Boolean): RasterNetworkMap {
        val bpr = (w + 7) / 8
        val bits = ByteArray(bpr * h)
        for (r in 0 until h) for (c in 0 until w) if (walkable(r, c)) {
            bits[r * bpr + c / 8] = (bits[r * bpr + c / 8].toInt() or (0x80 ushr (c % 8))).toByte()
        }
        // Sérialisation au format .iaumap pour tester aussi le lecteur
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeBytes("IAUMAP01")
            out.writeInt(w); out.writeInt(h)
            out.writeDouble(2.33); out.writeDouble(48.84)
            out.writeDouble(6.866e-6); out.writeDouble(4.504e-6)
            out.writeUTF("test")
            val gz = ByteArrayOutputStream()
            GZIPOutputStream(gz).use { it.write(bits) }
            out.write(gz.toByteArray())
        }
        return RasterNetworkMap.read(bos.toByteArray().inputStream())
    }

    @Test
    fun corridorSnapping() {
        val map = build(100, 100) { _, c -> c in 50..52 }
        val onNet = map.pixelCenter(30, 51)
        assertTrue(map.nearest(onNet.latitudeDeg, onNet.longitudeDeg, 10.0)!!.onNetwork)

        val off = map.pixelCenter(30, 40)
        val m = map.nearest(off.latitudeDeg, off.longitudeDeg, 20.0)
        assertNotNull(m)
        m!!
        assertFalse(m.onNetwork)
        assertEquals(10 * map.pixelWidthM, m.distanceM, 1e-6) // centre col 40 → centre col 50
        assertNull(map.nearest(off.latitudeDeg, off.longitudeDeg, 3.0)) // hors du rayon
    }

    @Test
    fun ringSearchMatchesBruteForce() {
        val rnd = Random(3)
        val map = build(120, 90) { _, _ -> rnd.nextDouble() < 0.01 }
        repeat(200) {
            val lat = map.southLatDeg + rnd.nextDouble() * (map.northLatDeg - map.southLatDeg)
            val lon = map.westLonDeg + rnd.nextDouble() * (map.eastLonDeg - map.westLonDeg)
            val cx = (lon - map.westLonDeg) / map.pixelLonDeg
            val cy = (map.northLatDeg - lat) / map.pixelLatDeg
            var best = Double.MAX_VALUE
            for (r in 0 until map.height) for (c in 0 until map.width) if (map.isWalkable(r, c)) {
                best = minOf(best, hypot((c + 0.5 - cx) * map.pixelWidthM, (r + 0.5 - cy) * map.pixelHeightM))
            }
            val m = map.nearest(lat, lon, 1000.0)!!
            if (m.onNetwork) assertTrue(map.isWalkable(cy.toInt(), cx.toInt()))
            else assertEquals(best, m.distanceM, 1e-9)
        }
    }

    @Test
    fun realCatacombMapLoads() {
        val f = listOf("src/main/assets/maps/catacombes.iaumap", "app/src/main/assets/maps/catacombes.iaumap")
            .map(::File).firstOrNull { it.exists() } ?: return // fichier non généré : test ignoré
        val map = f.inputStream().use { RasterNetworkMap.read(it) }
        assertEquals(6307, map.width)
        assertEquals(48.8483235, map.northLatDeg, 1e-9)
        assertEquals(2.3025517, map.westLonDeg, 1e-9)
        assertEquals(0.50, map.pixelWidthM, 0.01)
        assertEquals(0.50, map.pixelHeightM, 0.01)
        // Centre de la carte (Denfert / Montsouris) : une galerie à moins de 40 m
        val lat = (map.northLatDeg + map.southLatDeg) / 2
        val lon = (map.westLonDeg + map.eastLonDeg) / 2
        assertTrue(map.covers(lat, lon))
        assertNotNull(map.nearest(lat, lon, 40.0))
    }

    @Test
    fun alongDirectionUpdateOnlyConstrainsCrossTrack() {
        val ekf = InsEkf()
        ekf.initialize(0L, Quaternion.IDENTITY, sigmaPosH = 10.0)
        val r = ekf.updateAlongDirection(1.0, 0.0, 4.0, 1.5)
        assertTrue(r.accepted)
        assertTrue(ekf.sigma(InsEkf.IP) < 2.0) // Nord : contraint
        assertEquals(10.0, ekf.sigma(InsEkf.IP + 1), 1e-6) // Est : inchangé
        assertEquals(4.0 * 100 / (100 + 2.25), ekf.position.x, 1e-6)
    }
}
