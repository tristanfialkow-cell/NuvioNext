package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import java.util.Locale

/** Everything the library grid's filter, facet and sort step depends on, besides the entries. */
data class LibraryQuerySpec(
    /** Provider sources (Trakt, Simkl, MDBList) show one list at a time; the local library does not. */
    val filterByList: Boolean,
    val selectedListKey: String?,
    val selectedTypeKey: String?,
    val selectedGenre: String?,
    val selectedYear: String?,
    val watchedFilter: LibraryWatchedFilter,
    val watchedMovieIds: Set<String>,
    val watchedSeriesIds: Set<String>,
    val sortOption: LibrarySortOption,
    /** Provider-defined order, already matched to the source, list and sort option. */
    val providerOrder: LibraryProviderOrder?
)

data class LibraryQueryResult(
    val items: List<LibraryEntry>,
    /** Genre facet, sorted case-insensitively. Counts ignore the genre selection itself. */
    val genreCounts: List<Pair<String, Int>>,
    /** Year facet, newest first. Counts ignore the year selection itself. */
    val yearCounts: List<Pair<String, Int>>,
    /** Type keys present in the selected list, in display order. */
    val typeKeys: List<String>,
    /** Count per type key, ignoring the type selection itself. */
    val typeCounts: Map<String, Int>,
    val typeCountTotal: Int
)

/**
 * The library grid's filter → facet counts → sort step, as a pure function of the entries and
 * [LibraryQuerySpec]. This is the reference implementation that faster backends must match.
 */
object LibraryQueryEngine {
    private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

    // Strip leading English articles ("The", "A", "An") when sorting by title, so
    // "The Walking Dead" sorts under W and not T. Matches how streaming services
    // and most media libraries order titles alphabetically.
    private val LEADING_ARTICLE_REGEX = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

    // Stable order: movies, series/tv/show, anime, then anything else in first-seen order.
    private val TYPE_ORDER = listOf("movie", "series", "tv", "show", "anime")

    fun extractYear(entry: LibraryEntry): String? =
        entry.releaseInfo?.let { YEAR_REGEX.find(it)?.value }

    fun titleSortKey(title: String): String =
        title.trim().replace(LEADING_ARTICLE_REGEX, "").lowercase(Locale.ROOT)

    /** Key used by the type filter; differs from [typeTabKey] for blank types. */
    fun typeFilterKey(entry: LibraryEntry): String =
        (entry.mediaCategory ?: entry.type).trim().lowercase(Locale.ROOT)

    fun typeTabKey(entry: LibraryEntry): String =
        (entry.mediaCategory ?: entry.type).trim().ifBlank { "unknown" }.lowercase(Locale.ROOT)

