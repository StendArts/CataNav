package fr.inertialnav.iau.pedestrian

import fr.inertialnav.iau.core.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PedestrianTest {

    private val g = 9.80665

    @Test
    fun detectsStepsOfSyntheticWalk() {
        val det = StepDetector()
        val stepHz = 1.8
        val rnd = Random(1)
        var steps = 0
        val lengths = mutableListOf<Double>()
        for (k in 0 until 1000) { // 10 s à 100 Hz
            val t = k * 0.01
            val a = g + 2.5 * sin(2 * PI * stepHz * t) + rnd.nextDouble(-0.2, 0.2)
            det.process((t * 1e9).toLong() + 1, a)?.let { steps++; lengths += it.lengthM }
        }
        assertEquals(18.0, steps.toDouble(), 1.5)
        // Amplitude crête-à-crête ≈ 5 m/s² → L ≈ 0,47·5^¼ ≈ 0,70 m
        assertEquals(0.70, lengths.drop(2).average(), 0.1)
    }

    @Test
    fun zuptDetectsStillnessOnly() {
        val z = ZuptDetector()
        repeat(50) { z.process(Vec3(0.0, 0.0, -g), Vec3(0.001, 0.0, 0.0)) }
        assertTrue(z.stationary)
        repeat(50) { k -> z.process(Vec3(0.0, 0.0, -g + 2 * sin(k * 0.5)), Vec3(0.5, 0.0, 0.0)) }
        assertFalse(z.stationary)
    }
}
