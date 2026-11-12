package com.nuvio.tv.core.debrid

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlin.random.Random

/**
 * One addon's stream results for a title, in the shapes the app really receives, for the
 * provider-processing tests and benchmark. Every stream is tagged with what the presentation step
 * must do with it, so tests can check the output against the fixture instead of trusting it.
 */
object ProviderStreamFixtures {
    enum class Kind(val presented: Boolean, val managed: Boolean) {
        /** Torrentio-shaped torrent result the local availability check marked cached. */
        TORRENT_CACHED(presented = true, managed = true),
        /** Comet-shaped direct debrid result with parser metadata. */
        DIRECT_PARSED(presented = true, managed = true),
        /** AIOStreams-shaped direct debrid result: formatted description, no parser metadata. */
        DIRECT_TEXT(presented = true, managed = true),
        /** Synthetic edge cases (empty, unicode, very long, title words that look like tags). */
        EDGE(presented = true, managed = true),
        /** Plain HTTP stream: shown as is, never classified. */
        PASSTHROUGH(presented = true, managed = false),
        /** Torrent the availability check marked not cached: hidden. */
        TORRENT_NOT_CACHED(presented = false, managed = false),
        /** Direct debrid result for a service other than the active resolver: hidden. */
        DIRECT_OTHER_SERVICE(presented = false, managed = false)
    }

    data class Tagged(val stream: Stream, val kind: Kind)

    const val ADDON = "Torrentio"

    /** Torbox is the active resolver; nothing else is customised. */
    val defaultSettings = DebridSettings(enabled = true, torboxApiKey = "key")

