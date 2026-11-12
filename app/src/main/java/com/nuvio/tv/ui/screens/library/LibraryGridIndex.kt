package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import java.util.Locale

/**
 * Precomputed form of a library snapshot for [LibraryQueryEngine.query].
 *
 * The reference engine re-derives years and title keys with regexes inside its sort comparators,
 * which costs O(n log n) regex runs on the main thread per filter or sort change. This index
 * derives every key once per snapshot, interns strings to ids, and keeps one total order per sort
 * option, computed with the reference comparators over the precomputed keys. A query is then a
 * linear filter pass plus a walk over the cached order: a stable sort of any subset equals the
 * subset taken in the order of a stable sort of the whole list.
 *
 * Results are identical to [LibraryQueryEngine.query] (checked by `LibraryGridIndexParityTest`).
 * Instances are immutable apart from lazily built orders and safe to share across threads.
 */
class LibraryGridIndex(val entries: List<LibraryEntry>) {
    val size: Int = entries.size

    internal val typeFilterIds: IntArray
    internal val typeFilterValues: List<String>
    internal val typeTabIds: IntArray
    internal val typeTabValues: List<String>
    internal val yearIds: IntArray
    internal val yearValues: List<String>
    /** Genres of entry i are positions genreStart[i] until genreStart[i + 1]. */
    internal val genreStart: IntArray
    internal val rawGenreIds: IntArray
    internal val rawGenreValues: List<String>
    /** Trimmed genre id per occurrence, -1 when blank. */
    internal val trimmedGenreIds: IntArray
    internal val trimmedGenreValues: List<String>
    private val trimmedGenreLowercase: List<String>
    internal val listStart: IntArray
    internal val listIds: IntArray
    internal val listValues: List<String>
    internal val isMovie: BooleanArray

    private val years: Array<String?>
    private val sortNames: Array<String>
    private val titleKeys: Array<String>

    private val orderLock = Any()
    private val sortOrders = HashMap<LibrarySortOption, IntArray>()
    private val listOrders = HashMap<String?, IntArray>()
    private var providerOrderKey: LibraryProviderOrder? = null
    private var providerOrderValue: IntArray? = null

    init {
        val typeFilter = Interner()
        val typeTab = Interner()
        val year = Interner()
        val rawGenre = Interner()
        val trimmedGenre = Interner()
        val list = Interner()
        typeFilterIds = IntArray(size)
        typeTabIds = IntArray(size)
        yearIds = IntArray(size)
        isMovie = BooleanArray(size)
        years = arrayOfNulls(size)
        sortNames = Array(size) { "" }
        titleKeys = Array(size) { "" }
        genreStart = IntArray(size + 1)
        listStart = IntArray(size + 1)
        val genreRaw = ArrayList<Int>()
        val genreTrimmed = ArrayList<Int>()
        val lists = ArrayList<Int>()
        entries.forEachIndexed { i, entry ->
            typeFilterIds[i] = typeFilter.id(LibraryQueryEngine.typeFilterKey(entry))
            typeTabIds[i] = typeTab.id(LibraryQueryEngine.typeTabKey(entry))
            val entryYear = LibraryQueryEngine.extractYear(entry)
            years[i] = entryYear
            yearIds[i] = entryYear?.let(year::id) ?: -1
            isMovie[i] = entry.type.equals("movie", ignoreCase = true)
            val name = entry.name.ifBlank { entry.id }
            sortNames[i] = name
            titleKeys[i] = LibraryQueryEngine.titleSortKey(name)
            genreStart[i] = genreRaw.size
            entry.genres.forEach { genre ->
                genreRaw += rawGenre.id(genre)
                val trimmed = genre.trim()
                genreTrimmed += if (trimmed.isNotBlank()) trimmedGenre.id(trimmed) else -1
            }
            listStart[i] = lists.size
            entry.listKeys.forEach { lists += list.id(it) }
        }
        genreStart[size] = genreRaw.size
        listStart[size] = lists.size
        rawGenreIds = genreRaw.toIntArray()
        trimmedGenreIds = genreTrimmed.toIntArray()
        listIds = lists.toIntArray()
        typeFilterValues = typeFilter.values
        typeTabValues = typeTab.values
        yearValues = year.values
        rawGenreValues = rawGenre.values
        trimmedGenreValues = trimmedGenre.values
        trimmedGenreLowercase = trimmedGenreValues.map { it.lowercase(Locale.ROOT) }
        listValues = list.values
    }

    /** Same result as `LibraryQueryEngine.query(entries, spec)`. */
    fun query(spec: LibraryQuerySpec): LibraryQueryResult {
        val selection = resolve(spec)
        val visible = IntArray(size)
        val counts = LibraryFacetCounts(this)
        val visibleCount = LibraryGridKernel.run(this, selection, visible, counts)
        return assemble(selection, visible, visibleCount, counts)
    }

    /** Spec resolved against this index's interned values; consumed by every executor. */
    internal class Selection(
        val listId: Int,
        val typeId: Int,
        val genreMask: BooleanArray?,
        val yearId: Int,
        /** Per entry, whether it passes the watched filter; null when the filter is ALL. */
        val watchedOk: BooleanArray?,
        val order: IntArray?
    ) {
        companion object {
            const val ANY = -1
            const val NONE = -2
        }
    }

