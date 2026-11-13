package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.demo.EndlessSpinner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.awt.image.BufferedImage
import java.time.Duration

private const val SCENE_REUSE_PROPERTY = "viddik.sceneReuse"
private const val WIDTH = 64
private const val HEIGHT = 64

/**
 * Content that never becomes idle — a frame loop with no end, the shape of a busy button's spinner —
 * is captured at a fixed moment of a frozen clock when it asks for one (`captureAtMillis`).
 *
 * Before that it could not be captured at all: waitForIdle advanced the clock frame after frame for
 * as long as something awaited a frame, which for such a loop is forever — the shared scene's 120 s
 * timeout, or a hang in a scene of its own. The time limits below are what that looks like here.
 */
class ViddikEndlessAnimationTest {
    @AfterEach
    fun stopSharedScene() {
        closeCaptureSession()
        System.clearProperty(SCENE_REUSE_PROPERTY)
    }

    @Test
    fun `an endless animation is captured at the moment it asks for`() {
        val image = assertTimeoutPreemptively(LIMIT) { spinnerAt(500) }

        // The mark has moved: the clock really ran, the picture is not the first composition's.
        assertNotEquals(0, differingPixels(spinnerAt(0), image), "500 ms in should not look like 0 ms in")
    }

    @Test
    fun `the same moment gives the same picture on every run`() {
        val first = assertTimeoutPreemptively(LIMIT) { spinnerAt(500) }
        val second = assertTimeoutPreemptively(LIMIT) { spinnerAt(500) }

        assertEquals(0, differingPixels(first, second))
    }

    /**
     * Pins what `captureAtMillis` promises about the clock: it starts where a fresh scene's does,
     * and a moment is rounded up to a whole 16 ms frame — 500 is the frame at 512. The spinner's angle
     * is read off exactly this number, so a harness that starts or steps its clock differently
     * moves every such golden; this names the cause when it happens.
     */
    @Test
    fun `the last frame drawn is the requested moment, rounded up to a whole frame`() {
        var lastFrame = -1L
        assertTimeoutPreemptively(LIMIT) {
            captureComposable(width = WIDTH, height = HEIGHT, captureAtMillis = 500) {
                LaunchedEffect(Unit) { while (true) withFrameMillis { lastFrame = it } }
                Box(Modifier.size(8.dp).background(Color.Black))
            }
        }

        assertEquals(512L, lastFrame)
    }

    /**
     * A shared scene's clock is wherever the fixtures before left it, and the spinner draws the frame
     * time itself, so captured in the shared scene it would come out at another angle after another
     * fixture — or after itself. A fixture captured at a moment therefore gets a scene of its own
     * even under reuse, and must come out exactly as it does without reuse.
     */
    @Test
    fun `a reused scene gives what a scene of its own gives, whatever ran before`() {
        val alone = assertTimeoutPreemptively(LIMIT) { spinnerAt(500) }

        System.setProperty(SCENE_REUSE_PROPERTY, "true")
        val reused =
            assertTimeoutPreemptively(LIMIT) {
                listOf(
                    spinnerAt(500),
                    plainBox(),
                    spinnerAt(500),
                    spinnerAt(500),
                )
            }

        assertEquals(0, differingPixels(alone, reused[0]), "the first one under reuse")
        assertEquals(0, differingPixels(alone, reused[2]), "after a fixture in the shared scene")
        assertEquals(0, differingPixels(alone, reused[3]), "right after itself")
    }

    /** The shared scene a timed fixture closes is reopened for the next one, which draws as before. */
    @Test
    fun `fixtures captured once idle are untouched by one captured at a moment`() {
        val before = plainBox()

        System.setProperty(SCENE_REUSE_PROPERTY, "true")
        val around = assertTimeoutPreemptively(LIMIT) { listOf(plainBox(), spinnerAt(500), plainBox()) }

        assertEquals(0, differingPixels(before, around[0]))
        assertEquals(0, differingPixels(before, around[2]), "after the timed fixture")
    }

    @Test
    fun `a moment before the content is set is refused`() {
        assertThrows<IllegalArgumentException> { spinnerAt(-1) }
    }

    private fun spinnerAt(millis: Long): BufferedImage =
        captureComposable(width = WIDTH, height = HEIGHT, captureAtMillis = millis) { Spinner() }

    private fun plainBox(): BufferedImage =
        captureComposable(width = WIDTH, height = HEIGHT) { Box(Modifier.size(20.dp).background(Color.Blue)) }

    @Composable
    private fun Spinner() {
        Box(Modifier.background(Color.White)) { EndlessSpinner() }
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
        /** Far above a capture's ~1 s, far below the shared scene's 120 s that an idle wait ends in. */
        val LIMIT: Duration = Duration.ofSeconds(30)
    }
}
