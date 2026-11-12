package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.ui.screens.search.SearchSuggestionsBench
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in host JVM benchmark for the search/filter milestone: the library grid query
 * ([LibraryQueryEngine], run on the main thread on every filter or sort change) and search
 * suggestion ranking (run on the main thread per keystroke). Run with
 * `./gradlew :app:testFullDebugUnitTest --tests '*SearchFilterBenchmarkTest' -Pnuvio.bench=true`.
 */
class SearchFilterBenchmarkTest {

    @Test
    fun `benchmark library query and suggestion ranking`() {
        assumeTrue(System.getProperty("nuvio.bench") == "true")

        println("library_entries,scenario,reference_us,index_build_us,index_first_query_us,index_query_us,speedup")
        for (size in listOf(500, 2_000, 5_000, 10_000)) {
            val entries = LibraryFixtures.library(size, Random(size))
            val index = LibraryIndex(entries)
            for ((name, spec) in LibraryFixtures.scenarios()) {
                val reference = measure { LibraryQueryEngine.query(entries, spec).items.size }
                // Cold path a user hits after a library sync: build the index and run the first query.
                val build = measure { LibraryIndex(entries).size }
                val firstQuery = measure { LibraryIndex(entries).query(spec).items.size } - build
                val query = measure { index.query(spec).items.size }
                println(
                    "%d,%s,%.1f,%.1f,%.1f,%.1f,%.1f".format(
                        size, name, reference / 1_000.0, build / 1_000.0, firstQuery / 1_000.0,
                        query / 1_000.0, reference / query
                    )
                )
            }
        }

        // Phase split of a steady-state index query. Only the kernel (integer filter, facet and
        // ordering pass) is portable; resolve and assemble build Kotlin objects and stay in Kotlin
        // whatever executes the kernel, so the kernel time bounds what a native executor can save.
        println("library_entries,scenario,resolve_us,kernel_us,assemble_us")
        for (size in listOf(2_000, 10_000)) {
            val index = LibraryIndex(LibraryFixtures.library(size, Random(size)))
            for ((name, spec) in LibraryFixtures.scenarios().filter { it.first in setOf("default_local", "title_asc", "series_unwatched_released") }) {
                index.query(spec)
                val selection = index.resolve(spec)
                val visible = IntArray(index.size)
                var counts = LibraryFacetCounts(index)
                var visibleCount = LibraryIndexKernel.run(index, selection, visible, counts)
                val resolve = measure { if (index.resolve(spec).listId == Int.MIN_VALUE) 0 else 1 }
                val kernel = measure {
                    counts = LibraryFacetCounts(index)
                    visibleCount = LibraryIndexKernel.run(index, selection, visible, counts)
                    visibleCount
                }
                val assemble = measure { index.assemble(selection, visible, visibleCount, counts).items.size }
                println("%d,%s,%.1f,%.1f,%.1f".format(size, name, resolve / 1_000.0, kernel / 1_000.0, assemble / 1_000.0))
            }
        }

        println("suggestion_titles,query,kotlin_us")
        for (titles in listOf(100, 400, 1_000)) {
            val names = SearchSuggestionsBench.titles(titles, Random(titles))
            for (query in listOf("the", "wolf", "wolf wall", "zzz qqq")) {
                val us = measure { SearchSuggestionsBench.rank(names, query) } / 1_000.0
                println("%d,%s,%.1f".format(titles, query, us))
            }
        }
    }

