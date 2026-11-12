package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.core.nativecore.NuvioCore
import com.nuvio.tv.core.nativecore.NuvioCoreNative
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream

/**
 * Kotlin side of the Rust release classifier (`rust/nuvio-core/src/release_facts.rs`).
 *
 * Wire format (ABI 1), one batch per call, no JSON:
 * - `layout`: 6 ints per stream — text count, parsed flag (0/1), then hdr, audio, channels and
 *   languages list sizes (-1 when the list is null).
 * - `strings`: per stream, 7 nullable strings (stream quality, resolve title, parsed resolution,
 *   quality, codec, group, parsed title), then the texts and the four lists' elements.
 * - `out`: 8 ints per stream — resolution, quality, visual mask, audio mask, channel mask, encode,
 *   language start, language count. Masks use enum ordinals as bit indices.
 */
internal object NativeReleaseFacts {
    private const val TAG = "NativeReleaseFacts"
    private const val LAYOUT_STRIDE = 6
    private const val FIXED_STRINGS = 7
    private const val OUT_STRIDE = 8

    // The order the Kotlin classifier appends tags in; lists are rebuilt from masks in this order.
    private val visualOrder = DebridStreamVisualTag.entries.filter { it != DebridStreamVisualTag.UNKNOWN }
    private val audioOrder = DebridStreamAudioTag.entries.filter { it != DebridStreamAudioTag.UNKNOWN }
    private val channelOrder = listOf(
        DebridStreamAudioChannel.CH_7_1,
        DebridStreamAudioChannel.CH_6_1,
        DebridStreamAudioChannel.CH_5_1,
        DebridStreamAudioChannel.CH_2_0
    )

    private val resolutions = DebridStreamResolution.entries
    private val qualities = DebridStreamQuality.entries
    private val encodes = DebridStreamEncode.entries
    private val languages = DebridStreamLanguage.entries

    /** True when the library loaded and its enum/language tables match this build exactly. */
    val isAvailable: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NuvioCore.isAvailable && tablesMatch()
    }

    private fun tablesMatch(): Boolean {
        val expected = listOf(
            DebridStreamResolution.entries.map { it.name },
            DebridStreamQuality.entries.map { it.name },
            DebridStreamVisualTag.entries.map { it.name },
            DebridStreamAudioTag.entries.map { it.name },
            DebridStreamAudioChannel.entries.map { it.name },
            DebridStreamEncode.entries.map { it.name },
            DebridStreamLanguage.entries.map { it.name },
            DebridStreamLanguage.entries.map { it.code },
            DebridStreamLanguage.entries.map { it.label },
            DebridStreamLanguage.entries.map { ReleaseFactsClassifier.languageAliases[it].orEmpty().joinToString(",") }
        )
        return try {
            expected.withIndex().all { (kind, values) ->
                val native = NuvioCoreNative.nativeEnumTable(kind).toList()
                (native == values).also { matches ->
                    if (!matches) Log.w(TAG, "Native table $kind differs: $native != $values")
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Native table check failed", error)
            false
        }
    }

    fun classify(streams: List<Stream>): List<ReleaseTextFacts> {
        val layout = IntArray(streams.size * LAYOUT_STRIDE)
        val strings = ArrayList<String?>(streams.size * 16)
        var languageCapacity = 0
        streams.forEachIndexed { index, stream ->
            val resolve = stream.clientResolve
            val parsed = resolve?.stream?.raw?.parsed
            val texts = ReleaseFactsClassifier.streamTexts(stream)
            val base = index * LAYOUT_STRIDE
            layout[base] = texts.size
            layout[base + 1] = if (parsed != null) 1 else 0
            layout[base + 2] = parsed?.hdr?.size ?: -1
            layout[base + 3] = parsed?.audio?.size ?: -1
            layout[base + 4] = parsed?.channels?.size ?: -1
            layout[base + 5] = parsed?.languages?.size ?: -1
            strings += stream.quality
            strings += resolve?.title
            strings += parsed?.resolution
            strings += parsed?.quality
            strings += parsed?.codec
            strings += parsed?.group
            strings += parsed?.parsedTitle
            strings += texts
            parsed?.hdr?.let { strings += it }
            parsed?.audio?.let { strings += it }
            parsed?.channels?.let { strings += it }
            parsed?.languages?.let { strings += it }
            languageCapacity += maxOf(parsed?.languages?.size ?: 0, languages.size)
        }

        val out = IntArray(streams.size * OUT_STRIDE)
        val languagesOut = IntArray(languageCapacity)
        val groups = arrayOfNulls<String>(streams.size)
        val count = NuvioCoreNative.nativeClassifyReleases(layout, strings.toTypedArray(), out, languagesOut, groups)
        check(count == streams.size) { "Native classifier returned $count of ${streams.size}" }

        return List(streams.size) { index ->
            val base = index * OUT_STRIDE
            val languageStart = out[base + 6]
            ReleaseTextFacts(
                resolution = resolutions[out[base]],
                quality = qualities[out[base + 1]],
                visualTags = fromMask(out[base + 2], visualOrder, DebridStreamVisualTag.UNKNOWN),
                audioTags = fromMask(out[base + 3], audioOrder, DebridStreamAudioTag.UNKNOWN),
                audioChannels = fromMask(out[base + 4], channelOrder, DebridStreamAudioChannel.UNKNOWN),
                encode = encodes[out[base + 5]],
                languages = List(out[base + 7]) { languages[languagesOut[languageStart + it]] },
                releaseGroup = groups[index].orEmpty()
            )
        }
    }

    private fun <T : Enum<T>> fromMask(mask: Int, order: List<T>, unknown: T): List<T> {
        if (mask and (1 shl unknown.ordinal) != 0) return listOf(unknown)
        return order.filter { mask and (1 shl it.ordinal) != 0 }.ifEmpty { listOf(unknown) }
    }
}
