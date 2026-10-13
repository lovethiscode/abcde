package com.unciv.ui.screens.civilopediascreen

/** Values accompany their original generated line, so search and legacy rendering stay unchanged. */
class CivilopediaStat(val label: String, val value: Int, val icon: Char)

/** Reorganizes only explicitly tagged generated content; custom paragraphs retain their order. */
class CivilopediaArticle(val originalLines: List<FormattedLine>) {
    val introduction: List<FormattedLine>
    val sections = linkedMapOf<String, MutableList<FormattedLine>>()
    val conclusion: List<FormattedLine>

    init {
        val first = originalLines.indexOfFirst { it.civilopediaSection != null }
        val last = originalLines.indexOfLast { it.civilopediaSection != null }
        if (first < 0) {
            introduction = originalLines
            conclusion = emptyList()
        } else {
            introduction = originalLines.take(first)
            for (line in originalLines.subList(first, last + 1))
                sections.getOrPut(line.civilopediaSection ?: "") { arrayListOf() }.add(line)
            conclusion = originalLines.drop(last + 1)
        }
    }
}
