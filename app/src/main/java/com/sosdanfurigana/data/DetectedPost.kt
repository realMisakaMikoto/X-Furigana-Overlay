package com.sosdanfurigana.data

import android.graphics.Rect

data class DetectedPost(
    val id: String,
    val text: String,
    val boundsInScreen: Rect?,
    val packageName: String
)
