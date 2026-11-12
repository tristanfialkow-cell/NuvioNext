package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.ConcurrentHashMap

/** What a stream's release texts say about it, before any user preferences are applied. */
data class ReleaseTextFacts(
    val resolution: DebridStreamResolution,
    val quality: DebridStreamQuality,
    val visualTags: List<DebridStreamVisualTag>,
    val audioTags: List<DebridStreamAudioTag>,
    val audioChannels: List<DebridStreamAudioChannel>,
    val encode: DebridStreamEncode,
    val languages: List<DebridStreamLanguage>,
    val releaseGroup: String
)

/**
 * Classifies stream release names (resolution, source, HDR/audio tags, codec, languages, group).
 *
 * [classifyKotlin] is the reference implementation. When [nativeEnabled] is set and the Rust core
 * (`rust/nuvio-core`) loaded and passed its table check, [classifyAll] uses it instead; any native
 * failure falls back to Kotlin for that batch. Both are checked against the same fixtures.
 */
object ReleaseFactsClassifier {
    private const val TAG = "ReleaseFacts"

    @Volatile
    var nativeEnabled: Boolean = com.nuvio.tv.BuildConfig.RUST_RELEASE_FACTS_ENABLED

    fun classifyAll(streams: List<Stream>): List<ReleaseTextFacts> {
        if (streams.isEmpty()) return emptyList()
        if (nativeEnabled && NativeReleaseFacts.isAvailable) {
            try {
                return NativeReleaseFacts.classify(streams)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Native release classification failed; using Kotlin", error)
            }
        }
        return streams.map(::classifyKotlin)
    }

    private val tokenRegexCache = ConcurrentHashMap<String, Regex>()
    private val dolbyVisionRegex = Regex("(^|[^a-z0-9])(dv|dovi|dolby[ ._-]?vision)([^a-z0-9]|$)")
    private val hdrRegex = Regex("(^|[^a-z0-9])(hdr|hdr10|hdr10plus|hdr10\\+|hlg)([^a-z0-9]|$)")
    private val releaseGroupRegex = Regex("-([a-z0-9][a-z0-9._]{1,24})($|\\.)", RegexOption.IGNORE_CASE)
    private val nonAlphanumericRegex = Regex("[^a-z0-9]")
    private val nonAlphanumericOrPlusRegex = Regex("[^a-z0-9+]")
    private val nonAlphanumericRunRegex = Regex("[^a-z0-9]+")
    private val resolutionTokens = listOf(
        resolutionRegex("2160p?", "4k", "uhd") to DebridStreamResolution.P2160,
        resolutionRegex("1440p?", "2k") to DebridStreamResolution.P1440,
        resolutionRegex("1080p?", "fhd") to DebridStreamResolution.P1080,
        resolutionRegex("720p?", "hd") to DebridStreamResolution.P720,
        resolutionRegex("576p?") to DebridStreamResolution.P576,
        resolutionRegex("480p?", "sd") to DebridStreamResolution.P480,
        resolutionRegex("360p?") to DebridStreamResolution.P360
    )

    // Words release names use for each language besides its code. Short words that are common in
    // titles ("por", "chi", "fra") are left out so a title is not read as a language.
    internal val languageAliases = mapOf(
        DebridStreamLanguage.EN to listOf("eng", "english"),
        DebridStreamLanguage.HI to listOf("hin", "hindi"),
        DebridStreamLanguage.IT to listOf("ita", "italian", "italiano"),
        DebridStreamLanguage.ES to listOf("spa", "esp", "spanish", "espanol", "castellano"),
        DebridStreamLanguage.FR to listOf("fre", "french", "truefrench"),
        DebridStreamLanguage.DE to listOf("ger", "deu", "german", "deutsch"),
        DebridStreamLanguage.PT to listOf("portuguese", "portugues"),
        DebridStreamLanguage.PL to listOf("pol", "polish"),
        DebridStreamLanguage.CS to listOf("cze", "czech"),
        DebridStreamLanguage.LA to listOf("latino"),
        DebridStreamLanguage.JA to listOf("jpn", "japanese"),
        DebridStreamLanguage.KO to listOf("kor", "korean"),
        DebridStreamLanguage.ZH to listOf("chinese"),
        DebridStreamLanguage.HE to listOf("heb", "hebrew")
    )
    private val languageRegexes = DebridStreamLanguage.entries.map { language ->
        language to languageRegex(listOf(language.code) + languageAliases[language].orEmpty())
    }

