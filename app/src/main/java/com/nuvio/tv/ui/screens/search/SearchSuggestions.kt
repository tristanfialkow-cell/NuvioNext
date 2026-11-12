package com.nuvio.tv.ui.screens.search

/** Ranking and merging of search-as-you-type suggestion titles, independent of the ViewModel. */
internal object SearchSuggestions {
    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /** Match rank for [title], lower being better, or null when it does not match [queryLower]. */
    fun rank(title: String, queryLower: String): Int? = rank(title, queryLower, words(queryLower))

    private fun words(text: String): List<String> = text.split(WORD_SEPARATOR).filter { it.isNotEmpty() }

    private fun rank(title: String, queryLower: String, queryWords: List<String>): Int? {
        val titleLower = title.lowercase()
        if (titleLower == queryLower) return 0
        if (titleLower.startsWith(queryLower)) return 1
        if (titleLower.contains(queryLower)) return 2

        // Allow multi-word matches such as "wolf wall" -> "The Wolf of Wall Street".
        // Each query word must consume a different title word.
        if (queryWords.size < 2) return null
        val unmatchedTitleWords = titleLower.split(WORD_SEPARATOR).filterTo(mutableListOf()) { it.isNotEmpty() }
        val everyWordMatches = queryWords.all { word ->
            val index = unmatchedTitleWords.indexOfFirst { it.startsWith(word) }
            if (index >= 0) unmatchedTitleWords.removeAt(index)
            index >= 0
        }
        return if (everyWordMatches) 3 else null
    }

    /**
     * The strip contents for [names], best match first, capped at [limit].
     *
     * Titles of the same rank keep the order of [names], which is the addon's own relevance order.
     * Sorting them alphabetically put five titles ahead of "Jurassic Park" for "juras", and of
     * "Slow Horses" for "slo", past the few completions a TV keyboard shows.
     */
    fun ranked(names: Collection<String>, queryLower: String, limit: Int): List<String> {
        val queryWords = words(queryLower)
        return names
            .mapNotNull { name -> rank(name, queryLower, queryWords)?.let { name to it } }
            .sortedBy { it.second }
            .map { it.first }
            .take(limit)
    }

    /**
     * Every catalog's titles, taken one position at a time: each catalog's first title, then each
     * one's second, and so on, in catalog order. Each catalog's best matches lead, and the result
     * does not depend on which catalog happened to answer first.
     */
    fun mergedCatalogNames(namesByCatalog: Map<Int, List<String>>, catalogCount: Int): List<String> {
        val catalogs = (0 until catalogCount).mapNotNull { namesByCatalog[it] }
        val merged = LinkedHashSet<String>()
        for (position in 0 until (catalogs.maxOfOrNull { it.size } ?: 0)) {
            catalogs.forEach { names -> names.getOrNull(position)?.let(merged::add) }
        }
        return merged.toList()
    }
}