    internal fun resolve(spec: LibraryQuerySpec): Selection {
        val listId = if (spec.filterByList) listValues.indexOf(spec.selectedListKey ?: "").let { if (it < 0) Selection.NONE else it } else Selection.ANY
        val typeKey = spec.selectedTypeKey
        val typeId = if (typeKey == null || typeKey == LibraryTypeTab.ALL_KEY) {
            Selection.ANY
        } else {
            typeFilterValues.indexOf(typeKey).let { if (it < 0) Selection.NONE else it }
        }
        val genreMask = spec.selectedGenre?.let { selected ->
            BooleanArray(rawGenreValues.size) { rawGenreValues[it].equals(selected, ignoreCase = true) }
        }
        val yearId = spec.selectedYear?.let { selected -> yearValues.indexOf(selected).let { if (it < 0) Selection.NONE else it } }
            ?: Selection.ANY
        val watchedOk = when (spec.watchedFilter) {
            LibraryWatchedFilter.ALL -> null
            LibraryWatchedFilter.WATCHED -> BooleanArray(size) { isWatched(it, spec) }
            LibraryWatchedFilter.UNWATCHED -> BooleanArray(size) { !isWatched(it, spec) }
        }
        return Selection(listId, typeId, genreMask, yearId, watchedOk, order(spec))
    }

    private fun isWatched(i: Int, spec: LibraryQuerySpec): Boolean {
        val id = entries[i].id
        return if (isMovie[i]) spec.watchedMovieIds.contains(id) else spec.watchedSeriesIds.contains(id)
    }

    internal fun assemble(
        selection: Selection,
        visible: IntArray,
        visibleCount: Int,
        counts: LibraryFacetCounts
    ): LibraryQueryResult {
        val items = ArrayList<LibraryEntry>(visibleCount)
        for (k in 0 until visibleCount) items += entries[visible[k]]
        val genreCounts = counts.genreFirstSeen()
            .sortedBy { trimmedGenreLowercase[it] }
            .map { trimmedGenreValues[it] to counts.genre[it] }
        val yearCounts = yearValues.indices
            .filter { counts.year[it] > 0 }
            .sortedByDescending { yearValues[it] }
            .map { yearValues[it] to counts.year[it] }
        val typeKeys = counts.typeFirstSeen()
            .map { typeTabValues[it] }
            .sortedWith(compareBy { key -> TYPE_ORDER.indexOf(key).let { if (it >= 0) it else TYPE_ORDER.size } })
        val typeCounts = HashMap<String, Int>()
        typeTabValues.indices.forEach { if (counts.type[it] > 0) typeCounts[typeTabValues[it]] = counts.type[it] }
        return LibraryQueryResult(items, genreCounts, yearCounts, typeKeys, typeCounts, counts.typeTotal)
    }

    /** Entry positions in display order for [spec], or null for the snapshot's own order. */
    private fun order(spec: LibraryQuerySpec): IntArray? {
        val provider = spec.providerOrder
        return synchronized(orderLock) {
            when (spec.sortOption) {
                LibrarySortOption.DEFAULT ->
                    if (spec.filterByList) listOrders.getOrPut(spec.selectedListKey) { listOrder(spec.selectedListKey) } else null
                LibrarySortOption.ADDED_DESC, LibrarySortOption.ADDED_ASC,
                LibrarySortOption.RELEASED_DESC, LibrarySortOption.RELEASED_ASC ->
                    if (provider != null) providerOrder(provider) else sortOrders.getOrPut(spec.sortOption) { sortOrder(spec.sortOption) }
                LibrarySortOption.TITLE_ASC, LibrarySortOption.TITLE_DESC ->
                    sortOrders.getOrPut(spec.sortOption) { sortOrder(spec.sortOption) }
            }
        }
    }

    private fun providerOrder(provider: LibraryProviderOrder): IntArray {
        if (providerOrderKey !== provider) {
            val ranks = IntArray(size) { provider.ranks["${entries[it].type}:${entries[it].id}"] ?: Int.MAX_VALUE }
            providerOrderValue = sortedPositions(compareBy<Int> { ranks[it] }.thenBy { entries[it].id })
            providerOrderKey = provider
        }
        return providerOrderValue!!
    }