    fun classifyKotlin(stream: Stream): ReleaseTextFacts {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val searchText = streamSearchText(stream)
        val languages = parsed?.languages.orEmpty().mapNotNull { languageFor(it) }.ifEmpty {
            val languageText = languageSearchText(stream)
            languageRegexes.filter { (_, regex) -> regex.containsMatchIn(languageText) }.map { it.first }
        }
        return ReleaseTextFacts(
            resolution = streamResolution(parsed?.resolution, parsed?.quality, stream.quality, searchText),
            quality = streamQuality(parsed?.quality, searchText),
            visualTags = streamVisualTags(parsed?.hdr.orEmpty(), searchText),
            audioTags = streamAudioTags(parsed?.audio.orEmpty(), searchText),
            audioChannels = streamAudioChannels(parsed?.channels.orEmpty(), searchText),
            encode = streamEncode(parsed?.codec, searchText),
            languages = languages,
            releaseGroup = parsed?.group?.takeIf { it.isNotBlank() } ?: releaseGroupFromText(searchText)
        )
    }

    private fun streamResolution(vararg values: String?): DebridStreamResolution {
        return values.firstNotNullOfOrNull { resolutionValue(it) } ?: DebridStreamResolution.UNKNOWN
    }

    private fun resolutionValue(value: String?): DebridStreamResolution? {
        val normalized = value?.lowercase().orEmpty()
        return resolutionTokens.firstOrNull { (regex, _) -> regex.containsMatchIn(normalized) }?.second
    }

    private fun streamQuality(parsedQuality: String?, searchText: String): DebridStreamQuality {
        val text = listOfNotNull(parsedQuality, searchText).joinToString(" ").lowercase()
        return when {
            text.contains("remux") -> DebridStreamQuality.BLURAY_REMUX
            text.contains("blu-ray") || text.contains("bluray") || text.contains("bdrip") || text.contains("brrip") -> DebridStreamQuality.BLURAY
            text.contains("web-dl") || text.contains("webdl") -> DebridStreamQuality.WEB_DL
            text.contains("webrip") || text.contains("web-rip") -> DebridStreamQuality.WEBRIP
            text.contains("hdrip") -> DebridStreamQuality.HDRIP
            text.contains("hd-rip") || text.contains("hcrip") -> DebridStreamQuality.HD_RIP
            text.contains("dvdrip") -> DebridStreamQuality.DVDRIP
            text.contains("hdtv") -> DebridStreamQuality.HDTV
            text.hasToken("cam") -> DebridStreamQuality.CAM
            text.hasToken("ts") -> DebridStreamQuality.TS
            text.hasToken("tc") -> DebridStreamQuality.TC
            text.hasToken("scr") -> DebridStreamQuality.SCR
            else -> DebridStreamQuality.UNKNOWN
        }
    }