    fun query(allItems: List<LibraryEntry>, spec: LibraryQuerySpec): LibraryQueryResult {
        val listFiltered = if (spec.filterByList) {
            val listKey = spec.selectedListKey ?: ""
            allItems.filter { entry -> entry.listKeys.contains(listKey) }
        } else {
            allItems
        }

        val selectedTypeKey = spec.selectedTypeKey
        val typeFiltered = listFiltered.filter { entry ->
            selectedTypeKey == null ||
                selectedTypeKey == LibraryTypeTab.ALL_KEY ||
                typeFilterKey(entry) == selectedTypeKey
        }

        val selectedGenre = spec.selectedGenre
        val selectedYear = spec.selectedYear
        val genreFiltered = if (selectedGenre != null) {
            typeFiltered.filter { entry -> entry.genres.any { it.equals(selectedGenre, ignoreCase = true) } }
        } else {
            typeFiltered
        }

        val yearFiltered = if (selectedYear != null) {
            genreFiltered.filter { entry -> extractYear(entry) == selectedYear }
        } else {
            genreFiltered
        }

        val watchedFiltered = when (spec.watchedFilter) {
            LibraryWatchedFilter.ALL -> yearFiltered
            LibraryWatchedFilter.WATCHED -> yearFiltered.filter { entry -> isWatched(entry, spec) }
            LibraryWatchedFilter.UNWATCHED -> yearFiltered.filter { entry -> !isWatched(entry, spec) }
        }

        // Faceted counts: each facet counts items matching all OTHER active filters.
        val itemsForGenreCounts = if (selectedYear != null) {
            typeFiltered.filter { extractYear(it) == selectedYear }
        } else {
            typeFiltered
        }
        val genreCounts = mutableMapOf<String, Int>()
        itemsForGenreCounts.forEach { entry ->
            entry.genres.forEach { genre ->
                val normalized = genre.trim()
                if (normalized.isNotBlank()) {
                    genreCounts[normalized] = (genreCounts[normalized] ?: 0) + 1
                }
            }
        }
        val sortedGenreCounts = genreCounts.entries
            .sortedBy { it.key.lowercase(Locale.ROOT) }
            .map { it.key to it.value }

        val itemsForYearCounts = if (selectedGenre != null) {
            typeFiltered.filter { entry -> entry.genres.any { it.equals(selectedGenre, ignoreCase = true) } }
        } else {
            typeFiltered
        }
        val yearCounts = mutableMapOf<String, Int>()
        itemsForYearCounts.forEach { entry ->
            val year = extractYear(entry) ?: return@forEach
            yearCounts[year] = (yearCounts[year] ?: 0) + 1
        }
        val sortedYearCounts = yearCounts.entries
            .sortedByDescending { it.key }
            .map { it.key to it.value }

        val itemsForTypeCounts = listFiltered.filter { entry ->
            val genreMatch = selectedGenre == null || entry.genres.any { it.equals(selectedGenre, ignoreCase = true) }
            val yearMatch = selectedYear == null || extractYear(entry) == selectedYear
            genreMatch && yearMatch
        }

        val providerOrder = spec.providerOrder?.comparator()
        val sorted = when (spec.sortOption) {
            LibrarySortOption.DEFAULT -> when {
                spec.filterByList -> watchedFiltered.sortedWith(
                    compareBy<LibraryEntry> { it.listRanks[spec.selectedListKey] ?: it.traktRank ?: Int.MAX_VALUE }
                        .thenByDescending { it.listedAt }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                        .thenBy { it.id }
                )
                else -> watchedFiltered
            }
            LibrarySortOption.ADDED_DESC -> watchedFiltered.sortedWith(
                providerOrder ?: compareByDescending<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.ADDED_ASC -> watchedFiltered.sortedWith(
                providerOrder ?: compareBy<LibraryEntry> { it.listedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.RELEASED_DESC -> watchedFiltered.sortedWith(
                providerOrder ?: compareByDescending<LibraryEntry> { extractYear(it) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.RELEASED_ASC -> watchedFiltered.sortedWith(
                providerOrder ?: compareBy<LibraryEntry, String?>(nullsLast()) { extractYear(it) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.id } }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_ASC -> watchedFiltered.sortedWith(
                compareBy<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }
                    .thenBy { it.id }
            )
            LibrarySortOption.TITLE_DESC -> watchedFiltered.sortedWith(
                compareByDescending<LibraryEntry> { titleSortKey(it.name.ifBlank { it.id }) }
                    .thenBy { it.id }
            )
        }

        val typeKeys = LinkedHashSet<String>()
        listFiltered.forEach { typeKeys += typeTabKey(it) }
        val typeCounts = mutableMapOf<String, Int>()
        itemsForTypeCounts.forEach { entry ->
            val key = typeTabKey(entry)
            typeCounts[key] = (typeCounts[key] ?: 0) + 1
        }
        val orderedTypeKeys = typeKeys.sortedWith(compareBy { key ->
            val idx = TYPE_ORDER.indexOf(key)
            if (idx >= 0) idx else TYPE_ORDER.size
        })

        return LibraryQueryResult(
            items = sorted,
            genreCounts = sortedGenreCounts,
            yearCounts = sortedYearCounts,
            typeKeys = orderedTypeKeys,
            typeCounts = typeCounts,
            typeCountTotal = itemsForTypeCounts.size
        )
    }

    private fun isWatched(entry: LibraryEntry, spec: LibraryQuerySpec): Boolean {
        val isMovie = entry.type.equals("movie", ignoreCase = true)
        return if (isMovie) spec.watchedMovieIds.contains(entry.id) else spec.watchedSeriesIds.contains(entry.id)
    }
}
