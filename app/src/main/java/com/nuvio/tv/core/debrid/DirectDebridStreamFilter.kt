package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamCodecFilter
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamFeatureFilter
import com.nuvio.tv.domain.model.DebridStreamLanguage
import com.nuvio.tv.domain.model.DebridStreamMinimumQuality
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamSortCriterion
import com.nuvio.tv.domain.model.DebridStreamSortDirection
import com.nuvio.tv.domain.model.DebridStreamSortKey
import com.nuvio.tv.domain.model.DebridStreamSortMode
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.Stream

object DirectDebridStreamFilter {
    const val FALLBACK_SOURCE_NAME = "Direct Debrid"


    fun filterInstant(streams: List<Stream>, settings: DebridSettings? = null): List<Stream> {
        val instantStreams = streams
            .filter { isInstantCandidate(it) }
            .map { stream ->
                val sourceName = sourceName(stream)
                stream.copy(
                    name = stream.name ?: sourceName,
                    addonName = sourceName,
                    addonLogo = null
                )
            }
            .distinctBy { stream ->
                listOf(
                    stream.clientResolve?.infoHash?.lowercase(),
                    stream.clientResolve?.fileIdx?.toString(),
                    stream.clientResolve?.filename,
                    stream.name,
                    stream.title
                ).joinToString("|")
            }
        return if (settings == null) instantStreams else applyPreferences(instantStreams, settings)
    }

    fun isInstantCandidate(stream: Stream): Boolean {
        val resolve = stream.clientResolve ?: return false
        return resolve.type.equals("debrid", ignoreCase = true) &&
            DebridProviders.isSupported(resolve.service) &&
            resolve.isCached == true
    }

    fun isDirectDebridSourceName(addonName: String): Boolean {
        return DebridProviders.all().any { addonName == DebridProviders.instantName(it.id) }
    }

    fun applyPreferences(streams: List<Stream>, settings: DebridSettings): List<Stream> =
        applyPreferencesWithFacts(streams, settings).map { it.first }

    /** Same as [applyPreferences], but keeps each stream's facts so callers don't recompute them. */
    fun applyPreferencesWithFacts(
        streams: List<Stream>,
        settings: DebridSettings
    ): List<Pair<Stream, StreamFacts>> {
        val preferences = effectivePreferences(settings)
        val textFacts = ReleaseFactsClassifier.classifyAll(streams)
        val matchedStreams = streams.mapIndexed { index, stream -> stream to streamFacts(stream, textFacts[index], preferences) }
            .filter { (_, facts) -> facts.matchesFilters(preferences) }

        val orderedStreams = if (preferences.sortCriteria.isEmpty()) {
            matchedStreams
        } else {
            matchedStreams.sortedWith { left, right ->
                compareFacts(left.second, right.second, preferences.sortCriteria)
            }
        }

        return applyLimits(orderedStreams, preferences)
    }

    fun facts(stream: Stream, settings: DebridSettings): StreamFacts =
        streamFacts(stream, ReleaseFactsClassifier.classifyAll(listOf(stream)).single(), effectivePreferences(settings))

