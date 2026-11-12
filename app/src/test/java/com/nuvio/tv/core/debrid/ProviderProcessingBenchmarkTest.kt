package com.nuvio.tv.core.debrid

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nuvio.tv.core.debrid.ProviderStreamFixtures.Kind
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import io.mockk.mockk
import java.io.File
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host JVM benchmark for provider-result processing: the debrid presentation step
 * (classify → filter/sort/limit → format) that `StreamRepositoryImpl.mergePresentedResult`
 * runs on the merged list of an addon on every arrival. Run with
 * `./gradlew :app:testFullDebugUnitTest --tests '*ProviderProcessingBenchmarkTest' -Pnuvio.bench=true`.
 *
 * Every timed stage returns a checksum that must equal a value derived from the fixture tags or
 * from the Kotlin reference, on every call, so a stage that stops doing its work fails the run
 * instead of reporting a fast time. `ProviderProcessingPipelineTest` pins the output itself.
 *
 * Also writes the classified streams and their Kotlin facts to
 * `rust/target/bench/provider_streams_<n>.json` so
 * `cargo test --release -p nuvio-core --test bench -- --ignored --nocapture` can check and time
 * pure Rust classification of exactly the same inputs (the JNI-free lower bound).
 * Results go to stdout and `app/build/reports/nuvio-bench/provider_processing.csv`.
 */
class ProviderProcessingBenchmarkTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = formatter
    )
    // java.lang.management is not on the android.jar compile classpath; the host JVM has it.
    private val allocatedBytes: (Long) -> Long = run {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val read: (Long) -> Long = { thread -> method.invoke(bean, thread) as Long }
        read
    }
    private val lines = mutableListOf<String>()

    private fun emit(line: String) {
        println(line)
        lines += line
    }

    @Test
    fun `benchmark debrid presentation phases`() {
        assumeTrue(System.getProperty("nuvio.bench") == "true")
        val previous = ReleaseFactsClassifier.nativeEnabled
        try {
            // Cold: first use in this JVM, before any warm-up.
            val cold = ProviderStreamFixtures.addonStreams(150, Random(1))
            val coldGroup = group(cold.map { it.stream })
            val coldExpected = cold.count { it.kind.presented }
            ReleaseFactsClassifier.nativeEnabled = false
            val coldKotlin = once(coldExpected) { applyCount(coldGroup, ProviderStreamFixtures.defaultSettings) }
            val coldLoad = once(null) { if (System.getProperty("nuvio.core.hostLib") != null && NativeReleaseFacts.isAvailable) 1 else 0 }
            val native = System.getProperty("nuvio.core.hostLib") != null && NativeReleaseFacts.isAvailable
            val coldRust = if (native) once(cold.size) { NativeReleaseFacts.classify(cold.map { it.stream }).size } else Double.NaN
            emit("cold_ms,kotlin_apply_150=%.1f,rust_load_and_handshake=%.1f,rust_first_classify_150=%.1f"
                .format(coldKotlin / 1e6, coldLoad / 1e6, coldRust / 1e6))

            emit("streams,stage,in,out,p50_us,p90_us,p99_us,min_us,max_us,round_p50_min_us,round_p50_max_us,alloc_kb")
            for (size in listOf(50, 150, 400)) {
                val tagged = ProviderStreamFixtures.addonStreams(size, Random(size))
                val all = tagged.map { it.stream }
                // What the presentation step classifies: the cached, managed debrid streams.
                val managed = tagged.filter { it.kind.managed }.map { it.stream }
                val presented = tagged.count { it.kind.presented }
                check(managed.size >= size * 3 / 4) { "fixtures: only ${managed.size} of $size streams are classified" }
                val group = group(all)

                val kotlinFacts = managed.map(ReleaseFactsClassifier::classifyKotlin)
                val factsChecksum = kotlinFacts.sumOf(::checksum)
                check(kotlinFacts.count { it.resolution.value > 0 } >= managed.size * 8 / 10) { "fixtures: resolutions not recognised" }
                dumpForRust(size, managed, kotlinFacts)

                fun row(stage: String, input: Int, expected: Int, block: () -> Int) {
                    check(expected > 0) { "$stage: expected checksum must be positive" }
                    val s = measure(expected, block)
                    emit("%d,%s,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f".format(
                        size, stage, input, expected, s.p50 / 1e3, s.p90 / 1e3, s.p99 / 1e3, s.min / 1e3, s.max / 1e3,
                        s.roundP50Min / 1e3, s.roundP50Max / 1e3, s.bytes / 1024))
                }

                ReleaseFactsClassifier.nativeEnabled = false
                row("classify_kotlin", managed.size, factsChecksum) { managed.sumOf { checksum(ReleaseFactsClassifier.classifyKotlin(it)) } }
                if (native) {
                    check(NativeReleaseFacts.classify(managed) == kotlinFacts) { "Rust facts differ from Kotlin" }
                    row("classify_rust_jni_total", managed.size, factsChecksum) { NativeReleaseFacts.classify(managed).sumOf(::checksum) }
                    row("jni_encode", managed.size, managed.size) { NativeReleaseFacts.encode(managed).size }
                    val batch = NativeReleaseFacts.encode(managed)
                    row("jni_native_call", managed.size, managed.size) { NativeReleaseFacts.call(batch); batch.size }
                    row("jni_decode", managed.size, factsChecksum) { NativeReleaseFacts.decode(batch).sumOf(::checksum) }
                }

                for ((label, settings) in listOf("default" to ProviderStreamFixtures.defaultSettings, "custom" to ProviderStreamFixtures.customSettings)) {
                    val withFacts = DirectDebridStreamFilter.applyPreferencesWithFacts(managed, settings, kotlinFacts)
                    val reference = presentation.apply(group, settings, includeBadgeMatches = false).single().streams
                    val passthrough = tagged.count { it.kind == Kind.PASSTHROUGH }
                    check(reference.size == withFacts.size + passthrough) { "$label: ${reference.size} presented, ${withFacts.size} filtered" }
                    if (label == "default") check(reference.size == presented) { "default: ${reference.size} of $presented presented" }
                    check(withFacts.size >= 10) { "$label: only ${withFacts.size} streams survive filtering" }
                    val formattedChecksum = reference.sumOf { it.name.orEmpty().length + it.description.orEmpty().length }

                    row("filter_sort_limit_$label", managed.size, withFacts.size) {
                        DirectDebridStreamFilter.applyPreferencesWithFacts(managed, settings, kotlinFacts).size
                    }
                    row("format_$label", withFacts.size, withFacts.sumOf { (stream, _) -> formattedLength(stream, reference) }) {
                        withFacts.sumOf { (stream, facts) ->
                            formatter.format(stream, settings, emptyList(), facts).let { it.name.orEmpty().length + it.description.orEmpty().length }
                        }
                    }
                    row("apply_end_to_end_kotlin_$label", size, formattedChecksum) { applyChecksum(group, settings) }
                    if (native) {
                        ReleaseFactsClassifier.nativeEnabled = true
                        check(presentation.apply(group, settings, includeBadgeMatches = false).single().streams == reference) {
                            "$label: Rust-backed output differs from Kotlin"
                        }
                        row("apply_end_to_end_rust_$label", size, formattedChecksum) { applyChecksum(group, settings) }
                        ReleaseFactsClassifier.nativeEnabled = false
                    }
                }
            }
        } finally {
            ReleaseFactsClassifier.nativeEnabled = previous
            File("build/reports/nuvio-bench").apply { mkdirs() }.resolve("provider_processing.csv").writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    private fun group(streams: List<Stream>) = listOf(AddonStreams(ProviderStreamFixtures.ADDON, null, streams))

    private fun applyCount(group: List<AddonStreams>, settings: DebridSettings): Int =
        presentation.apply(group, settings, includeBadgeMatches = false).single().streams.size

    private fun applyChecksum(group: List<AddonStreams>, settings: DebridSettings): Int =
        presentation.apply(group, settings, includeBadgeMatches = false).single().streams
            .sumOf { it.name.orEmpty().length + it.description.orEmpty().length }

    private fun formattedLength(source: Stream, reference: List<Stream>): Int =
        reference.first { it.infoHash == source.infoHash }.let { it.name.orEmpty().length + it.description.orEmpty().length }

    /** Depends on every classified field, so a classifier that returns defaults cannot match. */
    private fun checksum(facts: ReleaseTextFacts): Int =
        1 + facts.resolution.ordinal + 3 * facts.quality.ordinal + 5 * facts.encode.ordinal +
            7 * facts.visualTags.sumOf { it.ordinal + 1 } + 11 * facts.audioTags.sumOf { it.ordinal + 1 } +
            13 * facts.audioChannels.sumOf { it.ordinal + 1 } + 17 * facts.languages.sumOf { it.ordinal + 1 } +
            19 * facts.releaseGroup.length

    private class Stat(
        val p50: Double, val p90: Double, val p99: Double, val min: Double, val max: Double,
        val roundP50Min: Double, val roundP50Max: Double, val bytes: Double
    )

    private fun once(expected: Int?, block: () -> Int): Double {
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start).toDouble()
        check(expected == null || result == expected) { "cold run returned $result, expected $expected" }
        return elapsed
    }

    /**
     * Wall time (ns) over [ROUNDS] rounds of [SAMPLES] calls after a 1.5 s warm-up, plus the spread
     * of the per-round medians and the mean allocated bytes per call.
     */
    private fun measure(expected: Int, block: () -> Int): Stat {
        fun call() {
            val result = block()
            check(result == expected) { "stage returned $result, expected $expected" }
        }
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline) call()
        val samples = DoubleArray(ROUNDS * SAMPLES)
        val roundMedians = DoubleArray(ROUNDS)
        for (round in 0 until ROUNDS) {
            for (i in 0 until SAMPLES) {
                val start = System.nanoTime()
                call()
                samples[round * SAMPLES + i] = (System.nanoTime() - start).toDouble()
            }
            roundMedians[round] = samples.copyOfRange(round * SAMPLES, (round + 1) * SAMPLES).apply { sort() }[SAMPLES / 2]
        }
        // Separate pass so the reflective counter reads do not perturb the timings.
        val thread = Thread.currentThread().id
        val bytesBefore = allocatedBytes(thread)
        repeat(SAMPLES) { call() }
        val allocated = allocatedBytes(thread) - bytesBefore
        samples.sort()
        fun percentile(p: Int) = samples[((samples.size - 1) * p + 50) / 100]
        return Stat(
            percentile(50), percentile(90), percentile(99), samples.first(), samples.last(),
            roundMedians.min(), roundMedians.max(), allocated.toDouble() / SAMPLES
        )
    }

    private fun dumpForRust(size: Int, streams: List<Stream>, facts: List<ReleaseTextFacts>) {
        val dir = File("../rust/target/bench").apply { mkdirs() }
        val array = JsonArray()
        streams.forEachIndexed { index, stream ->
            array.add(JsonObject().apply {
                add("input", ProviderStreamFixtures.toFixtureInput(stream))
                add("expected", ReleaseFactsParityTest.toJson(facts[index]))
            })
        }
        File(dir, "provider_streams_$size.json").writeText(GsonBuilder().disableHtmlEscaping().create().toJson(array))
    }

    private companion object {
        const val ROUNDS = 5
        const val SAMPLES = 61
    }
}
