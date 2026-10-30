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
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** What [ViddikEngine.verify] names the golden of the fixture below. */
private const val GOLDEN_NAME = "Engine_Unreadable.png"

/** What an un-pulled Git LFS checkout leaves where a PNG should be. */
private const val LFS_POINTER =
    "version https://git-lfs.github.com/spec/v1\n" +
        "oid sha256:4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393\n" +
        "size 1234\n"

/**
 * A golden that is not an image fails the verification with a message naming the file, rather than
 * with the `NullPointerException` `ImageIO.read`'s `null` used to become (issue #61).
 *
 * `record = false` is passed rather than inherited from `VIDDIK_RECORD_MODE`, because under record
 * mode both files would simply be replaced — which `ViddikRecordTest` pins separately.
 */
class ViddikUnreadableGoldenTest {
    @Test
    fun `a Git LFS pointer is named as one`(
        @TempDir dir: File,
    ) {
        File(dir, GOLDEN_NAME).writeText(LFS_POINTER)

        val failure = assertThrows<IllegalStateException> { verify(dir) }

        assertTrue(failure.message!!.contains("Git LFS pointer"), failure.message)
        assertTrue(failure.message!!.contains("git lfs pull"), failure.message)
        assertTrue(failure.message!!.contains(GOLDEN_NAME), "the message names the file: ${failure.message}")
    }

    @Test
    fun `any other file that is not an image names the fixture and the path`(
        @TempDir dir: File,
    ) {
        File(dir, GOLDEN_NAME).writeText("this is not a PNG")

        val failure = assertThrows<IllegalStateException> { verify(dir) }

        assertTrue(failure.message!!.contains("Engine/Unreadable"), failure.message)
        assertTrue(failure.message!!.contains("not an image"), failure.message)
        assertTrue(!failure.message!!.contains("LFS"), "a corrupted PNG is not an LFS pointer: ${failure.message}")
    }

    private fun verify(dir: File) =
        ViddikEngine.verify(
            component =
                ViddikComponent(name = "Unreadable", group = "Engine", width = 40, height = 20) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                },
            snapshotsDir = dir,
            reportsDir = File(dir, "reports"),
            record = false,
        )
}
