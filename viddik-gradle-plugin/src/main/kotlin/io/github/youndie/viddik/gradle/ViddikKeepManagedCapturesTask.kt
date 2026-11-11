package io.github.youndie.viddik.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * Keeps what one managed device's run pulled, under the level its device reported.
 *
 * Where AGP pulls a managed device's output is not something to rely on. Run one level at a time, the same
 * build put API 31's captures in `outputs/…/viddikApi36/`, overwriting what an earlier run of 36 had left
 * there, and API 34's in `intermediates/…/viddikApi34AndroidDeviceTest/`, while an earlier run's API 36
 * still sat beside it. Read from there, the page could lose a level to the next one or show a level from a
 * week before as if it were new. So each level's run is copied out as soon as it ends, into
 * `build/viddik-matrix/managed/api<N>`, choosing among what is in AGP's directories by what `device.json`
 * says and by whether it was written during this run, and the report reads only that.
 */
@DisableCachingByDefault(because = "It copies files whose only reader is the report, and has to run after every run.")
public abstract class ViddikKeepManagedCapturesTask : DefaultTask() {
    /** AGP's two pull directories for managed devices. */
    @get:Internal
    public abstract val pulled: ConfigurableFileCollection

    @get:Internal
    public abstract val kept: DirectoryProperty

    @get:Internal
    public abstract val level: Property<Int>

    /** Touched when the level's device test starts: anything pulled before it is an earlier run's. */
    @get:Internal
    public abstract val started: RegularFileProperty

    @TaskAction
    public fun keep() {
        val since =
            started
                .get()
                .asFile
                .takeIf { it.isFile }
                ?.lastModified() ?: 0L
        val api = level.get()
        if (!keepManagedCapture(pulled.files.toList(), api, since, kept.get().asFile)) {
            markPulledNothing(kept.get().asFile.resolve("api$api"))
            logger.warn(
                "viddik matrix: API $api's run pulled no captures back. The report keeps what an earlier run of " +
                    "it left, if anything, and marks that column as an earlier run's.",
            )
        }
    }
}

/**
 * Copies the newest capture directory under [roots] whose `device.json` says [level] and was written at or
 * after [since] to `api<level>/` under [into], replacing what was there.
 *
 * @return whether there was one.
 */
internal fun keepManagedCapture(
    roots: List<File>,
    level: Int,
    since: Long,
    into: File,
): Boolean {
    val newest =
        roots
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name == CAPTURE_MARKER }.toList() }
            .filter { levelOf(it) == level.toString() && it.lastModified() >= since }
            .maxByOrNull { it.lastModified() }
            ?: return false
    val target = into.resolve("api$level")
    target.deleteRecursively()
    newest.parentFile.copyRecursively(target)
    return true
}
