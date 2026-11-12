package com.nuvio.tv.core.debrid

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.debrid.ProviderStreamFixtures.Kind
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.Stream
import io.mockk.mockk
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Pins what provider-result processing (`DebridStreamPresentation.apply`) produces for the
 * addon-shaped fixtures the benchmark times: which streams survive, in which order, how they are
 * formatted, and that the Rust-backed path gives the same result as Kotlin.
 */
class ProviderProcessingPipelineTest {
    private val presentation = DebridStreamPresentation(
        dataStore = mockk<DebridSettingsDataStore>(relaxed = true),
        streamBadgeSettingsDataStore = mockk<StreamBadgeSettingsDataStore>(relaxed = true),
        formatter = DebridStreamFormatter(DebridStreamTemplateEngine())
    )

    private fun fixtures(size: Int) = ProviderStreamFixtures.addonStreams(size, Random(size))

    private fun present(tagged: List<ProviderStreamFixtures.Tagged>, settings: DebridSettings): List<Stream> =
        presentation.apply(
            listOf(AddonStreams(ProviderStreamFixtures.ADDON, null, tagged.map { it.stream })),
            settings,
            includeBadgeMatches = false
        ).single().streams

    private inline fun <T> withNative(enabled: Boolean, block: () -> T): T {
        val previous = ReleaseFactsClassifier.nativeEnabled
        ReleaseFactsClassifier.nativeEnabled = enabled
        try {
            return block()
        } finally {
            ReleaseFactsClassifier.nativeEnabled = previous
        }
    }

    @Test
    fun `fixtures contain every addon shape and mostly presentable streams`() {
        for (size in SIZES) {
            val kinds = fixtures(size).groupingBy { it.kind }.eachCount()
            Kind.entries.forEach { kind -> assertTrue("$size streams: no $kind", (kinds[kind] ?: 0) > 0) }
            val presented = fixtures(size).count { it.kind.presented }
            assertTrue("$size streams: only $presented presentable", presented >= size * 85 / 100)
            assertTrue("$size streams: nothing hidden", presented < size)
        }
    }

    @Test
    fun `default settings present every cached debrid stream in arrival order`() = withNative(false) {
        for (size in SIZES) {
            val tagged = fixtures(size)
            val output = present(tagged, ProviderStreamFixtures.defaultSettings)

            // Managed debrid streams first, in arrival order, then the untouched passthrough streams.
            val expected = tagged.filter { it.kind.managed } + tagged.filter { it.kind == Kind.PASSTHROUGH }
            assertEquals(expected.map { it.stream.identity() }, output.map { it.identity() })
            assertTrue(output.size >= size * 85 / 100)

            expected.zip(output).forEach { (source, presented) ->
                if (source.kind == Kind.PASSTHROUGH) {
                    assertEquals(source.stream, presented)
                } else {
                    assertTrue("name of ${source.stream}: ${presented.name}", presented.name.orEmpty().endsWith("Instant"))
                    assertTrue("description of ${source.stream}", presented.description.orEmpty().contains("Ready (TB)"))
                }
            }
            // Formatting is not a no-op: sizes and release details reach the description.
            val described = output.count { it.description.orEmpty().contains(" GB") }
            assertTrue("$size streams: only $described descriptions carry a size", described >= size * 70 / 100)
        }
    }

    @Test
    fun `custom preferences filter sort and limit`() = withNative(false) {
        val settings = ProviderStreamFixtures.customSettings
        val preferences = settings.streamPreferences
        for (size in SIZES) {
            val tagged = fixtures(size)
            val bySource = tagged.associateBy { it.stream.identity() }
            val output = present(tagged, settings)
            val managed = output.filter { bySource.getValue(it.identity()).kind.managed }
            val facts = managed.map { DirectDebridStreamFilter.facts(bySource.getValue(it.identity()).stream, settings) }

            assertTrue("$size streams: ${managed.size} managed results", managed.size in 10..preferences.maxResults)
            assertTrue(facts.groupingBy { it.resolution }.eachCount().values.all { it <= preferences.maxPerResolution })
            assertTrue(facts.none { it.quality in preferences.excludedQualities })
            assertTrue(facts.none { (it.size ?: 0L) > preferences.sizeMaxGb * 1_000_000_000L })
            assertTrue("sizes must be known for most streams", facts.count { it.size != null } >= facts.size * 8 / 10)
            // Resolution, then quality, then size, all descending by preference.
            val keys = facts.map { Triple(it.resolutionRank, it.qualityRank, -(it.size ?: 0L)) }
            assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), keys)
            if (size >= 150) {
                assertEquals(preferences.maxResults, managed.size)
                assertTrue(facts.map { it.resolution }.distinct().size >= 2)
            }
            assertFalse(facts.all { it.quality == DebridStreamQuality.UNKNOWN })
        }
    }

    @Test
    fun `rust backed pipeline matches kotlin`() {
        assumeTrue("Rust core disabled for this build", System.getProperty("nuvio.core.hostLib") != null)
        assertTrue("Rust core failed to load", NativeReleaseFacts.isAvailable)
        for (size in SIZES) {
            val tagged = fixtures(size)
            val streams = tagged.map { it.stream }
            assertEquals(streams.map(ReleaseFactsClassifier::classifyKotlin), NativeReleaseFacts.classify(streams))
            for (settings in listOf(ProviderStreamFixtures.defaultSettings, ProviderStreamFixtures.customSettings)) {
                val kotlin = withNative(false) { present(tagged, settings) }
                val rust = withNative(true) { present(tagged, settings) }
                assertTrue(kotlin.isNotEmpty())
                assertEquals(kotlin, rust)
            }
        }
    }

    /**
     * Exact output for 150 streams under both settings, recorded from the implementation before
     * any optimisation (regenerate with `-Pnuvio.updateFixtures=true` only for intended changes).
     */
    @Test
    fun `presentation output matches the recorded golden file`() = withNative(false) {
        val actual = JsonObject()
        for ((label, settings) in listOf("default" to ProviderStreamFixtures.defaultSettings, "custom" to ProviderStreamFixtures.customSettings)) {
            actual.add(label, JsonArray().apply {
                present(fixtures(150), settings).forEach { stream ->
                    add(JsonObject().apply {
                        addProperty("id", stream.identity())
                        addProperty("name", stream.name)
                        addProperty("description", stream.description)
                    })
                }
            })
        }
        if (System.getProperty("nuvio.updateFixtures") == "true") {
            goldenFile.parentFile.mkdirs()
            goldenFile.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(actual) + "\n")
        }
        assertTrue("missing ${goldenFile.path}", goldenFile.isFile)
        val expected = JsonParser.parseString(goldenFile.readText()).asJsonObject
        assertTrue(expected.getAsJsonArray("default").size() > 100)
        assertEquals(expected, actual)
    }

    private fun Stream.identity(): String = infoHash ?: url.orEmpty()

    private companion object {
        val SIZES = listOf(50, 150, 400)
        val goldenFile = File("src/test/resources/debrid/provider_presentation_golden.json")
    }
}