    private fun listOrder(listKey: String?): IntArray {
        val ranks = IntArray(size) { entries[it].listRanks[listKey] ?: entries[it].traktRank ?: Int.MAX_VALUE }
        return sortedPositions(
            compareBy<Int> { ranks[it] }
                .thenByDescending { entries[it].listedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { sortNames[it] }
                .thenBy { entries[it].id }
        )
    }

    // Mirrors the comparators in LibraryQueryEngine.query, over precomputed keys.
    private fun sortOrder(option: LibrarySortOption): IntArray = sortedPositions(
        when (option) {
            LibrarySortOption.ADDED_DESC -> compareByDescending<Int> { entries[it].listedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { sortNames[it] }
                .thenBy { entries[it].id }
            LibrarySortOption.ADDED_ASC -> compareBy<Int> { entries[it].listedAt }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { sortNames[it] }
                .thenBy { entries[it].id }
            LibrarySortOption.RELEASED_DESC -> compareByDescending<Int> { years[it] }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { sortNames[it] }
                .thenBy { entries[it].id }
            LibrarySortOption.RELEASED_ASC -> compareBy<Int, String?>(nullsLast()) { years[it] }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { sortNames[it] }
                .thenBy { entries[it].id }
            LibrarySortOption.TITLE_ASC -> compareBy<Int> { titleKeys[it] }.thenBy { entries[it].id }
            LibrarySortOption.TITLE_DESC -> compareByDescending<Int> { titleKeys[it] }.thenBy { entries[it].id }
            LibrarySortOption.DEFAULT -> error("DEFAULT has no global order")
        }
    )

    /** Stable sort of all positions, so ties keep snapshot order as in the reference. */
    private fun sortedPositions(comparator: Comparator<Int>): IntArray =
        (0 until size).sortedWith(comparator).toIntArray()

    private class Interner {
        private val ids = HashMap<String, Int>()
        val values = ArrayList<String>()
        fun id(value: String): Int = ids.getOrPut(value) { values += value; values.size - 1 }
    }

    private companion object {
        val TYPE_ORDER = listOf("movie", "series", "tv", "show", "anime")
    }
}

/** Facet counters filled by an executor; ids refer to the index's interned values. */
class LibraryFacetCounts internal constructor(index: LibraryGridIndex) {
    internal val genre = IntArray(index.trimmedGenreValues.size)
    internal val genreSeen = IntArray(index.trimmedGenreValues.size)
    internal var genreSeenCount = 0
    internal val year = IntArray(index.yearValues.size)
    internal val type = IntArray(index.typeTabValues.size)
    internal val typeSeen = IntArray(index.typeTabValues.size)
    internal var typeSeenCount = 0
    internal var typeTotal = 0

    internal fun genreFirstSeen(): List<Int> = (0 until genreSeenCount).map { genreSeen[it] }
    internal fun typeFirstSeen(): List<Int> = (0 until typeSeenCount).map { typeSeen[it] }
}

/** The per-query pass over a [LibraryGridIndex]: filters, facet counts and ordering. */
internal object LibraryGridKernel {
    /** Writes visible entry positions in display order to [visible]; returns how many. */
    fun run(
        index: LibraryGridIndex,
        selection: LibraryGridIndex.Selection,
        visible: IntArray,
        counts: LibraryFacetCounts
    ): Int {
        val n = index.size
        val visibleMask = BooleanArray(n)
        val typeSeen = BooleanArray(index.typeTabValues.size)
        val genreSeen = BooleanArray(index.trimmedGenreValues.size)
        for (i in 0 until n) {
            if (!inList(index, selection.listId, i)) continue
            val tab = index.typeTabIds[i]
            if (!typeSeen[tab]) {
                typeSeen[tab] = true
                counts.typeSeen[counts.typeSeenCount++] = tab
            }
            val typeOk = selection.typeId == LibraryGridIndex.Selection.ANY || index.typeFilterIds[i] == selection.typeId
            val genreOk = genreMatches(index, selection.genreMask, i)
            val yearOk = selection.yearId == LibraryGridIndex.Selection.ANY || index.yearIds[i] == selection.yearId
            if (genreOk && yearOk) {
                counts.type[tab]++
                counts.typeTotal++
            }
            if (!typeOk) continue
            if (yearOk) {
                for (g in index.genreStart[i] until index.genreStart[i + 1]) {
                    val id = index.trimmedGenreIds[g]
                    if (id < 0) continue
                    if (!genreSeen[id]) {
                        genreSeen[id] = true
                        counts.genreSeen[counts.genreSeenCount++] = id
                    }
                    counts.genre[id]++
                }
            }
            if (genreOk) {
                val year = index.yearIds[i]
                if (year >= 0) counts.year[year]++
            }
            if (genreOk && yearOk && (selection.watchedOk?.get(i) != false)) visibleMask[i] = true
        }
        var count = 0
        val order = selection.order
        if (order == null) {
            for (i in 0 until n) if (visibleMask[i]) visible[count++] = i
        } else {
            for (i in order) if (visibleMask[i]) visible[count++] = i
        }
        return count
    }

    private fun inList(index: LibraryGridIndex, listId: Int, i: Int): Boolean {
        if (listId == LibraryGridIndex.Selection.ANY) return true
        if (listId == LibraryGridIndex.Selection.NONE) return false
        for (k in index.listStart[i] until index.listStart[i + 1]) if (index.listIds[k] == listId) return true
        return false
    }

    private fun genreMatches(index: LibraryGridIndex, mask: BooleanArray?, i: Int): Boolean {
        if (mask == null) return true
        for (g in index.genreStart[i] until index.genreStart[i + 1]) if (mask[index.rawGenreIds[g]]) return true
        return false
    }
}