    /** A user who sorts, excludes cams, caps sizes and limits results. */
    val customSettings = defaultSettings.copy(
        streamPreferences = DebridStreamPreferences(
            maxResults = 60,
            maxPerResolution = 25,
            sizeMaxGb = 70,
            excludedQualities = listOf(
                DebridStreamQuality.CAM, DebridStreamQuality.TS, DebridStreamQuality.TC, DebridStreamQuality.SCR
            ),
            sortCriteria = listOf(
                DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
                DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC)
            )
        )
    )

    private val titles = listOf(
        "The Example Movie" to 2023, "Dune Part Two" to 2024, "The French Dispatch" to 2021,
        "The Italian Job" to 2003, "Spider-Man Across the Spider-Verse" to 2023, "Oppenheimer" to 2023
    )
    private val resolutions = listOf("2160p", "2160p", "1080p", "1080p", "1080p", "720p", "480p", "4K", "UHD")
    private val sources = listOf("BluRay", "BluRay.REMUX", "WEB-DL", "WEB-DL", "WEBRip", "HDTV", "BDRip", "HDRip", "CAM")
    private val visual = listOf("", "", "DV", "HDR", "HDR10", "HDR10+", "DV.HDR", "10bit", "SDR", "IMAX")
    private val audio = listOf("DDP5.1", "DD5.1", "TrueHD.Atmos.7.1", "DTS-HD.MA.5.1", "AAC2.0", "Opus", "FLAC", "DTS")
    private val codecs = listOf("x265", "x264", "HEVC", "H.264", "AV1", "XviD")
    private val groups = listOf("FraMeSToR", "NTb", "RARBG", "YIFY", "FLUX", "SPARKS", "TGx", "PSA")
    private val languages = listOf("", "", "", "ITA.ENG", "MULTi", "FRENCH", "GERMAN.DL", "Castellano", "Latino", "HINDI")
    private val trackers = listOf("ThePirateBay", "1337x", "TorrentGalaxy", "RARBG", "EZTV")

    /** [size] streams: about 90 % must be presented, the rest hidden, in a deterministic mix. */
    fun addonStreams(size: Int, random: Random): List<Tagged> {
        val edges = edgeCases()
        return List(size) { index ->
            val (title, year) = titles.random(random)
            val release = listOf(
                title.replace(' ', '.'), year.toString(), resolutions.random(random), sources.random(random),
                visual.random(random), audio.random(random), codecs.random(random), languages.random(random)
            ).filter { it.isNotEmpty() }.joinToString(".") + "-" + groups.random(random)
            val gigabytes = random.nextDouble(0.7, 80.0)
            when (val slot = index % 20) {
                in 0..8 -> Tagged(torrent(index, release, gigabytes, random, StreamDebridCacheState.CACHED), Kind.TORRENT_CACHED)
                in 9..13 -> Tagged(directParsed(index, title, year, release, gigabytes, random, "torbox"), Kind.DIRECT_PARSED)
                14, 15 -> Tagged(directText(index, title, release, gigabytes, random), Kind.DIRECT_TEXT)
                16 -> Tagged(edges[(index / 20) % edges.size].copy(infoHash = hash(index)), Kind.EDGE)
                17 -> Tagged(passthrough(index, release), Kind.PASSTHROUGH)
                18 -> Tagged(torrent(index, release, gigabytes, random, StreamDebridCacheState.NOT_CACHED), Kind.TORRENT_NOT_CACHED)
                else -> {
                    check(slot == 19)
                    Tagged(directParsed(index, title, year, release, gigabytes, random, "realdebrid"), Kind.DIRECT_OTHER_SERVICE)
                }
            }
        }
    }

    private fun hash(index: Int) = "%040x".format(index)

    private fun base(index: Int, name: String?, title: String?, description: String? = null) = Stream(
        name = name, title = title, description = description, url = null, ytId = null, infoHash = hash(index),
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = ADDON, addonLogo = null
    )

    private fun torrent(index: Int, release: String, gigabytes: Double, random: Random, state: StreamDebridCacheState): Stream {
        val sizeText = "%.2f GB".format(java.util.Locale.ROOT, gigabytes)
        return base(
            index,
            name = "Torrentio\n${resolutions.random(random)}",
            title = "$release\n$release.mkv\n👤 ${random.nextInt(1, 900)} 💾 $sizeText ⚙️ ${trackers.random(random)}"
        ).copy(
            fileIdx = random.nextInt(0, 4),
            behaviorHints = StreamBehaviorHints(
                notWebReady = null, bingeGroup = "torrentio|${index % 7}", countryWhitelist = null,
                proxyHeaders = null, filename = "$release.mkv"
            ),
            sources = listOf("tracker:udp://tracker.opentrackr.org:1337/announce", "dht:${hash(index)}"),
            debridCacheStatus = StreamDebridCacheStatus(
                providerId = "torbox", providerName = "Torbox", state = state,
                cachedName = "$release.mkv".takeIf { state == StreamDebridCacheState.CACHED && random.nextBoolean() }
            )
        )
    }

    private fun resolve(index: Int, title: String?, release: String?, service: String, raw: StreamClientResolveRaw?) =
        StreamClientResolve(
            type = "debrid", infoHash = hash(index), fileIdx = 0, magnetUri = null, sources = null,
            torrentName = release, filename = release?.let { "$it.mkv" }, mediaType = "movie", mediaId = "tt0000001",
            mediaOnlyId = "tt0000001", title = title, season = null, episode = null, service = service,
            serviceIndex = 0, serviceExtension = null, isCached = true, stream = StreamClientResolveStream(raw)
        )

    private fun directParsed(
        index: Int, title: String, year: Int, release: String, gigabytes: Double, random: Random, service: String
    ): Stream {
        val bytes = (gigabytes * 1024 * 1024 * 1024).toLong()
        val parsed = StreamClientResolveParsed(
            rawTitle = release, parsedTitle = title, year = year, resolution = resolutions.random(random),
            seasons = null, episodes = null, quality = sources.random(random).replace('.', ' '),
            hdr = listOf(visual.random(random)).filter { it.isNotEmpty() }, codec = codecs.random(random),
            audio = listOf(audio.random(random)), channels = listOf("5.1"),
            languages = if (random.nextBoolean()) listOf("en") else emptyList(), group = groups.random(random),
            network = null, edition = null, duration = 7200L, bitDepth = null, extended = random.nextInt(8) == 0,
            theatrical = null, remastered = null, unrated = null
        )
        val raw = StreamClientResolveRaw(
            torrentName = release, filename = "$release.mkv", size = bytes, folderSize = bytes,
            tracker = trackers.random(random), indexer = trackers.random(random), network = null, parsed = parsed
        )
        return base(index, name = "[${service.take(2).uppercase()}⚡] Comet ${parsed.resolution}", title = null, description = "$release.mkv")
            .copy(clientResolve = resolve(index, title, release, service, raw))
    }

    private fun directText(index: Int, title: String, release: String, gigabytes: Double, random: Random): Stream {
        val sizeText = "%.1f GB".format(java.util.Locale.ROOT, gigabytes)
        return base(
            index,
            name = "[TB⚡] AIOStreams ${resolutions.random(random)}",
            title = null,
            description = "🎬 $title\n🎥 ${sources.random(random)} 🎞️ ${codecs.random(random)}\n" +
                "🎧 ${audio.random(random)}\n📦 $sizeText 🔍 ${trackers.random(random)}\n📄 $release.mkv"
        ).copy(clientResolve = resolve(index, title, release, "torbox", raw = null))
    }

    private fun passthrough(index: Int, release: String) = base(index, name = "HTTP 1080p", title = release)
        .copy(url = "https://cdn.example.com/$index/$release.mkv", infoHash = null)

    /** Managed streams with unusual texts; each must survive default processing. */
    private fun edgeCases(): List<Stream> {
        fun direct(name: String?, title: String?, description: String?, resolveTitle: String?, release: String?) =
            base(0, name, title, description).copy(clientResolve = resolve(0, resolveTitle, release, "torbox", raw = null))
        return listOf(
            direct(name = null, title = null, description = null, resolveTitle = null, release = null),
            direct("", "", "", "", ""),
            direct("İstanbul ıſK 中文 🎬", "Σίσυφος.2160p.HDR10+.ςς-ΓΡ", "1,4 GB", "Σίσυφος", null),
            direct("TB", "The.French.Dispatch.2021.FRENCH.1080p.BluRay.x264-GRP", null, "The French Dispatch", null),
            direct("TB", "The.Italian.Job.2003.720p.HDTV.AC3", "no size here", "The Italian Job", "The.Italian.Job.2003"),
            direct("TB", "Show.S01-S03.COMPLETE.1080p.WEB-DL.DDP5.1.H.264-NTb 123.4 MB", null, "Show", null),
            direct("TB 4k", "x".repeat(40) + ".2160p.DV." + "long.release.name.".repeat(110) + "x265-GRP", null, "x", null),
            direct("TB", "dv hdr hlg 10bit 3d imax ai sdr h-ou h-sbs atmos dd+ dts:x dts-hd ma truehd 7.1 6.1 2.0", null, null, null)
        )
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
