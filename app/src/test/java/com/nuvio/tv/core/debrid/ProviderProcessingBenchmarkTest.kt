package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import io.mockk.mockk
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host JVM benchmark for provider-result processing: the debrid presentation step
 * (classify → filter → sort → limit → format) that `StreamRepositoryImpl.mergePresentedResult`
 * runs on every addon arrival. Run with
 * `./gradlew :app:testFullDebugUnitTest --tests '*ProviderProcessingBenchmarkTest' -Pnuvio.bench=true`.
 */
class ProviderProcessingBenchmarkTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = formatter
    )
    private val settings = DebridSettings(enabled = true, torboxApiKey = "key")

    @Test
    fun `benchmark debrid presentation phases`() {
        assumeTrue(System.getProperty("nuvio.bench") == "true")
        val native = System.getProperty("nuvio.core.hostLib") != null && NativeReleaseFacts.isAvailable
        val previous = ReleaseFactsClassifier.nativeEnabled
        try {
            println("streams,classify_kotlin_us,classify_rust_us,filter_sort_limit_us,format_us,apply_kotlin_us,apply_rust_us")
            for (size in listOf(50, 150, 400)) {
                val streams = ProviderStreamFixtures.addonStreams(size, Random(size))
                val group = listOf(AddonStreams(addonName = "Torrentio", addonLogo = null, streams = streams))

                val classifyKotlin = measure { streams.count { ReleaseFactsClassifier.classifyKotlin(it).encode.ordinal >= 0 } }
                val classifyRust = if (native) measure { NativeReleaseFacts.classify(streams).size } else Double.NaN

                val facts = ReleaseFactsClassifier.classifyAll(streams)
                val withFacts = DirectDebridStreamFilter.applyPreferencesWithFacts(streams, settings)
                val format = measure { withFacts.count { (stream, f) -> formatter.format(stream, settings, emptyList(), f).name != null } }
                ReleaseFactsClassifier.nativeEnabled = false
                val applyKotlin = measure { presentation.apply(group, settings, includeBadgeMatches = false).single().streams.size }
                val filterSortLimit = (measure { DirectDebridStreamFilter.applyPreferencesWithFacts(streams, settings).size } - classifyKotlin)
                    .coerceAtLeast(0.0)
                ReleaseFactsClassifier.nativeEnabled = true
                val applyRust = if (native) measure { presentation.apply(group, settings, includeBadgeMatches = false).single().streams.size } else Double.NaN
                check(facts.size == streams.size)
                println(
                    "%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f".format(
                        size, classifyKotlin / 1e3, classifyRust / 1e3, filterSortLimit / 1e3, format / 1e3,
                        applyKotlin / 1e3, applyRust / 1e3
                    )
                )
            }
        } finally {
            ReleaseFactsClassifier.nativeEnabled = previous
        }
    }

    /** Median wall time in ns of one call after a 1.5 s warm-up. */
    private fun measure(block: () -> Int): Double {
        var sink = 0
        val deadline = System.nanoTime() + 1_500_000_000L
        while (System.nanoTime() < deadline) sink += block()
        val samples = DoubleArray(31) {
            val start = System.nanoTime()
            sink += block()
            (System.nanoTime() - start).toDouble()
        }
        check(sink >= 0)
        samples.sort()
        return samples[samples.size / 2]
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
        val size = "%.1f GB".format(random.nextDouble(0.7, 80.0))
        val withParsed = random.nextInt(3) == 0
        ReleaseFactsParityTest.stream(
            name = "[${services.random(random).take(2).uppercase()}+] Torrentio\n${resolutions.random(random)}",
            title = "$release\n👤 ${random.nextInt(1, 900)} 💾 $size ⚙️ ThePirateBay",
            description = if (random.nextBoolean()) "$release.mkv" else null,
            resolve = ReleaseFactsParityTest.resolve(
                title = "The Example Movie",
                torrentName = release,
                filename = "$release.mkv",
                raw = if (!withParsed) null else com.nuvio.tv.domain.model.StreamClientResolveRaw(
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
}
