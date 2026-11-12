package com.nuvio.tv.core.debrid

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolveRaw
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
 * Also writes the generated streams to `rust/target/bench/provider_streams_<n>.json` so
 * `cargo test --release -p nuvio-core --test bench -- --ignored --nocapture` can time pure Rust
 * classification of exactly the same inputs (the JNI-free lower bound).
 */
class ProviderProcessingBenchmarkTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = formatter
    )
    private val settings = DebridSettings(enabled = true, torboxApiKey = "key")
    // java.lang.management is not on the android.jar compile classpath; the host JVM has it.
    private val allocatedBytes: (Long) -> Long = run {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val read: (Long) -> Long = { thread -> method.invoke(bean, thread) as Long }
        read
    }

    private data class Stat(val median: Double, val p10: Double, val p90: Double, val bytes: Double)

    @Test
    fun `benchmark debrid presentation phases`() {
        assumeTrue(System.getProperty("nuvio.bench") == "true")
        val previous = ReleaseFactsClassifier.nativeEnabled
        try {
            // Cold: first use in this JVM, before any warm-up.
            val coldStreams = ProviderStreamFixtures.addonStreams(150, Random(1))
            val coldGroup = listOf(AddonStreams(addonName = "Torrentio", addonLogo = null, streams = coldStreams))
            ReleaseFactsClassifier.nativeEnabled = false
            val coldKotlin = once { presentation.apply(coldGroup, settings, includeBadgeMatches = false).single().streams.size }
            val coldLoad = once { if (System.getProperty("nuvio.core.hostLib") != null && NativeReleaseFacts.isAvailable) 1 else 0 }
            val native = System.getProperty("nuvio.core.hostLib") != null && NativeReleaseFacts.isAvailable
            val coldRust = if (native) once { NativeReleaseFacts.classify(coldStreams).size } else Double.NaN
            println("cold_ms,kotlin_apply_150=%.1f,rust_load_and_handshake=%.1f,rust_first_classify_150=%.1f"
                .format(coldKotlin / 1e6, coldLoad / 1e6, coldRust / 1e6))

            println("streams,stage,median_us,p10_us,p90_us,alloc_kb")
            for (size in listOf(50, 150, 400)) {
                val streams = ProviderStreamFixtures.addonStreams(size, Random(size))
                dumpForRust(size, streams)
                val group = listOf(AddonStreams(addonName = "Torrentio", addonLogo = null, streams = streams))
                val facts = ReleaseFactsClassifier.classifyAll(streams)
                val withFacts = DirectDebridStreamFilter.applyPreferencesWithFacts(streams, settings)
                check(facts.size == size)

                fun row(stage: String, block: () -> Int) {
                    val s = measure(block)
                    println("%d,%s,%.1f,%.1f,%.1f,%.1f".format(size, stage, s.median / 1e3, s.p10 / 1e3, s.p90 / 1e3, s.bytes / 1024))
                }

                row("classify_kotlin") { streams.count { ReleaseFactsClassifier.classifyKotlin(it).encode.ordinal >= 0 } }
                ReleaseFactsClassifier.nativeEnabled = false
                row("filter_sort_limit_incl_classify_kotlin") { DirectDebridStreamFilter.applyPreferencesWithFacts(streams, settings).size }
                row("format") { withFacts.count { (stream, f) -> formatter.format(stream, settings, emptyList(), f).name != null } }
                row("apply_end_to_end_kotlin") { presentation.apply(group, settings, includeBadgeMatches = false).single().streams.size }
                if (native) {
                    row("classify_rust_total") { NativeReleaseFacts.classify(streams).size }
                    row("rust_encode") { NativeReleaseFacts.encode(streams).size }
                    val batch = NativeReleaseFacts.encode(streams)
                    row("rust_native_call") { NativeReleaseFacts.call(batch); batch.size }
                    row("rust_decode") { NativeReleaseFacts.decode(batch).size }
                    ReleaseFactsClassifier.nativeEnabled = true
                    row("filter_sort_limit_incl_classify_rust") { DirectDebridStreamFilter.applyPreferencesWithFacts(streams, settings).size }
                    row("apply_end_to_end_rust") { presentation.apply(group, settings, includeBadgeMatches = false).single().streams.size }
                    ReleaseFactsClassifier.nativeEnabled = false
                }
            }
        } finally {
            ReleaseFactsClassifier.nativeEnabled = previous
        }
    }

    private fun once(block: () -> Int): Double {
        val start = System.nanoTime()
        check(block() >= 0)
        return (System.nanoTime() - start).toDouble()
    }

    /** Median, p10, p90 wall time (ns) and mean allocated bytes of one call, after a 1.5 s warm-up. */
    private fun measure(block: () -> Int): Stat {
        var sink = 0
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline) sink += block()
        val thread = Thread.currentThread().id
        val samples = DoubleArray(41)
        for (i in samples.indices) {
            val start = System.nanoTime()
            sink += block()
            samples[i] = (System.nanoTime() - start).toDouble()
        }
        // Separate pass so the reflective counter reads do not perturb the timings.
        val bytesBefore = allocatedBytes(thread)
        repeat(samples.size) { sink += block() }
        val allocated = allocatedBytes(thread) - bytesBefore
        check(sink >= 0)
        samples.sort()
        return Stat(samples[20], samples[4], samples[36], allocated.toDouble() / samples.size)
    }

    private fun dumpForRust(size: Int, streams: List<Stream>) {
        val dir = File("../rust/target/bench").apply { mkdirs() }
        val array = JsonArray()
        streams.forEach { array.add(JsonObject().apply { add("input", ProviderStreamFixtures.toFixtureInput(it)) }) }
        File(dir, "provider_streams_$size.json").writeText(GsonBuilder().disableHtmlEscaping().create().toJson(array))
    }
}

