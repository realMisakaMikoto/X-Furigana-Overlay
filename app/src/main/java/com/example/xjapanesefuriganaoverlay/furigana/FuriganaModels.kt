package com.example.xjapanesefuriganaoverlay.furigana

data class FuriganaAnnotation(
    val surface: String,
    val reading: String,
    val start: Int,
    val end: Int,
    val confidence: Double
)

data class FuriganaCandidate(
    val id: Int,
    val surface: String,
    val start: Int,
    val end: Int
)
