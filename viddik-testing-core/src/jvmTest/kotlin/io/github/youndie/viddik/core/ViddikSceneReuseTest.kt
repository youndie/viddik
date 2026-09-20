package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

private const val SCENE_REUSE_PROPERTY = "viddik.sceneReuse"

/**
 * One scene serving several fixtures has to draw each of them exactly as a scene of its own would,
 * whatever ran before it (#40). A golden that depends on run order would make `--component`, a
 * shard and a full run disagree with each other, which is worse than the time the reuse saves.
 */
class ViddikSceneReuseTest {
    @AfterEach
    fun stopSharedScene() {
        closeCaptureSession()
        System.clearProperty(SCENE_REUSE_PROPERTY)
    }

    @Test
    fun `a reused scene draws what a fresh one draws, in any order`() {
        val fresh = FIXTURES.associate { it.name to it.captureFresh() }

        System.setProperty(SCENE_REUSE_PROPERTY, "true")
        val forward = FIXTURES.associate { it.name to it.capture() }
        val reverse = FIXTURES.reversed().associate { it.name to it.capture() }
        // The same fixture twice in a row: a scene that carries something over shows it here.
        val repeated = FIXTURES.first().let { listOf(it.capture(), it.capture()) }

        FIXTURES.forEach { fixture ->
            assertEquals(0, differingPixels(fresh.getValue(fixture.name), forward.getValue(fixture.name)), fixture.name)
            assertEquals(0, differingPixels(fresh.getValue(fixture.name), reverse.getValue(fixture.name)), fixture.name)
        }
        assertEquals(0, differingPixels(repeated[0], repeated[1]), "the same fixture twice")
    }

    private class Fixture(
        val name: String,
        val width: Int,
        val height: Int,
        val fontScale: Float = 1f,
        val content: @Composable () -> Unit,
    ) {
        fun capture(): BufferedImage =
            captureComposable(width = width, height = height, fontScale = fontScale, content = content)

        /** With reuse off, whatever the surrounding run asked for. */
        fun captureFresh(): BufferedImage {
            val previous = System.getProperty(SCENE_REUSE_PROPERTY)
            System.clearProperty(SCENE_REUSE_PROPERTY)
            try {
                return capture()
            } finally {
                previous?.let { System.setProperty(SCENE_REUSE_PROPERTY, it) }
            }
        }
    }

    private fun differingPixels(
        expected: BufferedImage,
        actual: BufferedImage,
    ): Int {
        if (expected.width != actual.width || expected.height != actual.height) return -1
        var differing = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) differing++
            }
        }
        return differing
    }

    private companion object {
        val FIXTURES =
            listOf(
                Fixture("text at 400x120", 400, 120) {
                    MaterialTheme(typography = viddikTypography()) {
                        Box(Modifier.padding(12.dp)) { Text("scene reuse") }
                    }
                },
                Fixture("a different size", 220, 150) {
                    MaterialTheme(typography = viddikTypography()) {
                        Box(Modifier.padding(4.dp)) { Text("smaller") }
                    }
                },
                Fixture("auto height", 320, AUTO_HEIGHT_FOR_TEST) {
                    MaterialTheme(typography = viddikTypography()) {
                        Box(Modifier.padding(20.dp)) { Text("measured, not given") }
                    }
                },
                Fixture("a font scale", 400, 120, fontScale = 1.5f) {
                    MaterialTheme(typography = viddikTypography()) {
                        Box(Modifier.padding(12.dp)) { Text("bigger text") }
                    }
                },
                Fixture("no text at all", 100, 80) {
                    Box(Modifier.fillMaxSize().background(Color.Magenta))
                },
            )
    }
}

private const val AUTO_HEIGHT_FOR_TEST = -1
