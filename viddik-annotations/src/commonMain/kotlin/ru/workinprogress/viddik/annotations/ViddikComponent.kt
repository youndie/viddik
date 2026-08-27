package ru.workinprogress.viddik.annotations

import androidx.compose.runtime.Composable

const val AUTO_HEIGHT = -1

/**
 * A [ViddikScreenshot] size that was not given, as opposed to one that was given as a number.
 *
 * The processor cannot tell "argument omitted" from "argument written out" on its own — KSP hands it
 * the annotation with defaults already substituted — so the default has to be a value no one would
 * write. Without it, falling back to `@Preview` would be indistinguishable from a fixture that
 * deliberately asked for the old default of 400.
 */
const val UNSPECIFIED = Int.MIN_VALUE

data class ViddikComponent(
    val name: String,
    val group: String,
    val width: Int = 400,
    val height: Int = AUTO_HEIGHT,
    val content: @Composable () -> Unit,
)
