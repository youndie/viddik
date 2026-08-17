package ru.workinprogress.viddik.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The names here are the whole reason the plugin exists — each one was copied by hand into four
 * consumers, and each one fails quietly when it's wrong (a `kspTest` dependency on a KMP module
 * generates nothing, and the screenshot task then passes with no tests in it).
 *
 * The expected values are the ones real consumer modules spelled out by hand before the plugin
 * existed — a `jvm("desktop")` target, an unnamed `jvm()` (as in viddik's own `viddik-testing-core`)
 * and a plain `kotlin("jvm")` module.
 */
class ViddikLayoutTest {
    @Test
    fun `named jvm target maps to the desktopTest names`() {
        val layout = ViddikLayout.forMultiplatform(targetName = "desktop", testSourceSetName = "desktopTest")

        assertEquals("kspDesktopTest", layout.kspConfigurationName)
        assertEquals("build/generated/ksp/desktop/desktopTest/kotlin", layout.generatedSourceDir)
        assertEquals("src/desktopTest/snapshots", layout.defaultSnapshotsDir)
        assertEquals("desktopTestImplementation", layout.implementationConfigurationName)
        assertEquals("desktopTestRuntimeOnly", layout.runtimeOnlyConfigurationName)
        assertEquals("desktopTestClasses", layout.testClassesTaskName)
        assertEquals("desktopTest", layout.testTaskName)
    }

    @Test
    fun `unnamed jvm target maps to the jvmTest names`() {
        val layout = ViddikLayout.forMultiplatform(targetName = "jvm", testSourceSetName = "jvmTest")

        assertEquals("kspJvmTest", layout.kspConfigurationName)
        assertEquals("build/generated/ksp/jvm/jvmTest/kotlin", layout.generatedSourceDir)
        assertEquals("src/jvmTest/snapshots", layout.defaultSnapshotsDir)
        assertEquals("jvmTestImplementation", layout.implementationConfigurationName)
        assertEquals("jvmTest", layout.testTaskName)
    }

    @Test
    fun `plain jvm module uses the plain test names`() {
        val layout = ViddikLayout.forJvm()

        assertEquals("kspTest", layout.kspConfigurationName)
        assertEquals("build/generated/ksp/test/kotlin", layout.generatedSourceDir)
        assertEquals("src/test/snapshots", layout.defaultSnapshotsDir)
        assertEquals("testImplementation", layout.implementationConfigurationName)
        assertEquals("testRuntimeOnly", layout.runtimeOnlyConfigurationName)
        assertEquals("testClasses", layout.testClassesTaskName)
        assertEquals("test", layout.testTaskName)
    }

    @Test
    fun `a KMP consumer takes the base coordinates and a plain jvm one the platform artifacts`() {
        val multiplatform = ViddikLayout.forMultiplatform("desktop", "desktopTest").coordinates
        assertEquals("ru.workinprogress:viddik-annotations", multiplatform.annotations)
        assertEquals("ru.workinprogress:viddik-testing-core", multiplatform.testingCore)

        val jvm = ViddikLayout.forJvm().coordinates
        // The suffixes differ because viddik's own two KMP modules name their JVM targets
        // differently: `jvm("desktop")` in annotations, an unnamed `jvm()` in testing-core.
        assertEquals("ru.workinprogress:viddik-annotations-desktop", jvm.annotations)
        assertEquals("ru.workinprogress:viddik-testing-core-jvm", jvm.testingCore)

        // The processor is a plain jvm module either way.
        assertEquals("ru.workinprogress:viddik-processor", multiplatform.processor)
        assertEquals("ru.workinprogress:viddik-processor", jvm.processor)
    }

    @Test
    fun `a target whose test source set was renamed follows the source set, not the target`() {
        val layout = ViddikLayout.forMultiplatform(targetName = "desktop", testSourceSetName = "desktopScreenshots")

        assertEquals("src/desktopScreenshots/snapshots", layout.defaultSnapshotsDir)
        assertEquals("desktopScreenshotsImplementation", layout.implementationConfigurationName)
        // KSP names its configuration after the target and the compilation, not the source set.
        assertEquals("kspDesktopTest", layout.kspConfigurationName)
    }
}
