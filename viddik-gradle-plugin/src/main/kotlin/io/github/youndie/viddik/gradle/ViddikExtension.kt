package io.github.youndie.viddik.gradle

import org.gradle.api.provider.Property

/**
 * Configuration for the `io.github.youndie.viddik` plugin, available as `viddik { }` in a consumer's
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
     * own 0.05% (plus a ±2 per-channel allowance), which is what the residual cross-OS difference
     * measures once fixtures bundle a font. Becomes the `viddik.tolerancePercent` system property.
     *
     * This is the threshold for the whole module. A single fixture that provably can't hold it says so
     * itself — `@ViddikScreenshot(tolerancePercent = ...)` — rather than being paid for by loosening
     * the check on every other fixture.
     */
    public val tolerancePercent: Property<Double>

    /**
     * How far a single channel may drift before a pixel counts as mismatched. Unset by default.
     * Becomes the `viddik.channelTolerance` system property.
     */
    public val channelTolerance: Property<Int>

    /**
     * Where the design references live — PNGs exported from the design the fixtures were built to,
     * named exactly like the goldens (`<group>_<name>.png`), relative to the module directory.
     * Defaults to `design/` under [snapshotsDir]. Becomes the `viddik.designDir` system property.
     *
     * `viddikDesignParity` reads them; nothing ever writes them. A fixture without one is reported as
     * such and is not a failure — a module rarely has a design for every state of every component.
     */
    public val designDir: Property<String>

    /**
     * Share of pixels allowed to differ between a fixture and its design reference before the fixture
     * is reported as a mismatch. Unset by default, leaving viddik's own 5% — a design is drawn by a
     * different rasterizer than Compose, so the golden threshold would fail on anti-aliasing alone.
     * Becomes the `viddik.designTolerancePercent` system property.
     *
     * Separate from [tolerancePercent] on purpose: that one measures rendering noise between two runs
     * of the same code, this one measures how far the code is from its design.
     */
    public val designTolerancePercent: Property<Double>

    /**
     * How far a single channel may drift before a pixel counts as different from the design. Unset by
     * default, leaving viddik's own ±16, which is what anti-aliased edges drawn by two rasterizers
     * measure. Becomes the `viddik.designChannelTolerance` system property.
     */
    public val designChannelTolerance: Property<Int>

    /**
     * Whether `viddikDesignParity` fails on a fixture outside the design tolerance. `false` by
     * default: the task is a report, and a screen half-way to its design is the normal state of a
     * screen being built. Turn it on where matching the design is the acceptance criterion, for good
     * here or per run with `-Pviddik.designStrict`. Either way the run fails when no fixture had a
     * reference at all, which is a misconfiguration and not a result. Becomes the
     * `viddik.designStrict` system property.
     */
    public val designStrict: Property<Boolean>

    /**
     * How many forks to spread the fixtures over. `1` by default, which is one class and one fork.
     *
     * Gradle divides test work by class, and all the fixtures live under one generated class, so
     * `maxParallelForks` alone does nothing. With this set, the processor emits that many classes,
     * each taking every Nth fixture at runtime, and the verify and record tasks get a matching
     * `maxParallelForks`.
     *
     * It buys nothing on a small suite: each fork pays its own JVM start plus Compose and skiko
     * class loading — measured at ~1.9 s against ~18 ms per capture — so a suite has to be big
     * enough for the captures to outweigh that. Measured on 400 fixtures, four forks took a run
     * from 11.3 s to about 5.8 s; on this repository's own 28, sharding is a straight loss.
     *
     * `viddikDesignParity` ignores it: that task writes one report for the module, and shard 0
     * measures everything.
     */
    public val shards: Property<Int>

    /**
     * Whether the run serves every capture from one shared scene instead of standing a scene up per
     * fixture. Unset by default. Becomes the `viddik.sceneReuse` system property.
     *
     * Standing a scene up is most of what a capture costs — an *empty* capture measured 11.7 ms
     * against a median fixture's 12.1 ms — so sharing one takes a suite of same-sized fixtures from
     * ~13 ms per capture to ~6 ms. The scene is reopened whenever the next fixture has a different
     * canvas, because a `Dialog` centres itself in the window; a suite whose sizes alternate every
     * fixture therefore gains nothing, and one whose fixtures share a size gains the most.
     *
     * Two constraints come with it: the run must not contain other Compose tests that stand up a
     * harness of their own (a shared scene cannot share a JVM with one), and the fixtures are
     * visited in size order rather than registry order.
     */
    public val sceneReuse: Property<Boolean>

    /**
     * Whether a capture refuses to photograph text the font cannot draw, instead of letting the host
     * draw it. Unset by default. Becomes the `viddik.glyphCheck` system property.
     *
     * A glyph missing from the font is resolved by whatever the machine has installed, so the golden
     * is stable where it was recorded and different elsewhere — and the pixels that move are the ones
     * *after* the character, which is why it is usually diagnosed as something else entirely.
     *
     * Off by default because the check can only read one font: turn it on in a module themed with
     * `viddikTypography()`, or point [glyphCheckFont] at the font the module bundles itself.
     */
    public val glyphCheck: Property<Boolean>

    /**
     * Path to the font [glyphCheck] reads, for a module that bundles its own instead of using
     * viddik's Roboto. Becomes the `viddik.glyphCheckFont` system property.
     */
    public val glyphCheckFont: Property<String>

    /**
     * Whether the KSP processor generates the JUnit 5 test class alongside the component registry.
     * `true` by default; set it to `false` in a module that only wants the registry for
     * [ViddikShowroom][io.github.youndie.viddik.ViddikShowroom] — an Android app module, typically.
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
     * Whether the component registry is generated from `commonMain` as well as compiled for the JVM,
     * so that every target the module has — Android and iOS included — can open the showroom.
     *
     * `false` by default, which is the shape viddik started with: fixtures live in the test source
     * set, the registry is generated there, and the only thing that can show it is the desktop window
     * `viddikShowroom` opens. A test source set is not compiled into an app, so that registry can
     * never reach a phone.
     *
     * Turning this on adds the processor to `kspCommonMainMetadata`, puts
     * `build/generated/ksp/metadata/commonMain/kotlin` on `commonMain`, and orders every compilation
     * after it. **The fixtures then have to live in `commonMain`**, not in the test source set — that
     * is the actual migration, and the rest is wiring.
     *
     * Goldens keep working: the JVM run over the test source set finds no fixtures of its own but
     * still emits the JUnit 5 class, over the registry `commonMain` produced, so `viddikVerify` and
     * `viddikRecord` are unchanged. Only [generateTests] interacts with this — the test class is
     * JUnit 5 and cannot be common, so it is emitted by the JVM run alone.
     */
    public val showroomTargets: Property<Boolean>

    /**
     * Version of the `io.github.youndie.viddik:viddik-*` artifacts to add when [addDependencies] is on.
     * Defaults to the plugin's own version, which is what keeps the processor and the engine in step.
     */
    public val viddikVersion: Property<String>
}
