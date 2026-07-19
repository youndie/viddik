@file:OptIn(ExperimentalCoroutinesApi::class)

package ru.workinprogress.viddik.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.workinprogress.viddik.annotations.AUTO_HEIGHT
import java.awt.image.BufferedImage

@OptIn(ExperimentalTestApi::class)
fun captureComposable(
    width: Int = DEFAULT_WIDTH,
    height: Int = AUTO_HEIGHT,
    compositionLocals: List<ProvidedValue<*>> = emptyList(),
    content: @Composable () -> Unit,
): BufferedImage {
    val autoHeight = height == AUTO_HEIGHT
    val canvasHeight = if (autoHeight) MAX_AUTO_HEIGHT_CANVAS else height

    var captured: BufferedImage? = null
    var measuredHeightPx = 0

    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        runDesktopComposeUiTest(width = width, height = canvasHeight) {
            setContent {
                CompositionLocalProvider(*compositionLocals.toTypedArray()) {
                    Box(
                        Modifier
                            .width(width.dp)
                            .let { if (autoHeight) it else it.height(height.dp) }
                            .onGloballyPositioned { measuredHeightPx = it.size.height },
                    ) {
                        content()
                    }
                }
            }
            waitForIdle()

            val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
            if (roots.size <= 1) {
                captured = onRoot().captureToImage().toAwtImage()
            } else {
                val dialogNode = onNode(isDialog())
                captured = dialogNode.captureToImage().toAwtImage()
                measuredHeightPx = dialogNode.fetchSemanticsNode().size.height
            }
        }
    } finally {
        Dispatchers.resetMain()
    }

    val full = captured ?: error("Screenshot capture produced no image")
    if (!autoHeight) return full

    check(measuredHeightPx < MAX_AUTO_HEIGHT_CANVAS) {
        "Контент выше потолка авто-высоты ($MAX_AUTO_HEIGHT_CANVAS px) — передайте явный height в " +
            "@ViddikScreenshot вместо авто-режима."
    }
    return full.getSubimage(0, 0, width, measuredHeightPx.coerceAtLeast(1))
}

const val DEFAULT_WIDTH = 400

private const val MAX_AUTO_HEIGHT_CANVAS = 4000