    private fun effectivePreferences(settings: DebridSettings): DebridStreamPreferences {
        val default = DebridStreamPreferences()
        if (settings.streamPreferences != default) return settings.streamPreferences
        if (
            settings.streamMaxResults == 0 &&
            settings.streamSortMode == DebridStreamSortMode.DEFAULT &&
            settings.streamMinimumQuality == DebridStreamMinimumQuality.ANY &&
            settings.streamDolbyVisionFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamHdrFilter == DebridStreamFeatureFilter.ANY &&
            settings.streamCodecFilter == DebridStreamCodecFilter.ANY
        ) {
            return default
        }
        var preferences = default.copy(
            maxResults = settings.streamMaxResults,
            sortCriteria = when (settings.streamSortMode) {
                DebridStreamSortMode.DEFAULT -> default.sortCriteria
                DebridStreamSortMode.QUALITY_DESC -> listOf(
                    DebridStreamSortCriterion(DebridStreamSortKey.RESOLUTION, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.QUALITY, DebridStreamSortDirection.DESC),
                    DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC)
                )
                DebridStreamSortMode.SIZE_DESC -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.DESC))
                DebridStreamSortMode.SIZE_ASC -> listOf(DebridStreamSortCriterion(DebridStreamSortKey.SIZE, DebridStreamSortDirection.ASC))
            },
            requiredResolutions = DebridStreamResolution.defaultOrder.filter {
                it.value >= settings.streamMinimumQuality.minResolution && it != DebridStreamResolution.UNKNOWN
            }
        )
        preferences = when (settings.streamDolbyVisionFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(excludedVisualTags = preferences.excludedVisualTags + listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV))
            DebridStreamFeatureFilter.ONLY -> preferences.copy(requiredVisualTags = preferences.requiredVisualTags + listOf(DebridStreamVisualTag.DV, DebridStreamVisualTag.DV_ONLY, DebridStreamVisualTag.HDR_DV))
        }
        preferences = when (settings.streamHdrFilter) {
            DebridStreamFeatureFilter.ANY -> preferences
            DebridStreamFeatureFilter.EXCLUDE -> preferences.copy(excludedVisualTags = preferences.excludedVisualTags + listOf(DebridStreamVisualTag.HDR, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR_DV))
            DebridStreamFeatureFilter.ONLY -> preferences.copy(requiredVisualTags = preferences.requiredVisualTags + listOf(DebridStreamVisualTag.HDR, DebridStreamVisualTag.HDR10, DebridStreamVisualTag.HDR10_PLUS, DebridStreamVisualTag.HLG, DebridStreamVisualTag.HDR_ONLY, DebridStreamVisualTag.HDR_DV))
        }
        return when (settings.streamCodecFilter) {
            DebridStreamCodecFilter.ANY -> preferences
            DebridStreamCodecFilter.H264 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AVC))
            DebridStreamCodecFilter.HEVC -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.HEVC))
            DebridStreamCodecFilter.AV1 -> preferences.copy(requiredEncodes = listOf(DebridStreamEncode.AV1))
        }
    }

    private fun applyLimits(
        streams: List<Pair<Stream, StreamFacts>>,
        preferences: DebridStreamPreferences
    ): List<Pair<Stream, StreamFacts>> {
        val resolutionCounts = mutableMapOf<DebridStreamResolution, Int>()
        val qualityCounts = mutableMapOf<DebridStreamQuality, Int>()
        val result = mutableListOf<Pair<Stream, StreamFacts>>()
        for (stream in streams) {
            if (preferences.maxResults > 0 && result.size >= preferences.maxResults) break
            if (preferences.maxPerResolution > 0) {
                val count = resolutionCounts[stream.second.resolution] ?: 0
                if (count >= preferences.maxPerResolution) continue
            }
            if (preferences.maxPerQuality > 0) {
                val count = qualityCounts[stream.second.quality] ?: 0
                if (count >= preferences.maxPerQuality) continue
            }
            resolutionCounts[stream.second.resolution] = (resolutionCounts[stream.second.resolution] ?: 0) + 1
            qualityCounts[stream.second.quality] = (qualityCounts[stream.second.quality] ?: 0) + 1
            result += stream
        }
        return result
    }

    private fun StreamFacts.matchesFilters(preferences: DebridStreamPreferences): Boolean {
        if (preferences.requiredResolutions.isNotEmpty() && resolution !in preferences.requiredResolutions) return false
        if (resolution in preferences.excludedResolutions) return false
        if (preferences.requiredQualities.isNotEmpty() && quality !in preferences.requiredQualities) return false
        if (quality in preferences.excludedQualities) return false
        if (preferences.requiredVisualTags.isNotEmpty() && visualTags.none { it in preferences.requiredVisualTags }) return false
        if (visualTags.any { it in preferences.excludedVisualTags }) return false
        if (preferences.requiredAudioTags.isNotEmpty() && audioTags.none { it in preferences.requiredAudioTags }) return false
        if (audioTags.any { it in preferences.excludedAudioTags }) return false
        if (preferences.requiredAudioChannels.isNotEmpty() && audioChannels.none { it in preferences.requiredAudioChannels }) return false
        if (audioChannels.any { it in preferences.excludedAudioChannels }) return false
        if (preferences.requiredEncodes.isNotEmpty() && encode !in preferences.requiredEncodes) return false
        if (encode in preferences.excludedEncodes) return false
        if (preferences.requiredLanguages.isNotEmpty() && languages.none { it in preferences.requiredLanguages }) return false
        if (languages.isNotEmpty() && languages.all { it in preferences.excludedLanguages }) return false
        if (preferences.requiredReleaseGroups.isNotEmpty() && preferences.requiredReleaseGroups.none { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.excludedReleaseGroups.any { releaseGroup.equals(it, ignoreCase = true) }) return false
        if (preferences.sizeMinGb > 0 && size != null && size < preferences.sizeMinGb.gigabytes()) return false
        if (preferences.sizeMaxGb > 0 && size != null && size > preferences.sizeMaxGb.gigabytes()) return false
        return true
    }

    private fun compareFacts(
        left: StreamFacts,
        right: StreamFacts,
        criteria: List<DebridStreamSortCriterion>
    ): Int {
        for (criterion in criteria) {
            val comparison = compareKey(left, right, criterion)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun compareKey(
        left: StreamFacts,
        right: StreamFacts,
        criterion: DebridStreamSortCriterion
    ): Int {
        val direction = if (criterion.direction == DebridStreamSortDirection.ASC) 1 else -1
        return when (criterion.key) {
            DebridStreamSortKey.RESOLUTION -> left.resolutionRank.compareTo(right.resolutionRank) * -direction
            DebridStreamSortKey.QUALITY -> left.qualityRank.compareTo(right.qualityRank) * -direction
            DebridStreamSortKey.VISUAL_TAG -> left.visualRank.compareTo(right.visualRank) * -direction
            DebridStreamSortKey.AUDIO_TAG -> left.audioRank.compareTo(right.audioRank) * -direction
            DebridStreamSortKey.AUDIO_CHANNEL -> left.channelRank.compareTo(right.channelRank) * -direction
            DebridStreamSortKey.ENCODE -> left.encodeRank.compareTo(right.encodeRank) * -direction
            DebridStreamSortKey.SIZE -> (left.size ?: 0L).compareTo(right.size ?: 0L) * direction
            DebridStreamSortKey.LANGUAGE -> left.languageRank.compareTo(right.languageRank) * -direction
            DebridStreamSortKey.RELEASE_GROUP -> left.releaseGroup.compareTo(right.releaseGroup, ignoreCase = true)
        }
    }

    private fun streamFacts(
        stream: Stream,
        text: ReleaseTextFacts,
        preferences: DebridStreamPreferences
    ): StreamFacts {
        return StreamFacts(
            resolution = text.resolution,
            quality = text.quality,
            visualTags = text.visualTags,
            audioTags = text.audioTags,
            audioChannels = text.audioChannels,
            encode = text.encode,
            languages = text.languages,
            releaseGroup = text.releaseGroup,
            size = streamSize(stream),
            resolutionRank = rank(text.resolution, preferences.preferredResolutions),
            qualityRank = rank(text.quality, preferences.preferredQualities),
            visualRank = rankAny(text.visualTags, preferences.preferredVisualTags),
            audioRank = rankAny(text.audioTags, preferences.preferredAudioTags),
            channelRank = rankAny(text.audioChannels, preferences.preferredAudioChannels),
            encodeRank = rank(text.encode, preferences.preferredEncodes),
            languageRank = if (text.languages.isEmpty()) Int.MAX_VALUE else text.languages.minOf { rank(it, preferences.preferredLanguages) }
        )
    }

    private fun <T> rank(value: T, preferred: List<T>): Int {
        val index = preferred.indexOf(value)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun <T> rankAny(values: List<T>, preferred: List<T>): Int {
        return values.minOfOrNull { rank(it, preferred) } ?: Int.MAX_VALUE
    }

    private fun streamSize(stream: Stream): Long? =
        StreamTextSizeParser.effectiveSizeBytes(stream)

    private fun sourceName(stream: Stream): String {
        return DebridProviders.instantName(stream.clientResolve?.service)
    }

    private fun Int.gigabytes(): Long = this * 1_000_000_000L

    data class StreamFacts(
        val resolution: DebridStreamResolution,
        val quality: DebridStreamQuality,
        val visualTags: List<DebridStreamVisualTag>,
        val audioTags: List<DebridStreamAudioTag>,
        val audioChannels: List<DebridStreamAudioChannel>,
        val encode: DebridStreamEncode,
        val languages: List<DebridStreamLanguage>,
        val releaseGroup: String,
        val size: Long?,
        val resolutionRank: Int,
        val qualityRank: Int,
        val visualRank: Int,
        val audioRank: Int,
        val channelRank: Int,
        val encodeRank: Int,
        val languageRank: Int
    )
}
