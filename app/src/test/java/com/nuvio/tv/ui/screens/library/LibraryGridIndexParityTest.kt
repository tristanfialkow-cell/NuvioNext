package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibrarySourceMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** [LibraryGridIndex] must return exactly what the reference [LibraryQueryEngine] returns. */
class LibraryGridIndexParityTest {

    @Test
    fun `index matches the reference engine on randomized libraries and queries`() {
        val random = Random(0x11B)
        repeat(60) { round ->
            val entries = edgeCaseEntries() + LibraryFixtures.library(random.nextInt(0, 400), random)
            val index = LibraryGridIndex(entries)
            repeat(40) { query ->
                val spec = randomSpec(entries, random)
                assertEquals(
                    "round $round query $query spec $spec",
                    LibraryQueryEngine.query(entries, spec),
                    index.query(spec)
                )
            }
        }
    }

    @Test
    fun `cached orders stay correct across repeated and alternating queries`() {
        val entries = LibraryFixtures.library(300, Random(7))
        val index = LibraryGridIndex(entries)
        val specs = LibraryFixtures.scenarios().map { it.second }
        repeat(3) {
            specs.forEach { spec -> assertEquals(LibraryQueryEngine.query(entries, spec), index.query(spec)) }
        }
    }

    @Test
    fun `empty library`() {
        val spec = LibraryFixtures.scenarios().first().second
        assertEquals(LibraryQueryEngine.query(emptyList(), spec), LibraryGridIndex(emptyList()).query(spec))
    }

    private fun entry(id: String, name: String, type: String = "movie", release: String? = null, genres: List<String> = emptyList(),
                      lists: Set<String> = emptySet(), listedAt: Long = 0L, category: String? = null) =
        LibraryEntry(
            id = id, type = type, name = name, poster = null, background = null, logo = null, description = null,
            releaseInfo = release, imdbRating = null, genres = genres, addonBaseUrl = null, listKeys = lists,
            listedAt = listedAt, mediaCategory = category
        )

    // Ties, duplicate ids, blank names and types, case and whitespace variants, non-ASCII case pairs.
    private fun edgeCaseEntries(): List<LibraryEntry> = listOf(
        entry("tt1", "The Office", release = "2005"),
        entry("tt1", "the office", release = "2005"),
        entry("tt2", "", type = " ", release = "1999–2003", genres = listOf("Drama", " drama", "DRAMA", "")),
        entry("tt3", "An Échappée", type = "Series", genres = listOf("Sci-Fi", "sci-fi ")),
        entry("tt4", "a", type = "SERIES ", release = "Released in 2010 and 2012"),
        entry("tt5", "İstanbul", category = "Anime", genres = listOf("ı", "I")),
        entry("tt6", "ǅungla", release = "19999 2001", genres = listOf("ǆ", "Ǆ")),
        entry("tt7", "Straße", release = "2001", listedAt = 5, lists = setOf("trakt:watchlist")),
        entry("tt8", "STRASSE", release = "2001", listedAt = 5, lists = setOf("trakt:watchlist")),
        entry("tt9", "\uD801\uDC00 deseret", release = "2001", listedAt = 5),
        entry("tt9", "\uD801\uDC28 deseret", release = "2001", listedAt = 5)
    )

    private fun <T> Random.pick(values: List<T>): T = values[nextInt(values.size)]

    private fun randomSpec(entries: List<LibraryEntry>, random: Random): LibraryQuerySpec {
        val genres = entries.flatMap { it.genres } + listOf("drama", "DRAMA", "missing", "ǅ", "i")
        val years = entries.mapNotNull { LibraryQueryEngine.extractYear(it) } + listOf("1800", "2015")
        val types = entries.map { LibraryQueryEngine.typeFilterKey(it) } + listOf(LibraryTypeTab.ALL_KEY, "missing", "")
        val ids = entries.map { it.id }
        val filterByList = random.nextBoolean()
        val listKey = if (random.nextInt(4) == 0) null else random.pick(LibraryFixtures.listKeys + "missing:list")
        val sort = random.pick(LibrarySortOption.entries)
        val providerOrder = if (random.nextInt(3) == 0) {
            LibraryProviderOrder(
                source = LibrarySourceMode.TRAKT,
                listKey = listKey ?: "",
                sortOption = sort,
                ranks = entries.shuffled(random).take(entries.size / 2)
                    .associate { "${it.type}:${it.id}" to random.nextInt(0, 20) }
            )
        } else {
            null
        }
        return LibraryQuerySpec(
            filterByList = filterByList,
            selectedListKey = listKey,
            selectedTypeKey = if (random.nextBoolean()) null else random.pick(types),
            selectedGenre = if (random.nextBoolean() || genres.isEmpty()) null else random.pick(genres),
            selectedYear = if (random.nextBoolean() || years.isEmpty()) null else random.pick(years),
            watchedFilter = random.pick(LibraryWatchedFilter.entries),
            watchedMovieIds = ids.filter { random.nextBoolean() }.toSet(),
            watchedSeriesIds = ids.filter { random.nextBoolean() }.toSet(),
            sortOption = sort,
            providerOrder = providerOrder
        )
    }
}
