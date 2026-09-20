@file:OptIn(
    ExperimentalCoroutinesApi::class,
    ExperimentalTestApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package io.github.youndie.viddik.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.LocalViddikCapture
import io.github.youndie.viddik.annotations.AUTO_HEIGHT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.Matrix44
import org.jetbrains.skia.Surface
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

public fun captureComposable(
    width: Int = DEFAULT_WIDTH,
    height: Int = AUTO_HEIGHT,
    compositionLocals: List<ProvidedValue<*>> = emptyList(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
): BufferedImage {
    val request = CaptureRequest(width, height, compositionLocals, fontScale, content)
    return if (sceneReuseEnabled) CaptureSession.capture(request) else request.captureInItsOwnScene()
}

/** One fixture's worth of what a capture needs to know. */
internal class CaptureRequest(
    val width: Int,
    val height: Int,
    val compositionLocals: List<ProvidedValue<*>>,
    val fontScale: Float,
    val content: @Composable () -> Unit,
) {
    val autoHeight: Boolean = height == AUTO_HEIGHT

    /** What the scene is laid out in; the surface it is drawn into can be shorter (issue #31). */
    val canvasHeight: Int = if (autoHeight) MAX_AUTO_HEIGHT_CANVAS else height

    internal companion object {
        /** The canvas a component will be laid out in, without building a request for it. */
        fun canvasHeightOf(height: Int): Int = if (height == AUTO_HEIGHT) MAX_AUTO_HEIGHT_CANVAS else height
    }
}

/** A scene of its own, torn down afterwards — what every capture did before `viddik.sceneReuse`. */
@OptIn(ExperimentalTestApi::class)
private fun CaptureRequest.captureInItsOwnScene(): BufferedImage {
    var captured: BufferedImage? = null
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try {
        runDesktopComposeUiTest(width = width, height = canvasHeight) {
            captured = (this as SkikoComposeUiTest).capture(this@captureInItsOwnScene, throughHarness = true)
        }
    } finally {
        Dispatchers.resetMain()
    }
    return captured ?: error("Screenshot capture produced no image")
}

/**
 * The capture itself, against a scene that already exists.
 *
 * [throughHarness] says how the content gets in: `setContent` on the test harness for a scene that
 * was just created for this one fixture, `ComposeScene.setContent` for a scene being reused. A
 * reused scene is also resized per fixture, which is what lets one scene serve fixtures of
 * different shapes.
 */
private fun SkikoComposeUiTest.capture(
    request: CaptureRequest,
    throughHarness: Boolean,
): BufferedImage {
    var measuredHeightPx = 0
    val fixture: @Composable () -> Unit = {
        // Only the *font* scale is overridden, never the density itself. A font scale changes how
        // large text draws inside a canvas of a given size, which is what @Preview.fontScale asks
        // for; changing the density would also change what a dp is worth, and the whole capture
        // path treats dp and pixel as the same unit (ViddikDensityTest pins that).
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, request.fontScale),
            // Provided before the caller's own locals, so a caller can still override it —
            // ViddikStableGlyphsTest turns it off to check the modifier costs nothing when no
            // capture is running.
            LocalViddikCapture provides true,
            *request.compositionLocals.toTypedArray(),
        ) {
            Box(
                Modifier
                    .width(request.width.dp)
                    .let { if (request.autoHeight) it else it.height(request.height.dp) }
                    .onGloballyPositioned { measuredHeightPx = it.size.height },
            ) {
                request.content()
            }
        }
    }

    if (throughHarness) {
        setContent(fixture)
    } else {
        // The window this scene lives in was opened at exactly this fixture's canvas, so there is
        // nothing to resize — see CaptureSession.start for why that matters.
        scene.setContent(content = fixture)
    }
    waitForIdle()

    if (glyphCheckEnabled) {
        val drawn = mutableListOf<String>()
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { drawn += it.text }
            }
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .forEach { node ->
                node.config.getOrNull(SemanticsProperties.EditableText)?.let { drawn += it.text }
            }
        failOnUncoveredGlyphs(drawn)
    }

    val roots = onAllNodes(isRoot()).fetchSemanticsNodes()
    // A second root means a Dialog or a Popup. Only a dialog is worth cropping to: it is a
    // window of its own and the scene around it is empty scrim. A popup — a dropdown menu, a
    // tooltip — is positioned inside the window and belongs in the image together with what it
    // is anchored to, so the whole scene is the capture. Asking for a dialog node
    // unconditionally is what this used to do, and it failed every popup fixture outright with
    // a message about dialogs (found by the Canary/Popup fixture).
    //
    // Read before the render rather than after it, because the answer decides how tall a
    // surface the render needs.
    val dialogNodes = onAllNodes(isDialog()).fetchSemanticsNodes()
    val dialogCapture = roots.size > 1 && dialogNodes.isNotEmpty()

    // An auto-height capture keeps `measuredHeightPx` of what it draws and throws the rest
    // away (the crop below), so that is all the surface has to be. The *scene* stays at
    // `canvasHeight`, so nothing about the layout changes — only the raster target, and with
    // it the PNG encode and the decode back, which is where the time was going: measured on
    // this repository's suite, 1353 ms of capture time became 764 ms, with all 28 goldens
    // byte-identical (issue #31). A dialog is the exception and keeps the whole canvas: it is
    // centred in the window rather than laid out from the top, and auto-height does not
    // measure it reliably in the first place.
    val surfaceHeight =
        if (request.autoHeight && !dialogCapture) {
            measuredHeightPx.coerceIn(1, request.canvasHeight)
        } else {
            request.canvasHeight
        }

    // Render the scene ourselves, into a canvas carrying the perspective nudge, instead of
    // captureToImage() on a node: a matrix on the scene's canvas reaches every layer of it,
    // including Dialog/Popup, which Compose renders into their own roots — a modifier on the
    // content node never reached those.
    val rendered = renderSceneWithPerspective(scene, request.width, surfaceHeight)

    val full: BufferedImage
    if (!dialogCapture) {
        full = rendered
    } else {
        // The dialog is drawn on top of the scene — crop it out by its semantics bounds.
        val dialogNode = onNode(isDialog()).fetchSemanticsNode()
        val bounds = dialogNode.boundsInWindow
        val left = bounds.left.toInt().coerceIn(0, rendered.width - 1)
        val top = bounds.top.toInt().coerceIn(0, rendered.height - 1)
        full =
            rendered.getSubimage(
                left,
                top,
                dialogNode.size.width.coerceIn(1, rendered.width - left),
                dialogNode.size.height.coerceIn(1, rendered.height - top),
            )
        measuredHeightPx = dialogNode.size.height
    }

    if (!request.autoHeight) return full

    check(measuredHeightPx < MAX_AUTO_HEIGHT_CANVAS) {
        "Content is taller than the auto-height ceiling ($MAX_AUTO_HEIGHT_CANVAS px) — pass an explicit " +
            "height to @ViddikScreenshot instead of relying on auto-height."
    }
    return full.getSubimage(
        0,
        0,
        request.width.coerceAtMost(full.width),
        measuredHeightPx.coerceIn(1, full.height),
    )
}