    /** Median wall time in ns of one call after a 1 s warm-up. */
    private fun measure(block: () -> Int): Double {
        var sink = 0
        val deadline = System.nanoTime() + 1_000_000_000L
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

/** Synthetic libraries shaped like Trakt/MDBList/local libraries. */
object LibraryFixtures {
    private val words = listOf(
        "Dark", "Night", "House", "Dragon", "Breaking", "Bad", "Lost", "City", "Star", "War", "Last", "Kingdom",
        "Office", "Crown", "Witcher", "Ozark", "Severance", "Wolf", "Wall", "Street", "Silent", "Sea", "Blue",
        "Planet", "Earth", "Zulu", "Alpha", "Échappée", "Ōoku", "İstanbul", "naïve", "Straße", "東京", "Love"
    )
    private val genres = listOf(
        "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
        "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "Thriller", "War", "Western",
        " drama ", "sci-fi"
    )
    private val types = listOf("movie", "series", "movie", "series", "movie", "Series ", "tv")
    val listKeys = listOf("trakt:watchlist", "trakt:collection", "trakt:list:1", "trakt:list:2", "trakt:list:3")

    fun library(size: Int, random: Random): List<LibraryEntry> = List(size) { index ->
        val wordCount = random.nextInt(1, 5)
        val title = buildString {
            when (random.nextInt(8)) {
                0 -> append("The ")
                1 -> append("A ")
                2 -> append("an ")
                3 -> append("THE ")
            }
            append((0 until wordCount).joinToString(" ") { words.random(random) })
            if (random.nextInt(6) == 0) append(" ${random.nextInt(2, 6)}")
        }
        val year = random.nextInt(1950, 2027)
        val releaseInfo = when (random.nextInt(6)) {
            0 -> null
            1 -> "$year-${year + random.nextInt(1, 8)}"
            2 -> "$year–"
            3 -> "Released $year"
            4 -> "n/a"
            else -> "$year"
        }
        val type = types.random(random)
        val keys = listKeys.shuffled(random).take(random.nextInt(1, 4)).toSet()
        LibraryEntry(
            id = if (random.nextBoolean()) "tt${1_000_000 + index}" else "tmdb:${index}",
            type = type,
            name = if (random.nextInt(50) == 0) "" else title,
            poster = "https://image.tmdb.org/t/p/w342/poster$index.jpg",
            background = null,
            logo = null,
            description = null,
            releaseInfo = releaseInfo,
            imdbRating = random.nextFloat() * 10,
            genres = genres.shuffled(random).take(random.nextInt(0, 5)),
            addonBaseUrl = null,
            listKeys = keys,
            listRanks = keys.associateWith { random.nextInt(1, size + 1) },
            listedAt = 1_600_000_000_000L + random.nextLong(0, 200_000_000_000L) / (1 + random.nextInt(3)),
            traktRank = if (random.nextBoolean()) random.nextInt(1, size + 1) else null,
            mediaCategory = if (random.nextInt(10) == 0) "anime" else null
        )
    }

    private fun spec(
        sort: LibrarySortOption,
        filterByList: Boolean = false,
        type: String? = null,
        genre: String? = null,
        year: String? = null,
        watched: LibraryWatchedFilter = LibraryWatchedFilter.ALL
    ) = LibraryQuerySpec(
        filterByList = filterByList,
        selectedListKey = if (filterByList) listKeys[0] else null,
        selectedTypeKey = type,
        selectedGenre = genre,
        selectedYear = year,
        watchedFilter = watched,
        watchedMovieIds = emptySet(),
        watchedSeriesIds = emptySet(),
        sortOption = sort,
        providerOrder = null
    )

    fun scenarios(): List<Pair<String, LibraryQuerySpec>> = listOf(
        "default_local" to spec(LibrarySortOption.DEFAULT),
        "default_provider_list" to spec(LibrarySortOption.DEFAULT, filterByList = true),
        "added_desc" to spec(LibrarySortOption.ADDED_DESC),
        "released_desc" to spec(LibrarySortOption.RELEASED_DESC),
        "released_asc" to spec(LibrarySortOption.RELEASED_ASC),
        "title_asc" to spec(LibrarySortOption.TITLE_ASC),
        "title_desc_genre_year" to spec(LibrarySortOption.TITLE_DESC, genre = "drama", year = "2015"),
        "series_unwatched_released" to spec(
            LibrarySortOption.RELEASED_DESC, type = "series", watched = LibraryWatchedFilter.UNWATCHED
        )
    )
}
