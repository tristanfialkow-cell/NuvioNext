package com.nuvio.tv.ui.screens.search

import kotlin.random.Random

/** Test-side access to the internal [SearchSuggestions] for benchmarks in other packages. */
object SearchSuggestionsBench {
    private val words = listOf(
        "The", "Wolf", "of", "Wall", "Street", "Dark", "Knight", "Lord", "Rings", "Star", "Wars", "Return",
        "Jurassic", "Park", "World", "Slow", "Horses", "House", "Dragon", "Breaking", "Bad", "Better", "Call"
    )

    fun titles(count: Int, random: Random): List<String> = List(count) {
        (0 until random.nextInt(1, 6)).joinToString(" ") { words.random(random) }
    }

    fun rank(names: List<String>, query: String): Int =
        SearchSuggestions.ranked(names, query.lowercase(), 8).size
}