    private fun streamVisualTags(parsedHdr: List<String>, searchText: String): List<DebridStreamVisualTag> {
        val text = (parsedHdr + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamVisualTag>()
        val hasDv = parsedHdr.any { it.isDolbyVisionToken() } || dolbyVisionRegex.containsMatchIn(searchText)
        val hasHdr = parsedHdr.any { it.isHdrToken() } || hdrRegex.containsMatchIn(searchText)
        if (hasDv && hasHdr) tags += DebridStreamVisualTag.HDR_DV
        if (hasDv && !hasHdr) tags += DebridStreamVisualTag.DV_ONLY
        if (hasHdr && !hasDv) tags += DebridStreamVisualTag.HDR_ONLY
        if (text.contains("hdr10+") || text.contains("hdr10plus")) tags += DebridStreamVisualTag.HDR10_PLUS
        if (text.contains("hdr10")) tags += DebridStreamVisualTag.HDR10
        if (hasDv) tags += DebridStreamVisualTag.DV
        if (hasHdr) tags += DebridStreamVisualTag.HDR
        if (text.hasToken("hlg")) tags += DebridStreamVisualTag.HLG
        if (text.contains("10bit") || text.contains("10 bit")) tags += DebridStreamVisualTag.TEN_BIT
        if (text.hasToken("3d")) tags += DebridStreamVisualTag.THREE_D
        if (text.hasToken("imax")) tags += DebridStreamVisualTag.IMAX
        if (text.hasToken("ai")) tags += DebridStreamVisualTag.AI
        if (text.hasToken("sdr")) tags += DebridStreamVisualTag.SDR
        if (text.contains("h-ou")) tags += DebridStreamVisualTag.H_OU
        if (text.contains("h-sbs")) tags += DebridStreamVisualTag.H_SBS
        return tags.distinct().ifEmpty { listOf(DebridStreamVisualTag.UNKNOWN) }
    }

    private fun streamAudioTags(parsedAudio: List<String>, searchText: String): List<DebridStreamAudioTag> {
        val text = (parsedAudio + searchText).joinToString(" ").lowercase()
        val tags = mutableListOf<DebridStreamAudioTag>()
        if (text.hasToken("atmos")) tags += DebridStreamAudioTag.ATMOS
        if (text.contains("dd+") || text.contains("ddp") || text.contains("dolby digital plus")) tags += DebridStreamAudioTag.DD_PLUS
        if (text.hasToken("dd") || text.contains("ac3") || text.contains("dolby digital")) tags += DebridStreamAudioTag.DD
        if (text.contains("dts:x") || text.contains("dtsx")) tags += DebridStreamAudioTag.DTS_X
        if (text.contains("dts-hd ma") || text.contains("dtshd ma")) tags += DebridStreamAudioTag.DTS_HD_MA
        if (text.contains("dts-hd") || text.contains("dtshd")) tags += DebridStreamAudioTag.DTS_HD
        if (text.contains("dts-es") || text.contains("dtses")) tags += DebridStreamAudioTag.DTS_ES
        if (text.hasToken("dts")) tags += DebridStreamAudioTag.DTS
        if (text.contains("truehd") || text.contains("true hd")) tags += DebridStreamAudioTag.TRUEHD
        if (text.hasToken("opus")) tags += DebridStreamAudioTag.OPUS
        if (text.hasToken("flac")) tags += DebridStreamAudioTag.FLAC
        if (text.hasToken("aac")) tags += DebridStreamAudioTag.AAC
        return tags.distinct().ifEmpty { listOf(DebridStreamAudioTag.UNKNOWN) }
    }

    private fun streamAudioChannels(parsedChannels: List<String>, searchText: String): List<DebridStreamAudioChannel> {
        val text = (parsedChannels + searchText).joinToString(" ").lowercase()
        val channels = mutableListOf<DebridStreamAudioChannel>()
        if (text.hasToken("7.1")) channels += DebridStreamAudioChannel.CH_7_1
        if (text.hasToken("6.1")) channels += DebridStreamAudioChannel.CH_6_1
        if (text.hasToken("5.1") || text.hasToken("6ch")) channels += DebridStreamAudioChannel.CH_5_1
        if (text.hasToken("2.0")) channels += DebridStreamAudioChannel.CH_2_0
        return channels.distinct().ifEmpty { listOf(DebridStreamAudioChannel.UNKNOWN) }
    }

    private fun streamEncode(parsedCodec: String?, searchText: String): DebridStreamEncode {
        val text = listOfNotNull(parsedCodec, searchText).joinToString(" ").lowercase()
        return when {
            text.hasToken("av1") -> DebridStreamEncode.AV1
            text.hasToken("hevc") || text.hasToken("h265") || text.hasToken("x265") -> DebridStreamEncode.HEVC
            text.hasToken("avc") || text.hasToken("h264") || text.hasToken("x264") -> DebridStreamEncode.AVC
            text.hasToken("xvid") -> DebridStreamEncode.XVID
            text.hasToken("divx") -> DebridStreamEncode.DIVX
            else -> DebridStreamEncode.UNKNOWN
        }
    }

    private fun languageFor(value: String): DebridStreamLanguage? {
        val normalized = value.trim().lowercase()
        return DebridStreamLanguage.entries.firstOrNull {
            normalized == it.code ||
                normalized == it.label.lowercase() ||
                normalized in languageAliases[it].orEmpty()
        }
    }

    private fun releaseGroupFromText(text: String): String {
        return releaseGroupRegex
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun resolutionRegex(vararg tokens: String): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|\$)")

    private fun languageRegex(tokens: List<String>): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|") { Regex.escape(it) }})([^a-z0-9]|\$)")

