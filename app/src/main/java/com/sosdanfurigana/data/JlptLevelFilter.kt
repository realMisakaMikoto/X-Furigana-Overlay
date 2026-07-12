package com.sosdanfurigana.data

import java.util.Locale

internal enum class JlptLevelFilter(
    val label: String,
    private val level: String?
) {
    ALL("全部", null),
    N5("N5", "N5"),
    N4("N4", "N4"),
    N3("N3", "N3"),
    N2("N2", "N2"),
    N1("N1", "N1"),
    UNCLASSIFIED("未分级", "");

    fun matches(jlptLevel: String): Boolean {
        val normalized = jlptLevel.trim().uppercase(Locale.ROOT)
        return when (this) {
            ALL -> true
            UNCLASSIFIED -> normalized !in CLASSIFIED_LEVELS
            else -> normalized == level
        }
    }

    private companion object {
        val CLASSIFIED_LEVELS = setOf("N1", "N2", "N3", "N4", "N5")
    }
}