/** Debrid addon results shaped like Torrentio/Comet/AIOStreams responses for one title. */
object ProviderStreamFixtures {
    private val resolutions = listOf("2160p", "1080p", "720p", "480p", "4K", "UHD")
    private val sources = listOf("BluRay", "BluRay.REMUX", "WEB-DL", "WEBRip", "HDTV", "BDRip", "HDRip", "CAM")
    private val visual = listOf("", "DV", "HDR", "HDR10", "HDR10+", "DV.HDR", "10bit", "SDR", "IMAX")
    private val audio = listOf("DDP5.1", "DD5.1", "TrueHD.Atmos.7.1", "DTS-HD.MA.5.1", "AAC2.0", "Opus", "FLAC", "DTS")
    private val codecs = listOf("x265", "x264", "HEVC", "H.264", "AV1", "XviD")
    private val groups = listOf("FraMeSToR", "NTb", "RARBG", "YIFY", "FLUX", "SPARKS", "TGx", "PSA")
    private val languages = listOf("", "ITA.ENG", "MULTi", "FRENCH", "GERMAN.DL", "Castellano", "Latino", "HINDI")
    private val services = listOf("torbox", "realdebrid", "alldebrid", "premiumize")

    fun addonStreams(size: Int, random: Random): List<Stream> = List(size) { index ->
        val release = listOf(
            "The.Example.Movie.2023", resolutions.random(random), sources.random(random), visual.random(random),
            audio.random(random), codecs.random(random), languages.random(random)
        ).filter { it.isNotEmpty() }.joinToString(".") + "-" + groups.random(random)
        val sizeText = "%.1f GB".format(java.util.Locale.ROOT, random.nextDouble(0.7, 80.0))
        val withParsed = random.nextInt(3) == 0
        ReleaseFactsParityTest.stream(
            name = "[${services.random(random).take(2).uppercase()}+] Torrentio\n${resolutions.random(random)}",
            title = "$release\n👤 ${random.nextInt(1, 900)} 💾 $sizeText ⚙️ ThePirateBay",
            description = if (random.nextBoolean()) "$release.mkv" else null,
            resolve = ReleaseFactsParityTest.resolve(
                title = "The Example Movie",
                torrentName = release,
                filename = "$release.mkv",
                raw = if (!withParsed) null else StreamClientResolveRaw(
                    torrentName = release, filename = "$release.mkv", size = null, folderSize = null,
                    tracker = null, indexer = null, network = null,
                    parsed = ReleaseFactsParityTest.parsed(
                        resolution = resolutions.random(random), quality = sources.random(random),
                        codec = codecs.random(random), hdr = listOf(visual.random(random)).filter { it.isNotEmpty() },
                        audio = listOf(audio.random(random)), channels = listOf("5.1"),
                        languages = if (random.nextBoolean()) listOf("en") else emptyList(),
                        group = groups.random(random), parsedTitle = "The Example Movie"
                    )
                )
            )
        ).copy(infoHash = "%040x".format(index), addonName = "Torrentio")
    }

    /** Inverse of `ReleaseFactsParityTest.streamFrom` for the fields classification reads. */
    fun toFixtureInput(stream: Stream): JsonObject = JsonObject().apply {
        stream.name?.let { addProperty("name", it) }
        stream.title?.let { addProperty("title", it) }
        stream.description?.let { addProperty("description", it) }
        stream.quality?.let { addProperty("quality", it) }
        stream.debridCacheStatus?.cachedName?.let { addProperty("cachedName", it) }
        stream.clientResolve?.let { resolve ->
            add("resolve", JsonObject().apply {
                resolve.title?.let { addProperty("title", it) }
                resolve.torrentName?.let { addProperty("torrentName", it) }
                resolve.filename?.let { addProperty("filename", it) }
                resolve.stream?.raw?.let { raw ->
                    add("raw", JsonObject().apply {
                        raw.torrentName?.let { addProperty("torrentName", it) }
                        raw.filename?.let { addProperty("filename", it) }
                        raw.parsed?.let { p ->
                            add("parsed", JsonObject().apply {
                                p.resolution?.let { addProperty("resolution", it) }
                                p.quality?.let { addProperty("quality", it) }
                                p.codec?.let { addProperty("codec", it) }
                                p.group?.let { addProperty("group", it) }
                                p.parsedTitle?.let { addProperty("parsedTitle", it) }
                                fun list(key: String, values: List<String>?) {
                                    values?.let { v -> add(key, JsonArray().apply { v.forEach(::add) }) }
                                }
                                list("hdr", p.hdr); list("audio", p.audio); list("channels", p.channels); list("languages", p.languages)
                            })
                        }
                    })
                }
            })
        }
    }
}