    /** The receiver must already be lowercase: every caller builds its text with lowercase(). */
    private fun String.hasToken(token: String): Boolean {
        val regex = tokenRegexCache.getOrPut(token) {
            Regex("(^|[^a-z0-9])${Regex.escape(token.lowercase())}([^a-z0-9]|\$)")
        }
        return regex.containsMatchIn(this)
    }

    private fun String.isDolbyVisionToken(): Boolean {
        val normalized = lowercase().replace(nonAlphanumericRegex, "")
        return normalized == "dv" || normalized == "dovi" || normalized == "dolbyvision"
    }

    private fun String.isHdrToken(): Boolean {
        val normalized = lowercase().replace(nonAlphanumericOrPlusRegex, "")
        return normalized == "hdr" ||
            normalized == "hdr10" ||
            normalized == "hdr10+" ||
            normalized == "hdr10plus" ||
            normalized == "hlg"
    }

    private fun streamSearchText(stream: Stream): String {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val parsedTexts = listOfNotNull(
            parsed?.resolution,
            parsed?.quality,
            parsed?.codec,
            parsed?.hdr?.joinToString(" "),
            parsed?.audio?.joinToString(" ")
        )
        return (streamTexts(stream) + parsedTexts).joinToString(" ").lowercase()
    }

    internal fun streamTexts(stream: Stream): List<String> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        return listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.quality,
            resolve?.torrentName,
            resolve?.filename,
            raw?.torrentName,
            raw?.filename,
            stream.debridCacheStatus?.cachedName
        )
    }

    // Removes the media title from each text before languages are matched, so title words
    // ("The French Dispatch", "The Italian Job") are not read as the stream language. Only the
    // first match per text is removed: a release name repeats the title only as a language word.
    private fun languageSearchText(stream: Stream): String {
        val resolve = stream.clientResolve
        val titleRegexes = listOfNotNull(resolve?.stream?.raw?.parsed?.parsedTitle, resolve?.title)
            .mapNotNull { titleRegex(it) }
            .distinctBy { it.pattern }
            .sortedByDescending { it.pattern.length }
        return streamTexts(stream).joinToString(" ") { text ->
            titleRegexes.fold(text.lowercase()) { remaining, regex -> regex.replaceFirst(remaining, " ") }
        }
    }

    private fun titleRegex(title: String): Regex? {
        val words = title.lowercase().split(nonAlphanumericRunRegex).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return Regex("(^|[^a-z0-9])${words.joinToString("[^a-z0-9]+")}(?=[^a-z0-9]|\$)")
    }
}
