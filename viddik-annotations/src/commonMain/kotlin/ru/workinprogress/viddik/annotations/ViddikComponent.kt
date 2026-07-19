package ru.workinprogress.viddik.annotations

import androidx.compose.runtime.Composable

const val AUTO_HEIGHT = -1

data class ViddikComponent(
    val name: String,
    val group: String,
    val width: Int = 400,
    val height: Int = AUTO_HEIGHT,
    val content: @Composable () -> Unit,
)
