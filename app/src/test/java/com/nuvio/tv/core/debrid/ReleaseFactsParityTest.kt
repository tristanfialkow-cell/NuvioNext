package com.nuvio.tv.core.debrid

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.nativecore.NuvioCore
import com.nuvio.tv.core.nativecore.NuvioCoreNative
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Kotlin <-> Rust parity for release classification. Fixtures are shared with
 * `rust/nuvio-core/tests/fixtures.rs`; expectations come from the Kotlin reference
 * (regenerate with `-Pnuvio.updateFixtures=true`).
 */
class ReleaseFactsParityTest {

    @Test
    fun `kotlin reference matches shared fixtures`() {
        val fixtures = loadFixtures()
        if (System.getProperty("nuvio.updateFixtures") == "true") {
            fixtures.forEach { it.asJsonObject.add("expected", toJson(ReleaseFactsClassifier.classifyKotlin(streamFrom(it)))) }
            fixtureFile.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(fixtures) + "\n")
        }
        fixtures.forEach { fixture ->
            val expected = fixture.asJsonObject.get("expected")
            assertTrue("missing expectation for ${name(fixture)}", expected != null && !expected.isJsonNull)
            assertEquals(name(fixture), expected, toJson(ReleaseFactsClassifier.classifyKotlin(streamFrom(fixture))))
        }
    }

    @Test
    fun `native library loads and its tables match`() {
        requireNative()
        val status = NuvioCore.status
        assertTrue("$status", status is NuvioCore.Status.Available)
        assertEquals(NuvioCore.EXPECTED_ABI_VERSION, (status as NuvioCore.Status.Available).abiVersion)
        assertTrue(NativeReleaseFacts.isAvailable)
    }

    @Test
    fun `native classifier matches shared fixtures`() {
        requireNative()
        val fixtures = loadFixtures().toList()
        val actual = NativeReleaseFacts.classify(fixtures.map(::streamFrom))
        fixtures.forEachIndexed { index, fixture ->
            assertEquals(name(fixture), fixture.asJsonObject.get("expected"), toJson(actual[index]))
        }
    }

    @Test
    fun `native classifier matches kotlin on randomized streams`() {
        requireNative()
        val random = Random(0x5EED)
        repeat(20) { batch ->
            val streams = List(500) { randomStream(random) }
            val native = NativeReleaseFacts.classify(streams)
            streams.forEachIndexed { index, stream ->
                assertEquals("batch $batch stream $index: $stream", ReleaseFactsClassifier.classifyKotlin(stream), native[index])
            }
        }
    }

    @Test
    fun `classifier entry point uses native when enabled and handles empty input`() {
        requireNative()
        val previous = ReleaseFactsClassifier.nativeEnabled
        try {
            ReleaseFactsClassifier.nativeEnabled = true
            val streams = loadFixtures().map(::streamFrom)
            assertEquals(streams.map(ReleaseFactsClassifier::classifyKotlin), ReleaseFactsClassifier.classifyAll(streams))
            assertEquals(emptyList<ReleaseTextFacts>(), ReleaseFactsClassifier.classifyAll(emptyList()))
        } finally {
            ReleaseFactsClassifier.nativeEnabled = previous
        }
    }

    @Test
    fun `native rejects malformed batches with exceptions instead of crashing`() {
        requireNative()
        // Layout announces one text but the strings array is too short.
        assertThrows(IllegalArgumentException::class.java) {
            NuvioCoreNative.nativeClassifyReleases(intArrayOf(1, 0, -1, -1, -1, -1), arrayOfNulls(3), IntArray(8), IntArray(16), arrayOfNulls(1))
        }
        // Output array of the wrong size.
        assertThrows(IllegalArgumentException::class.java) {
            NuvioCoreNative.nativeClassifyReleases(intArrayOf(0, 0, -1, -1, -1, -1), arrayOfNulls(7), IntArray(3), IntArray(16), arrayOfNulls(1))
        }
        // Null list element.
        assertThrows(IllegalArgumentException::class.java) {
            NuvioCoreNative.nativeClassifyReleases(intArrayOf(1, 0, -1, -1, -1, -1), arrayOfNulls(8), IntArray(8), IntArray(16), arrayOfNulls(1))
        }
        assertThrows(IllegalArgumentException::class.java) { NuvioCoreNative.nativeEnumTable(42) }
    }

    private fun requireNative() {
        // The Gradle build sets this whenever the Rust core is enabled (-Pnuvio.rustCore, default true).
        assumeTrue("Rust core disabled for this build", System.getProperty("nuvio.core.hostLib") != null)
    }

    companion object {
        val fixtureFile = File("../rust/nuvio-core/tests/fixtures/release_facts.json")

        fun loadFixtures(): JsonArray = JsonParser.parseString(fixtureFile.readText()).asJsonArray

        private fun name(fixture: JsonElement) = fixture.asJsonObject.get("name").asString

        private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
        private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeUnless { it.isJsonNull }?.asJsonObject
        private fun JsonObject.list(key: String): List<String>? =
            get(key)?.takeUnless { it.isJsonNull }?.asJsonArray?.map { it.asString }

        fun streamFrom(fixture: JsonElement): Stream {
            val input = fixture.asJsonObject.obj("input") ?: JsonObject()
            val resolve = input.obj("resolve")
            val raw = resolve?.obj("raw")
            val parsed = raw?.obj("parsed")
            return stream(
                name = input.string("name"),
                title = input.string("title"),
                description = input.string("description"),
                quality = input.string("quality"),
                cachedName = input.string("cachedName"),
                resolve = resolve?.let {
                    resolve(
                        title = it.string("title"),
                        torrentName = it.string("torrentName"),
                        filename = it.string("filename"),
                        raw = raw?.let { r ->
                            StreamClientResolveRaw(
                                torrentName = r.string("torrentName"), filename = r.string("filename"),
                                size = null, folderSize = null, tracker = null, indexer = null, network = null,
                                parsed = parsed?.let { p ->
                                    parsed(
                                        resolution = p.string("resolution"), quality = p.string("quality"),
                                        codec = p.string("codec"), hdr = p.list("hdr"), audio = p.list("audio"),
                                        channels = p.list("channels"), languages = p.list("languages"),
                                        group = p.string("group"), parsedTitle = p.string("parsedTitle")
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }

        fun toJson(facts: ReleaseTextFacts): JsonObject = JsonObject().apply {
            addProperty("resolution", facts.resolution.name)
            addProperty("quality", facts.quality.name)
            add("visualTags", names(facts.visualTags))
            add("audioTags", names(facts.audioTags))
            add("audioChannels", names(facts.audioChannels))
            addProperty("encode", facts.encode.name)
            add("languages", names(facts.languages))
            addProperty("releaseGroup", facts.releaseGroup)
        }

        private fun names(values: List<Enum<*>>) = JsonArray().apply { values.forEach { add(it.name) } }

        fun stream(
            name: String? = null,
            title: String? = null,
            description: String? = null,
            quality: String? = null,
            cachedName: String? = null,
            resolve: StreamClientResolve? = null
        ) = Stream(
            name = name, title = title, description = description, url = null, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "test", addonLogo = null,
            quality = quality, clientResolve = resolve,
            debridCacheStatus = cachedName?.let {
                StreamDebridCacheStatus("rd", "Real-Debrid", StreamDebridCacheState.CACHED, cachedName = it)
            }
        )

        fun resolve(
            title: String? = null,
            torrentName: String? = null,
            filename: String? = null,
            raw: StreamClientResolveRaw? = null
        ) = StreamClientResolve(
            type = "debrid", infoHash = null, fileIdx = null, magnetUri = null, sources = null,
            torrentName = torrentName, filename = filename, mediaType = null, mediaId = null, mediaOnlyId = null,
            title = title, season = null, episode = null, service = "realdebrid", serviceIndex = null,
            serviceExtension = null, isCached = true, stream = StreamClientResolveStream(raw)
        )

        fun parsed(
            resolution: String? = null, quality: String? = null, codec: String? = null,
            hdr: List<String>? = null, audio: List<String>? = null, channels: List<String>? = null,
            languages: List<String>? = null, group: String? = null, parsedTitle: String? = null
        ) = StreamClientResolveParsed(
            rawTitle = null, parsedTitle = parsedTitle, year = null, resolution = resolution, seasons = null,
            episodes = null, quality = quality, hdr = hdr, codec = codec, audio = audio, channels = channels,
            languages = languages, group = group, network = null, edition = null, duration = null,
            bitDepth = null, extended = null, theatrical = null, remastered = null, unrated = null
        )

        // Release-name vocabulary plus separators and characters with tricky case/whitespace rules.
        private val vocabulary = listOf(
            "2160p", "2160", "4k", "uhd", "1440p", "2k", "1080p", "fhd", "720p", "hd", "576p", "480p", "sd", "360p",
            "remux", "bluray", "blu-ray", "bdrip", "brrip", "web-dl", "webdl", "webrip", "web-rip", "hdrip", "hd-rip",
            "hcrip", "dvdrip", "hdtv", "cam", "ts", "tc", "scr", "dv", "dovi", "dolby", "vision", "dolbyvision", "hdr",
            "hdr10", "hdr10+", "hdr10plus", "hlg", "10bit", "10", "bit", "3d", "imax", "ai", "sdr", "h-ou", "h-sbs",
            "atmos", "dd+", "ddp", "dd", "ac3", "digital", "plus", "dts:x", "dtsx", "dts-hd", "ma", "dtshd", "dts-es",
            "dtses", "dts", "truehd", "true", "opus", "flac", "aac", "7.1", "6.1", "5.1", "6ch", "2.0", "av1", "hevc",
            "h265", "x265", "avc", "h264", "x264", "xvid", "divx", "en", "eng", "english", "ita", "italian", "es",
            "spa", "castellano", "fr", "french", "truefrench", "de", "ger", "pt", "pl", "cs", "la", "latino", "ja",
            "jpn", "ko", "zh", "chinese", "he", "multi", "unknown", "the", "movie", "job", "dispatch", "-grp", "-ntb",
            "-a", ".mkv", "İ", "ı", "ſ", "\u212A", "Σ", "ς", "é", "中文", "🎬", "GROUP", "MiXeD", "1", "2023"
        )
        private val separators = listOf(" ", ".", "-", "_", "+", ":", "\n", "\r\n", "\r", "\u0085", "\u2028", "\u001c", "\u00a0", "", "[", "]", "/")

        private fun randomText(random: Random): String = buildString {
            repeat(random.nextInt(0, 14)) {
                append(vocabulary.random(random).let { if (random.nextInt(4) == 0) it.uppercase() else it })
                append(separators.random(random))
            }
        }

        private fun <T> Random.maybe(value: () -> T): T? = if (nextInt(3) == 0) null else value()

        fun randomStream(random: Random): Stream {
            val withParsed = random.nextBoolean()
            val parsed = if (!withParsed) null else parsed(
                resolution = random.maybe { randomText(random) }, quality = random.maybe { randomText(random) },
                codec = random.maybe { randomText(random) },
                hdr = random.maybe { List(random.nextInt(0, 3)) { randomText(random) } },
                audio = random.maybe { List(random.nextInt(0, 3)) { randomText(random) } },
                channels = random.maybe { List(random.nextInt(0, 3)) { randomText(random) } },
                languages = random.maybe { List(random.nextInt(0, 3)) { listOf("en", " English ", "ita", "xx", "Multi", "\u001cfr\u0085", vocabulary.random(random)).random(random) } },
                group = random.maybe { listOf("", "  ", "FLUX", randomText(random)).random(random) },
                parsedTitle = random.maybe { randomText(random) }
            )
            val hasResolve = withParsed || random.nextBoolean()
            return stream(
                name = random.maybe { randomText(random) }, title = random.maybe { randomText(random) },
                description = random.maybe { randomText(random) }, quality = random.maybe { randomText(random) },
                cachedName = random.maybe { randomText(random) },
                resolve = if (!hasResolve) null else resolve(
                    title = random.maybe { listOf("The French Dispatch", "The Italian Job", "Movie", randomText(random)).random(random) },
                    torrentName = random.maybe { randomText(random) }, filename = random.maybe { randomText(random) },
                    raw = if (parsed == null && random.nextBoolean()) null else StreamClientResolveRaw(
                        torrentName = random.maybe { randomText(random) }, filename = random.maybe { randomText(random) },
                        size = null, folderSize = null, tracker = null, indexer = null, network = null, parsed = parsed
                    )
                )
            )
        }

    }
}
