package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host JVM benchmark of release classification, Kotlin vs Rust (including JNI marshalling).
 * Run with `./gradlew :app:testFullDebugUnitTest --tests '*ReleaseFactsBenchmarkTest' -Pnuvio.bench=true`.
 * Host HotSpot numbers do not represent ART on TV hardware; see docs/nuvio-next/PERFORMANCE.md.
 */
class ReleaseFactsBenchmarkTest {

    @Test
    fun `benchmark kotlin and native release classification`() {
        assumeTrue(System.getProperty("nuvio.bench") == "true")
        assumeTrue(System.getProperty("nuvio.core.hostLib") != null && NativeReleaseFacts.isAvailable)

        val random = Random(42)
        val fixtures = ReleaseFactsParityTest.loadFixtures().map(ReleaseFactsParityTest::streamFrom)
        val pool = fixtures + List(2_000) { ReleaseFactsParityTest.randomStream(random) }

        println("batch,kotlin_us_per_batch,native_us_per_batch,kotlin_ns_per_stream,native_ns_per_stream,speedup")
        for (batchSize in listOf(20, 100, 500, 2_000)) {
            val batch = List(batchSize) { pool[(it * 7919) % pool.size] }
            val kotlin = measure(batch) { streams -> streams.map(ReleaseFactsClassifier::classifyKotlin) }
            val native = measure(batch) { streams -> NativeReleaseFacts.classify(streams) }
            println(
                "%d,%.1f,%.1f,%.0f,%.0f,%.2f".format(
                    batchSize, kotlin / 1_000.0, native / 1_000.0,
                    kotlin / batchSize, native / batchSize, kotlin / native
                )
            )
        }
    }

    /** Median wall time in ns of one call after warm-up. */
    private fun measure(batch: List<Stream>, block: (List<Stream>) -> List<ReleaseTextFacts>): Double {
        var sink = 0
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) sink += block(batch).size
        val samples = DoubleArray(31) {
            val start = System.nanoTime()
            sink += block(batch).size
            (System.nanoTime() - start).toDouble()
        }
        check(sink > 0)
        samples.sort()
        return samples[samples.size / 2]
    }
}
