package io.github.youndie.viddik.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.youndie.viddik.annotations.ViddikComponent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 40
private const val HEIGHT = 20

/** What [ViddikEngine.verify] names the golden of the fixture below. */
private const val GOLDEN_NAME = "Engine_Tolerance.png"

private const val BLUE_ARGB = 0xFF0000FF.toInt()
private const val TOLERANCE_PROPERTY = "viddik.tolerancePercent"
private const val BUDGET_PROPERTY = "viddik.pronouncedPixelBudget"

/**
 * A fixture's own `tolerancePercent` against a golden it cannot possibly match — solid red compared to
 * a solid blue golden, i.e. 100% of the pixels differing.
 *
 * The point of comparing something so far off is that the outcome then says nothing about rendering:
 * every case here turns purely on which threshold `verify` decided to use, which is what a per-fixture
 * tolerance is. Driving it through `verify` rather than through `DiffResult.matches` is deliberate too —
 * the precedence being pinned lives in `verify`'s default argument, and a test of the differ would
 * report green whatever that default did.
 *
 * Record mode turns every `verify` into a write, so the cases that expect a failure would report one
 * of their own. That is a property of the mode, not a regression, hence the guard.
 */
@DisabledIfEnvironmentVariable(named = "VIDDIK_RECORD_MODE", matches = "true")
class ViddikToleranceTest {
    @Test
    fun `a fixture with no tolerance of its own is judged by the default`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        val failure = assertThrows<IllegalStateException> { verifyAgainst(dir, tolerance = null) }

        assertTrue(
            "800/800 px differ" in failure.message.orEmpty(),
            "the failure should be the mismatch itself: ${failure.message}",
        )
    }

    @Test
    fun `a fixture that states a tolerance is judged by that one`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        verifyAgainst(dir, tolerance = 100.0)
    }

    @Test
    fun `a fixture's own tolerance wins over the run's`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        // Both directions: the fixture is the deliberate statement, so it decides whether it is the
        // looser of the two or the stricter one.
        withProperties(TOLERANCE_PROPERTY to "0.05") { verifyAgainst(dir, tolerance = 100.0) }
        withProperties(TOLERANCE_PROPERTY to "100.0") {
            assertThrows<IllegalStateException> { verifyAgainst(dir, tolerance = 0.0) }
        }
    }

    @Test
    fun `a fixture without one still follows the run's tolerance`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        withProperties(TOLERANCE_PROPERTY to "100.0", BUDGET_PROPERTY to null) {
            verifyAgainst(dir, tolerance = null)
        }
    }

    @Test
    fun `a fixture without one is held to the run's pronounced-pixel budget inside the run's share`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        // #64: every pixel is pronounced (red against blue), so a share of 100% would take it and a
        // budget of 16 does not.
        val failure =
            withProperties(TOLERANCE_PROPERTY to "100.0", BUDGET_PROPERTY to "16") {
                assertThrows<IllegalStateException> { verifyAgainst(dir, tolerance = null) }
            }

        assertTrue(
            "at most 16 px past delta" in failure.message.orEmpty(),
            "the failure should name the budget that decided it: ${failure.message}",
        )
    }

    @Test
    fun `a fixture's own tolerance is not cut short by the run's budget`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        // A fixture states its own tolerance for residue that is not faint, which is exactly what the
        // budget counts — so its number is the whole of its allowance.
        withProperties(BUDGET_PROPERTY to "0") { verifyAgainst(dir, tolerance = 100.0) }
    }

    @Test
    fun `the failure says the tolerance was the fixture's own`(
        @TempDir dir: File,
    ) {
        writeGolden(dir)

        val failure = assertThrows<IllegalStateException> { verifyAgainst(dir, tolerance = 0.0) }

        assertTrue(
            "@ViddikScreenshot" in failure.message.orEmpty(),
            "a threshold the fixture set itself should be attributed to it: ${failure.message}",
        )
    }

    private fun verifyAgainst(
        dir: File,
        tolerance: Double?,
    ) = ViddikEngine.verify(
        component =
            ViddikComponent(
                name = "Tolerance",
                group = "Engine",
                width = WIDTH,
                height = HEIGHT,
                tolerancePercent = tolerance,
            ) {
                Box(Modifier.fillMaxSize().background(Color.Red))
            },
        snapshotsDir = dir,
        reportsDir = File(dir, "reports"),
    )

    private fun writeGolden(dir: File) {
        val blue = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                blue.setRGB(x, y, BLUE_ARGB)
            }
        }
        check(ImageIO.write(blue, "png", File(dir, GOLDEN_NAME))) { "no PNG writer" }
    }

    /** Sets each property (null clears it) for [block], and puts back what was there before. */
    private fun <T> withProperties(
        vararg values: Pair<String, String?>,
        block: () -> T,
    ): T {
        val previous = values.map { (name, _) -> name to System.getProperty(name) }
        values.forEach { (name, value) -> set(name, value) }
        try {
            return block()
        } finally {
            previous.forEach { (name, value) -> set(name, value) }
        }
    }

    private fun set(
        name: String,
        value: String?,
    ) {
        if (value == null) System.clearProperty(name) else System.setProperty(name, value)
    }
}
