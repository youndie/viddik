package io.github.youndie.viddik.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Makes the last kept run of each level its baseline (#81): the report then compares every capture with
 * the same level's baseline, and a fixture that changed on some levels only is a regression on those.
 *
 * One directory per level and density (`api<N>-<density>`), replaced whole; levels not run are left as
 * they were. Where the baselines live — committed, or kept as a CI artifact — is the project's choice:
 * committed, they multiply the repository's PNGs by the number of levels.
 */
@DisableCachingByDefault(because = "It copies captures into a directory the project owns.")
public abstract class ViddikMatrixBaselineTask : DefaultTask() {
    /** The kept managed and connected captures. */
    @get:Internal
    public abstract val kept: ConfigurableFileCollection

    @get:Internal
    public abstract val baselines: DirectoryProperty

    @get:Internal
    public abstract val levels: ListProperty<Int>

    @TaskAction
    public fun write() {
        val into = baselines.get().asFile
        val written = writeBaselines(kept.files.toList(), into, levels.get().toSet())
        if (written.isEmpty()) {
            throw GradleException(
                "viddik matrix: no kept captures of API ${levels.get().joinToString()} to make a baseline of. " +
                    "Run the matrix (or the levels) first.",
            )
        }
        written.forEach {
            logger.lifecycle(
                "viddik matrix: baseline ${it.key}, ${it.captures} capture(s), in ${File(into, it.key).path}",
            )
            if (it.filtered) {
                logger.warn(
                    "viddik matrix: ${it.key}'s kept run was filtered, so its baseline has only the fixtures " +
                        "that run had. Run the level in full before making a baseline of it.",
                )
            }
        }
    }
}

internal class WrittenBaseline(
    val key: String,
    val captures: Int,
    val filtered: Boolean,
)

/**
 * Copies the newest kept capture of each level in [levels] (every one, when empty) found under [kept] into
 * [into], flat, under its [baselineKeyOf] name.
 */
internal fun writeBaselines(
    kept: List<File>,
    into: File,
    levels: Set<Int>,
): List<WrittenBaseline> {
    val captures =
        kept
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == CAPTURE_MARKER }.toList() }
            .map { it.parentFile }
            .filter { levels.isEmpty() || levelOf(File(it, CAPTURE_MARKER)).toIntOrNull() in levels }
            .groupBy { baselineKeyOf(File(it, CAPTURE_MARKER)) }
            .mapValues { (_, dirs) -> dirs.maxBy { File(it, CAPTURE_MARKER).lastModified() } }
    return captures.map { (key, capture) ->
        val target = File(into, key)
        target.deleteRecursively()
        target.mkdirs()
        val files = capture.listFiles { file -> file.isFile }.orEmpty()
        files.forEach { it.copyTo(File(target, it.name), overwrite = true) }
        WrittenBaseline(key, files.count { it.extension == "png" }, File(capture, FILTER_FILE).isFile)
    }
}

/** Has to match `baselineKey` in viddik-matrix-report; the two modules share no code. */
internal fun baselineKeyOf(deviceFile: File): String {
    val text = deviceFile.readText()
    val density =
        Regex(""""density"\s*:\s*([0-9.]+)""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toFloatOrNull() ?: 0f
    return "api${levelOf(deviceFile)}-${density.toString().removeSuffix(".0")}"
}