private const val SCENE_REUSE_PROPERTY = "viddik.sceneReuse"

internal val sceneReuseEnabled: Boolean
    get() = System.getProperty(SCENE_REUSE_PROPERTY)?.toBooleanStrictOrNull() == true

/**
 * One scene for the whole run, on a thread of its own, behind `viddik.sceneReuse`.
 *
 * Standing a scene up is what a capture mostly costs: an *empty* capture at a consumer-typical
 * canvas measured 11.7 ms against a median real fixture's 12.1 ms (#34). Reusing one takes a fixture
 * from ~16 ms to ~7 ms, and the spike in #40 found the pixels identical to a fresh capture —
 * including after resizing the scene between fixtures.
 *
 * It needs a thread because `runDesktopComposeUiTest` is a scoped block: the scene exists only
 * inside it, while the fixtures arrive one JUnit test at a time from outside. So one thread holds
 * the block open and serves capture requests from a queue, and the callers block on their answer.
 * Exactly one thread ever drives the harness — the hang measured in #35 came from several driving it
 * at once, and this does not re-open that.
 *
 * Every fixture goes through `ComposeScene.setContent`, including the first: the harness's own
 * `setContent` is used once, for empty content, so that no fixture is the special one. A fixture
 * whose rendering depends on what ran before it is the failure this design has to avoid, and
 * `ViddikSceneReuseTest` is what checks it did.
 */
private object CaptureSession {
    private class Job(
        val request: CaptureRequest?,
    ) {
        val answer: ArrayBlockingQueue<Result<BufferedImage>> = ArrayBlockingQueue(1)
    }

    private val lock = Any()
    private var jobs = LinkedBlockingQueue<Job>()
    private var worker: Thread? = null
    private var windowSize: IntSize? = null

