package ru.workinprogress.viddik.gradle

import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault

/**
 * A `Test` task that can be pointed at a single screenshot.
 *
 * Gradle's own `--tests` can't do it: the fixtures are JUnit 5 *dynamic* tests under one generated
 * `GeneratedViddikTests` class, and `--tests` only matches classes and methods — `--tests "*Primary*"`
 * finds nothing at all. The selection therefore has to happen inside the engine, and this task exists
 * to carry it there.
 */
@DisableCachingByDefault(
    because =
        "The same type serves recording, which writes goldens into the source tree as an undeclared " +
            "output — a cache hit would skip producing the very files the run exists to produce.",
)
public abstract class ViddikScreenshotTask : Test() {
    /**
     * Which components to run, as a case-insensitive substring of `"$group - $name"` with `*` and `?`
     * as wildcards. Unset runs every fixture in the module; a value matching nothing fails the task
     * rather than reporting an empty green run.
     *
     * `@Internal` because [ViddikFilterArgumentProvider] is what declares it as an input — that is
     * also what carries the value into the test JVM, since a command-line option is only known after
     * the task has been configured.
     */
    @get:Internal
    @get:Option(
        option = "component",
        description = "Run only components matching this pattern, e.g. --component \"Buttons - Primary\".",
    )
    public abstract val component: Property<String>
}

/** Turns [ViddikScreenshotTask.component] into the system property the engine reads. */
internal class ViddikFilterArgumentProvider(
    @get:Input @get:Optional val component: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = component.orNull?.let { listOf("-D$FILTER_PROPERTY=$it") } ?: emptyList()

    private companion object {
        const val FILTER_PROPERTY = "viddik.filter"
    }
}
