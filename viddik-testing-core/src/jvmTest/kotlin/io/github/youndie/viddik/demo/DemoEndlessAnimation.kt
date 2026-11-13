package io.github.youndie.viddik.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.annotations.ViddikScreenshot
import io.github.youndie.viddik.core.viddikTypography

/** How long one turn of [EndlessSpinner] takes. */
internal const val SPINNER_TURN_MS = 1200L

/** The moment [EndlessSpinnerPreview] is captured at: 32 frames of 16 ms, so the mark is at 153.6°. */
internal const val SPINNER_CAPTURE_AT_MS = 500L

/**
 * A mark that turns for as long as it is composed, the shape of a busy button's spinner: a
 * `withFrameMillis` loop with no end. Plain `withFrameMillis`, not `withInfiniteAnimationFrameMillis`,
 * so the test harness's infinite-animation policy does not stop it, and nothing ever becomes idle.
 *
 * The angle is the frame time itself rather than the time since the loop started, which is the harder
 * of the two: such a picture depends on where the clock stood when the content was set, and is only
 * the same on every run because a fixture captured at a moment gets a clock of its own.
 */
@Composable
internal fun EndlessSpinner(modifier: Modifier = Modifier) {
    var frameMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frameMillis = it }
    }
    val ink = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(40.dp)) {
        rotate(degrees = frameMillis % SPINNER_TURN_MS * 360f / SPINNER_TURN_MS) {
            val bar = 6.dp.toPx()
            drawRect(ink, topLeft = Offset((size.width - bar) / 2, 0f), size = Size(bar, size.height / 2))
        }
    }
}

// Without captureAtMillis this fixture never finishes: waitForIdle advances the clock frame after
// frame for as long as something awaits a frame, and the loop above always does.
@ViddikScreenshot(name = "Endless spinner", group = "Demo", captureAtMillis = SPINNER_CAPTURE_AT_MS)
@Composable
fun EndlessSpinnerPreview() {
    MaterialTheme(typography = viddikTypography()) {
        Surface {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EndlessSpinner()
                Text("Busy")
            }
        }
    }
}
