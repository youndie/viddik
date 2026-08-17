package ru.workinprogress.viddik.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for the `ru.workinprogress.viddik` plugin, available as `viddik { }` in a consumer's
 * build script.
 *
 * Every property has a default derived from the module itself, so an empty `viddik { }` block — or no
 * block at all — is the normal case. Reach in here only where the derivation can't know the answer:
 * where the goldens live ([snapshotsDir]), how strict the comparison should be ([tolerancePercent]),
 * or whether verification belongs in `check` ([verifyOnCheck]).
 */
public interface ViddikExtension {
    /**
     * Name of the Kotlin JVM target that carries the screenshot fixtures, e.g. `"desktop"` for
     * `jvm("desktop")` or `"jvm"` for an unnamed `jvm()`.
     *
     * Auto-detected, and current Kotlin Gradle Plugin versions reject a second JVM target in the same
     * module anyway ("`jvm()` Kotlin Target Already Declared"), so this is a guard rather than
     * something to reach for. Ignored by a plain `kotlin("jvm")` module, which has no targets to
     * choose between.
     */
    public val jvmTarget: Property<String>

    /**
     * Where the golden PNGs live, relative to the module directory.
     *
     * Defaults to `src/<test source set>/snapshots` — `src/desktopTest/snapshots` for a
     * `jvm("desktop")` target, `src/jvmTest/snapshots` for an unnamed `jvm()`, `src/test/snapshots`
     * for a plain `kotlin("jvm")` module. Becomes the `viddik.snapshotsDir` system property.
     */
    public val snapshotsDir: Property<String>

    /**
     * Where a failed comparison writes its `_DIFF.png`, relative to the module directory. Defaults to
     * viddik's own `build/reports/screenshots`. Becomes the `viddik.reportsDir` system property.
     */
    public val reportsDir: Property<String>

    /**
     * Share of pixels allowed to differ before a comparison fails. Unset by default, leaving viddik's
     * own 0.5% — which exists to absorb cross-OS rasterizer differences. A project that records and
     * verifies on the same machine can afford to tighten this considerably. Becomes the
     * `viddik.tolerancePercent` system property.
     */
    public val tolerancePercent: Property<Double>

    /**
     * How far a single channel may drift before a pixel counts as mismatched. Unset by default.
     * Becomes the `viddik.channelTolerance` system property.
     */
    public val channelTolerance: Property<Int>

    /**
     * Whether the KSP processor generates the JUnit 5 test class alongside the component registry.
     * `true` by default; set it to `false` in a module that only wants the registry for
     * [ViddikShowroom][ru.workinprogress.viddik.ViddikShowroom] — an Android app module, typically.
     * Becomes the `viddik.generateTests` KSP argument.
     *
     * Turning this off leaves nothing for the verify task to run, so the task is not registered.
     */
    public val generateTests: Property<Boolean>

    /**
     * Whether `check` depends on the verify task. `false` by default, so goldens recorded on a CI
     * runner don't redden `./gradlew build` on a dev machine with different fonts.
     *
     * Passing `-Pviddik.verify` on the command line turns it on regardless — that's how CI opts in
     * without the build script having to know it's CI.
     */
    public val verifyOnCheck: Property<Boolean>

    /**
     * Whether the module's ordinary test task excludes the generated screenshot tests. `true` by
     * default: they're owned by the verify task, and running them from both places just does the work
     * twice against goldens the ordinary task has no reason to care about.
     */
    public val excludeFromTestTask: Property<Boolean>

    /**
     * Whether the plugin adds the viddik dependencies itself — annotations and testing-core on the
     * test source set, the processor on the matching KSP configuration, and the JUnit 5 runtime.
     * `true` by default.
     *
     * Set it to `false` to declare them by hand, e.g. to pin them through your own version catalog.
     */
    public val addDependencies: Property<Boolean>

    /**
     * Version of the `ru.workinprogress:viddik-*` artifacts to add when [addDependencies] is on.
     * Defaults to the plugin's own version, which is what keeps the processor and the engine in step.
     */
    public val viddikVersion: Property<String>
}