    /**
     * What killed the scene, if anything did.
     *
     * Without this the first version deadlocked: the worker died inside the harness, nothing
     * answered, and every caller waited forever — a silent hang with no stack trace anywhere,
     * because an uncaught exception on a daemon thread goes to a stderr the test runner has taken
     * over.
     */
    @Volatile
    private var fatal: Throwable? = null

    fun capture(request: CaptureRequest): BufferedImage {
        val job = Job(request)
        synchronized(lock) {
            start(IntSize(request.width, request.canvasHeight))
            jobs.put(job)
        }
        val answer =
            job.answer.poll(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: throw IllegalStateException(
                    "The shared capture scene did not answer within ${ANSWER_TIMEOUT_SECONDS}s. " +
                        "Turn viddik.sceneReuse off to fall back to a scene per capture.",
                    fatal,
                )
        return answer.getOrThrow()
    }

    /**
     * Opens a scene for [size], reopening if the live one has another.
     *
     * The window has to match the fixture's canvas, and that is not a detail: a `Dialog` centres
     * itself in the *window*, not in the scene, so a dialog photographed in a window of the wrong
     * size is a different picture — measured at 49–56% of its pixels before this, and byte-identical
     * to a fresh capture after it. Sizes cluster in practice (a group of fixtures shares one), so a
     * run reopens a handful of times; a run that alternates sizes every fixture degenerates to one
     * scene per capture, which is exactly what it costs today.
     */
    private fun start(size: IntSize) {
        if (worker != null && windowSize == size && fatal == null) return
        stop()
        fatal = null
        jobs = LinkedBlockingQueue()
        windowSize = size
        worker =
            Thread({ serve(size) }, "viddik-capture").apply {
                isDaemon = true
                start()
            }
        registerShutdownHook()
    }

    private fun stop() {
        val running = worker ?: return
        val pill = Job(null)
        jobs.put(pill)
        pill.answer.poll(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        running.join(CLOSE_TIMEOUT_SECONDS * MILLIS_PER_SECOND)
        worker = null
        windowSize = null
    }

    /**
     * Ends the run's scene. Called by the trailing dynamic test of a reusing run, and by a shutdown
     * hook for anyone calling [captureComposable] directly — a scene left open holds the harness's
     * own threads, and a test JVM that will not exit is worse than a slow one.
     */
    fun close() {
        synchronized(lock) { stop() }
    }

    private fun registerShutdownHook() {
        if (shutdownHookRegistered) return
        shutdownHookRegistered = true
        Runtime.getRuntime().addShutdownHook(Thread({ close() }, "viddik-capture-close"))
    }

    @Volatile
    private var shutdownHookRegistered = false

    private fun serve(size: IntSize) {
        try {
            runScene(size)
        } catch (failure: Throwable) {
            fatal = failure
            // Everyone waiting, and everyone who arrives later, gets the reason rather than silence.
            generateSequence { jobs.poll() }.forEach { it.answer.offer(Result.failure(failure)) }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun runScene(size: IntSize) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            runDesktopComposeUiTest(width = size.width, height = size.height) {
                val harness = this as SkikoComposeUiTest
                // Empty content through the harness once, so that no fixture is the special one:
                // every fixture then arrives the same way, through ComposeScene.setContent.
                harness.setContent { }
                while (true) {
                    val job = jobs.take()
                    val request = job.request
                    if (request == null) {
                        job.answer.offer(Result.failure(IllegalStateException("closed")))
                        return@runDesktopComposeUiTest
                    }
                    job.answer.put(runCatching { harness.capture(request, throughHarness = false) })
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private const val CLOSE_TIMEOUT_SECONDS = 10L
    private const val ANSWER_TIMEOUT_SECONDS = 120L
    private const val MILLIS_PER_SECOND = 1000L
}

/** Ends the shared scene, if one was ever opened. Safe to call when reuse is off. */
internal fun closeCaptureSession() {
    if (sceneReuseEnabled) CaptureSession.close()
}

private const val GLYPH_CHECK_PROPERTY = "viddik.glyphCheck"
private const val GLYPH_CHECK_FONT_PROPERTY = "viddik.glyphCheckFont"

private val glyphCheckEnabled: Boolean
    get() = System.getProperty(GLYPH_CHECK_PROPERTY)?.toBooleanStrictOrNull() == true

/** The font the check reads, `viddik.glyphCheckFont` for a consumer that bundles its own. */
private val glyphCheckFont: ByteArray
    get() = System.getProperty(GLYPH_CHECK_FONT_PROPERTY)?.let { File(it).readBytes() } ?: robotoBytes

/**
 * Refuses to photograph text whose font cannot draw it.
 *
 * A glyph the font lacks is resolved by the host — Segoe UI Symbol here, DejaVu there — and the
 * result is a golden that is stable on the machine that recorded it and different on the next one,
 * discovered later as a fraction of a percent of drifting pixels somewhere near, but not at, the
 * character responsible. Issue #6 is two days spent on exactly that: the diff pointed at a button,
 * the cause was a `←` in the link beside it pushing everything two pixels left.
 *
 * Off unless asked for, because the check can only read one font and a consumer may legitimately
 * draw with another: pointing it at the wrong one would fail fixtures that are perfectly portable.
 * `viddik { glyphCheck = true }` turns it on for a module themed with `viddikTypography()`;
 * `glyphCheckFont` points it at the font a module bundles itself.
 */
private fun failOnUncoveredGlyphs(drawn: List<String>) {
    val font = glyphCheckFont
    val offenders = drawn.associateWith { ViddikGlyphCoverage.missingGlyphs(it, font) }.filterValues { it.isNotEmpty() }
    if (offenders.isEmpty()) return

    val codepoints =
        offenders.values
            .flatten()
            .toSortedSet()
            .joinToString { "U+%04X (%s)".format(it, String(Character.toChars(it))) }
    val where = offenders.keys.joinToString(", ") { "\"${it.take(60)}\"" }
    error(
        "Nothing in the font draws $codepoints, so the host would: $where. A golden recorded that " +
            "way is stable here and different on the next machine. Draw the character as an icon, " +
            "bundle a font that covers it, or point $GLYPH_CHECK_FONT_PROPERTY at the font this " +
            "module actually uses.",
    )
}

// Everything Skia draws except glyphs goes through its own scan converter, which is identical in every
// skiko build — shapes, gradients and shadows already come out byte-identical across platforms (verified).
// Glyphs are the exception: they are rasterized by the host font backend (CoreText / DirectWrite /
// FreeType), which no FontRasterizationSettings combination can reconcile. SkStrikeSpec::ShouldDrawAsPath()
// does have an escape hatch though — it gives up on the glyph mask cache when the canvas matrix carries
// perspective ("we don't cache perspective"), and fills the outlines with Skia's own rasterizer instead.
// A persp1 term of 1e-9 flips that switch: geometry shifts by ~1e-6 px (far below anything representable),
// while glyph rendering becomes platform-independent. Unconditional — unlike bundling a font, this costs
// nothing and changes nothing a reviewer would notice (it is not the same as disabling anti-aliasing).
//
// Applied to the scene's canvas rather than to a node, so it reaches every layer: content inside
// compositing layers (verified with alpha layers and elevated Cards) and Dialog/Popup roots alike.
// It has to be the scene: a modifier on the content node cannot reach a Dialog, which Compose renders
// into a root of its own — measured on a downstream consumer's suite, where dialog and bottom-sheet
// fixtures were the only cross-OS failures left (36 of 422) until this moved up to the scene.
//
// Spelled as sixteen positional arguments rather than a spread `*floatArrayOf(...)`: skiko 0.150.1
// turned Matrix44 into a value class, whose array constructor is no longer public — the sixteen-float
// form is the one that compiles against both the old vararg constructor and the new one.
// laid out as the 4x4 grid it is, rather than one float per line
@Suppress("ktlint:standard:argument-list-wrapping")
private val PERSPECTIVE_NUDGE =
    Matrix44(
        // identity, except for persp1 in the bottom row
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1e-9f, 0f, 1f,
    )

// The scene is drawn into a canvas of our own with the perspective already applied: unlike a modifier
// on a node, this covers every layer of the scene, Dialog and Popup roots included.
private fun renderSceneWithPerspective(
    scene: ComposeScene,
    width: Int,
    height: Int,
): BufferedImage {
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.concat(PERSPECTIVE_NUDGE)
    // Compose Multiplatform 1.12 dropped ComposeScene.render(canvas, nanoTime) in favour of the two
    // halves it used to combine. The frame time it took is not missed here: the test harness has
    // already been driven to idle by waitForIdle() before this runs, so there is no animation left to
    // advance — all that is needed is a settled layout and one draw.
    scene.measureAndLayout()
    scene.draw(surface.canvas.asComposeCanvas())
    val encoded = checkNotNull(surface.makeImageSnapshot().encodeToData()) { "Screenshot encoding failed" }
    return ImageIO.read(ByteArrayInputStream(encoded.bytes))
}

public const val DEFAULT_WIDTH: Int = 400

private const val MAX_AUTO_HEIGHT_CANVAS = 4000
