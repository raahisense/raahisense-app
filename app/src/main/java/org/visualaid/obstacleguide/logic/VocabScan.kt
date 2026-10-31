package org.visualaid.obstacleguide.logic

/**
 * Finds which command words a model knows without keeping the whole word list.
 * The Hindi and Spanish lists are large. Callers should drop the result
 * after the grammar string is built.
 */
object VocabScan {
    fun presentWords(lines: Sequence<String>, needed: Set<String>): Set<String> {
        if (needed.isEmpty()) return emptySet()
        val found = HashSet<String>(needed.size)
        for (line in lines) {
            val word = line.trim()
            if (word.isEmpty() || word !in needed || !found.add(word)) continue
            if (found.size == needed.size) break
        }
        return found
    }
}
