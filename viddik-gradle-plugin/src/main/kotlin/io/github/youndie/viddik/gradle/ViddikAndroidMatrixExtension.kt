package io.github.youndie.viddik.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the `io.github.youndie.viddik.android-matrix` plugin, available as
 * `viddikAndroidMatrix { }`.
 *
 * The matrix is one device profile run on several API levels, so that the API level is the only thing
 * that changes from column to column. Everything has a default; an empty block is the normal case.
 */
public interface ViddikAndroidMatrixExtension {
    /**
     * The API levels to capture on, one at a time, one Gradle-managed emulator each. Default
     * `[29, 31, 34, 36]`: either side of the levels where Compose's rendering is known to branch
     * (RenderEffect at 31, non-linear font scaling at 34), and no lower than a managed device can
     * capture. A level below 29 needs an emulator started by hand; see docs/android-matrix.md.
     *
     * 24 is the floor. From 26 a capture is `captureToImage()` (PixelCopy of the window); on 24 and 25,
     * which have no PixelCopy, it is a screenshot of the display cut down to the fixture.
     * `-PviddikMatrix.apiLevels=34,36` overrides this for one run.
     */
    public val apiLevels: ListProperty<Int>

    /**
     * The hardware profile every emulator uses, by its `avdmanager` name. Default `"Pixel 6"`, whose safe
     * area is about 817 dp tall; a fixture that does not fit fails by name, so a suite of 852 dp screens
     * wants a taller one. `-PviddikMatrix.device="Pixel 9 Pro XL"` overrides this for one run.
     */
    public val device: Property<String>

    /**
     * Which system images to use: `"aosp"`, `"aosp-atd"`, `"google"` or `"google-atd"`. Default
     * `"aosp"`, the one that exists for every API level in the default list.
     */
    public val systemImageSource: Property<String>

    /** The column every other one is compared with. Default: the highest of [apiLevels]. */
    public val referenceApi: Property<Int>
}
