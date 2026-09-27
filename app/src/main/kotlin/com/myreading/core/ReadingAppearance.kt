package com.myreading.core

data class ReadingAppearance(
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.25f,
    val surroundColor: Long = 0xFFF8F3EA,
    val paperColor: Long = 0xFFFFFCF7,
    val surroundImage: String = "",
    val paperImage: String = ""
)
