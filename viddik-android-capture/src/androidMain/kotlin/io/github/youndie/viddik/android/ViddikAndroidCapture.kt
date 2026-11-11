@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)

package io.github.youndie.viddik.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.youndie.viddik.annotations.AUTO_HEIGHT
import io.github.youndie.viddik.annotations.ViddikComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours

/**
 * Captures a module's fixtures through the device's own renderer, for the Android matrix.
 *
 * The JVM path (`CaptureEngine`) goes out of its way to take the platform out of a render, so that
 * a golden recorded on one OS verifies on another. This does the opposite on purpose: the point of
 * running on an emulator of a given API level is to see what *that* platform's HWUI, Skia, Minikin
 * and fonts make of the fixture. Nothing here compares anything — the host-side report does that,
 * across API levels — and nothing here is used by `viddikVerify`/`viddikRecord`.
 *
 * Called from the one test the `io.github.youndie.viddik.android-matrix` plugin generates:
 *
 * ```kotlin
 * @Test fun captureAll() = ViddikAndroidCapture.captureAll(GeneratedViddikRegistry.components)
 * ```
 *
 * **One test and one activity for the whole registry**, the fixtures taking turns in one composition.
 * The first version was a `Parameterized` test per fixture: a median of 1.12 s each, which on a suite of
 * 876 fixtures is 17 minutes per API level, spent on what surrounds a capture — an activity launched and
 * finished, and the test runner's per-test work — rather than on the capture. It has to be one test and
 * not a rule shared across tests, because `AndroidJUnitRunner` finishes every activity after each test.
 */
public object ViddikAndroidCapture {
    /** The node the capture is taken from: the fixture's own box, not the window around it. */
    public const val ROOT_TAG: String = "viddik-android-capture-root"

    /**
     * The instrumentation argument AGP passes when a test run collects additional output — the
     * directory it pulls back to `build/outputs/…additional_output…` once the run is over. The
     * same one androidx.benchmark writes its traces to.
     */
    public const val OUTPUT_DIR_ARGUMENT: String = "additionalTestOutputDir"

    /** Instrumentation argument: capture only the fixtures whose `group - name` contains this. */
    public const val FILTER_ARGUMENT: String = "viddikFilter"

    private const val LOG_TAG = "viddik"

    /** `captureToImage()` is PixelCopy of the window, which arrived in API 26; below it, see [captureFromScreen]. */
    private const val PIXEL_COPY_API = 26

    /**
     * A dialog is another window, and `captureToImage()` copies one only from API 28 ("Cannot currently
     * capture dialogs on API lower than 28!", measured on 27). See [captureDialog] for the levels below.
     */
    private const val DIALOG_CAPTURE_TO_IMAGE_API = 28

    /** Several frames at 60 Hz: enough for the last one Compose drew to be the one on screen. */
    private const val SCREEN_SETTLE_MS = 250L

    private const val PIXEL_COPY_TIMEOUT_S = 5L

    /** See [redrawLayers]: one pass can leave a layer that reads another one pass behind. */
    private const val REDRAW_PASSES = 2
    private const val DRAW_TIMEOUT_S = 2L

    /** How many times a dialog's screenshots are retaken while it is still moving. */
    private const val SCREEN_STABLE_TRIES = 3

    /** How long a keyboard a fixture raised gets to go away before the next fixture is drawn. */
    private const val KEYBOARD_GONE_POLLS = 40
    private const val KEYBOARD_POLL_MS = 25L

    private const val NO_FIXTURE = -1

    /**
     * Captures every fixture, and fails at the end, naming them, if any could not be.
     *
     * A fixture that throws takes its composition with it, so the next one gets a fresh activity: one
     * broken fixture costs one capture and one launch, not the rest of the run. One that merely does not
     * fit costs nothing, and the fixtures after it stay where they are.
     */
    public fun captureAll(registry: List<ViddikComponent>) {
        val components = selected(registry, InstrumentationRegistry.getArguments().getString(FILTER_ARGUMENT))
        val dir = outputDirectory()
        writeDeviceFile(dir)
        File(dir, EXPECTED_FILE).writeText(components.joinToString("") { expectedLine(it) + "\n" })
        val failuresFile = File(dir, FAILURES_FILE).apply { writeText("") }
        val failures = mutableListOf<String>()

        fun fail(
            component: ViddikComponent,
            cause: Throwable,
        ) {
            Log.w(LOG_TAG, "No capture of ${component.group} - ${component.name}", cause)
            failuresFile.appendText(failureLine(component, cause.message ?: cause.toString()) + "\n")
            failures += "${component.group} - ${component.name}: ${cause.message ?: cause}"
        }

        var next = 0
        while (next < components.size) {
            var started = NO_FIXTURE
            try {
                runAndroidComposeUiTest<ComponentActivity>(
                    effectContext = UnconfinedTestDispatcher(),
                    testTimeout = RUN_TIMEOUT,
                ) {
                    val stage = Stage(components)
                    runOnIdle {
                        val host = checkNotNull(activity)
                        // Edge to edge on every level, as an app that calls this itself is. The platform
                        // enforces it from 35 and only there, so without this the host's window differed
                        // between columns: below 35 `WindowInsets.navigationBars` read zero, and every
                        // bottom sheet padding itself by it sat a bar lower than on 36 (measured: 64 fixtures
                        // past 5% before this line and 2 after), which is the harness and not the OS.
                        host.enableEdgeToEdge()
                        // A window that cannot take focus. A focused one raises a keyboard for a field
                        // that asks for focus, and a capture then catches the inset animation half way
                        // (measured: text squashed, a grey band over the screen), or a cursor at either end
                        // of its blink. With an activity per fixture the window did not get focus in time,
                        // by luck.
                        host.window.addFlags(FLAG_NOT_FOCUSABLE)
                    }
                    setContent { stage.Content() }
                    while (next < components.size) {
                        started = next++
                        try {
                            captureOne(stage, started, dir)
                        } catch (rejected: FixtureDoesNotFit) {
                            fail(components[started], rejected)
                        }
                        started = NO_FIXTURE
                        clearStage(stage)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") broken: Throwable,
            ) {
                // The fixture that was on stage, if one was. Otherwise it is the harness that failed, and
                // the run has to say so rather than loop on it.
                if (started == NO_FIXTURE) throw broken
                fail(components[started], broken)
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("${failures.size} fixture(s) not captured:\n" + failures.joinToString("\n"))
        }
    }

    /** The one composition every fixture of a run takes its turn in. */
    private class Stage(
        private val components: List<ViddikComponent>,
    ) {
        var current by mutableIntStateOf(NO_FIXTURE)
        var available = IntSize.Zero
            private set

        @Composable
        fun Content() {
            val index = current
            if (index == NO_FIXTURE) return
            val component = components[index]
            // `required*`, so a fixture wider than the device is laid out at its own width and caught
            // below, instead of quietly squeezed into the window.
            val size =
                Modifier.requiredWidth(component.width.dp).let {
                    if (component.height == AUTO_HEIGHT) it else it.requiredHeight(component.height.dp)
                }
            // A fixture of its own in the slot table: nothing it remembered reaches the next one.
            key(index) {
                // The font scale goes through a real Configuration, not through `Density(density,
                // fontScale)` as on the JVM: from API 34 the platform scales text non-linearly, and only
                // the Configuration path reaches the converter that does it. That difference is exactly
                // the kind of thing the matrix is for.
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(component.fontScale)) {
                    // Inside the safe area, so neither a system bar nor the contrast scrim edge-to-edge
                    // draws over the navigation bar ever lands in a capture.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .onGloballyPositioned { available = it.size },
                    ) {
                        // The tag goes INSIDE `required*`. A node outside it sees the size coerced to the
                        // parent's constraints, so the check below would never fire, while `required*`
                        // centres the overflow: measured on a 852 dp fixture, the capture was silently cut
                        // at both ends, by an amount that depended on the level's system bars.
                        Box(size.testTag(ROOT_TAG)) {
                            component.content()
                        }
                    }
                }
            }
        }

        fun component(index: Int): ViddikComponent = components[index]
    }

    private fun AndroidComposeUiTest<ComponentActivity>.captureOne(
        stage: Stage,
        index: Int,
        dir: File,
    ) {
        val component = stage.component(index)
        // With one test for the whole registry, this line is what says how far a run that died had got.
        Log.i(LOG_TAG, "Capturing ${index + 1}: ${component.group} - ${component.name}")
        runOnIdle { stage.current = index }
        waitForIdle()

        // A Dialog is a window of its own, and it is the capture — the same rule CaptureEngine follows.
        val dialog = onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        val node = if (dialog) onNode(isDialog()) else onNodeWithTag(ROOT_TAG)
        if (!dialog) failIfItDoesNotFit(component, onNodeWithTag(ROOT_TAG).fetchSemanticsNode().size, stage.available)

        val bitmap =
            when {
                dialog -> captureDialog(node)
                Build.VERSION.SDK_INT >= PIXEL_COPY_API -> node.captureToImage().asAndroidBitmap()
                else -> settled { node.captureFromScreen() }
            }
        File(dir, matrixFileNameFor(component)).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode the capture as PNG" }
        }
    }

    /**
     * Empties the stage between two fixtures, so that nothing of one is there when the next one draws: its
     * dialog's window, and a keyboard its focused field raised, which would otherwise shrink the safe area
     * and show up in the next capture. A fresh activity used to take care of both.
     */
    private fun AndroidComposeUiTest<ComponentActivity>.clearStage(stage: Stage) {
        // Below API 28 a new window always hands focus to its first focusable view, so a sheet with a text
        // field comes up with the keyboard. That keyboard belongs to the sheet's window, and only that window
        // can ask for it to go: the host takes no focus, so once the sheet is gone nobody can, and the next
        // fixture is laid out above a keyboard with nothing behind it (measured on 24 and 26: "does not fit
        // the 1080x1436 safe area" for the full-height fixtures after such a sheet).
        onAllNodes(isDialog()).fetchSemanticsNodes().firstOrNull()?.let { dialog ->
            val view = (dialog.root as ViewRootForTest).view
            val dialogWindow = view.dialogWindow()
            runOnIdle { WindowCompat.getInsetsController(dialogWindow, view).hide(WindowInsetsCompat.Type.ime()) }
        }
        val window = checkNotNull(activity) { "The activity is gone" }.window

        fun keyboardShown(): Boolean =
            runOnIdle {
                ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        awaitKeyboardGone(::keyboardShown)
        runOnIdle { stage.current = NO_FIXTURE }
        waitForIdle()
        if (!keyboardShown()) return
        runOnIdle { WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        awaitKeyboardGone(::keyboardShown)
    }

    private fun awaitKeyboardGone(keyboardShown: () -> Boolean) {
        repeat(KEYBOARD_GONE_POLLS) {
            if (!keyboardShown()) return
            SystemClock.sleep(KEYBOARD_POLL_MS)
        }
        Log.w(LOG_TAG, "The keyboard did not go away; the next capture may show its effect")
    }

    /**
     * A dialog's window, as nearly the same thing on every level as each level allows: what the window drew,
     * with its transparency and without what is behind it, and with the system bars painted over.
     *
     * Three routes to the first part, because `captureToImage()` refuses a dialog below 28 and PixelCopy
     * starts at 26. Without this a full-screen dialog (a bottom sheet) below 28 kept everything on the screen
     * (bars with a running clock, the dimmed activity behind): 80-95% "different" for that reason alone.
     */
    private fun AndroidComposeUiTest<ComponentActivity>.captureDialog(node: SemanticsNodeInteraction): Bitmap {
        if (Build.VERSION.SDK_INT < DIALOG_CAPTURE_TO_IMAGE_API) keepKeyboardAway(node)
        val window =
            when {
                Build.VERSION.SDK_INT >= DIALOG_CAPTURE_TO_IMAGE_API -> node.captureToImage().asAndroidBitmap()
                Build.VERSION.SDK_INT >= PIXEL_COPY_API -> node.copyDialogWindow()
                else -> captureDialogFromScreen(node)
            }
        return node.withSystemBarsCovered(window)
    }

    /**
     * Below 28 a new window hands focus to its first focusable view, so a sheet with a text field raises the
     * keyboard by itself, which from 28 it does not. When it does was a matter of luck: across two runs of
     * API 24 one such sheet came out once lifted by the keyboard and once not, 75% of its pixels apart, and the
     * next fixture's timing was all that had changed. So the dialog's window is barred from the keyboard
     * (`FLAG_ALT_FOCUSABLE_IM`, which also stops one that has not come up yet), and a keyboard already up is
     * sent away and waited out. The field keeps its focus; only the keyboard is taken out.
     */
    private fun AndroidComposeUiTest<ComponentActivity>.keepKeyboardAway(node: SemanticsNodeInteraction) {
        val view = (node.fetchSemanticsNode("Failed to capture a dialog").root as ViewRootForTest).view
        val dialogWindow = view.dialogWindow()
        runOnIdle {
            dialogWindow.addFlags(FLAG_ALT_FOCUSABLE_IM)
            WindowCompat.getInsetsController(dialogWindow, view).hide(WindowInsetsCompat.Type.ime())
        }
        awaitKeyboardGone {
            runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        }
        waitForIdle()
    }

    /**
     * PixelCopy of the dialog's window, which is what `captureToImage()` does for one from 28.
     *
     * The source rectangle is the part it takes care of. A window with a shadow has a surface larger than
     * itself, and on 26 and 27 the rectangle is read in the surface's coordinates: a centred dialog came out
     * 84 px (32 dp) too far down and right and cut off on those two sides, though a full-screen sheet, with
     * no such margin, was fine. Asking for no rectangle does not help either, it scales the whole surface,
     * margin included, into the bitmap.
     */
    @RequiresApi(PIXEL_COPY_API)
    private fun SemanticsNodeInteraction.copyDialogWindow(): Bitmap {
        val node = fetchSemanticsNode("Failed to capture a dialog")
        val view = (node.root as ViewRootForTest).view
        val window = view.dialogWindow()
        val origin = IntArray(2).also { view.getLocationInWindow(it) }
        val margin = surfaceMargin(window.decorView)
        val left = origin[0] + node.positionInRoot.x.roundToInt() + margin.left
        val top = origin[1] + node.positionInRoot.y.roundToInt() + margin.top
        val source = Rect(left, top, left + node.size.width, top + node.size.height)
        return settled {
            node.redrawLayers()
            settleOnScreen()
            copyWindow(window, source)
        }
    }

    /**
     * How far a window's own area sits inside its surface. Hidden API, reached by reflection, on two
     * released levels that will not change and that predate the restrictions on it.
     */
    private fun surfaceMargin(decor: View): Rect {
        val root = View::class.java.getMethod("getViewRootImpl").invoke(decor)
        val attributes =
            checkNotNull(root) { "A dialog's window that is not attached" }
                .javaClass
                .getDeclaredField("mWindowAttributes")
                .apply { isAccessible = true }
                .get(root)
        return WindowManager.LayoutParams::class.java.getField("surfaceInsets").get(attributes) as Rect
    }

    @RequiresApi(PIXEL_COPY_API)
    private fun copyWindow(
        window: Window,
        source: Rect,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
        val thread = HandlerThread("viddik-pixel-copy").apply { start() }
        try {
            val done = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(
                window,
                source,
                bitmap,
                {
                    result = it
                    done.countDown()
                },
                Handler(thread.looper),
            )
            check(done.await(PIXEL_COPY_TIMEOUT_S, TimeUnit.SECONDS)) { "PixelCopy did not answer" }
            check(result == PixelCopy.SUCCESS) { "PixelCopy of a dialog's window failed with $result" }
        } finally {
            thread.quitSafely()
        }
        return bitmap
    }

    /**
     * Takes a capture until two in a row agree. A fixed wait alone lost a race below 28: one sheet in 658
     * came out on 27 without its pinned button, between two runs that agreed on everything else. Nothing
     * reports that a composition has reached the screen, and from 28 `captureToImage()` forces a redraw.
     */
    private fun settled(take: () -> Bitmap): Bitmap {
        var last = take()
        repeat(SCREEN_STABLE_TRIES) {
            val next = take()
            if (next.sameAs(last)) return next
            last = next
        }
        Log.w(LOG_TAG, "The capture was still changing after ${SCREEN_STABLE_TRIES + 1} tries; keeping the last one")
        return last
    }

    /**
     * Below PixelCopy a screenshot is all there is, and it is composited. Taking it twice with nothing but
     * black and then white behind the dialog recovers what the window drew (see [pixelFromComposites]), so
     * this level's scrim is a translucent one like everywhere else and not a grey one.
     */
    private fun AndroidComposeUiTest<ComponentActivity>.captureDialogFromScreen(
        node: SemanticsNodeInteraction,
    ): Bitmap {
        val host = checkNotNull(activity) { "The activity is gone" }
        val content = host.findViewById<View>(android.R.id.content)
        val background = host.window.decorView.background
        val dialogWindow =
            (node.fetchSemanticsNode("Failed to capture a dialog").root as ViewRootForTest).view.dialogWindow()

        fun over(color: Int): Bitmap {
            runOnIdle { host.window.setBackgroundDrawable(ColorDrawable(color)) }
            return node.captureFromScreen()
        }
        runOnIdle {
            dialogWindow.clearFlags(FLAG_DIM_BEHIND)
            content.alpha = 0f
        }
        try {
            // Black, white, and black again: anything that moved in between would be recovered as two
            // ghosts of itself. Measured on 24: a keyboard coming up between the two shots left a sheet
            // half transparent in both of its positions, the same way on every run.
            var overBlack = over(Color.BLACK)
            var overWhite = over(Color.WHITE)
            for (attempt in 1..SCREEN_STABLE_TRIES) {
                val again = over(Color.BLACK)
                if (again.sameAs(overBlack)) break
                overBlack = again
                overWhite = over(Color.WHITE)
            }
            val width = overBlack.width
            val height = overBlack.height
            val black = IntArray(width * height).also { overBlack.getPixels(it, 0, width, 0, 0, width, height) }
            val white = IntArray(width * height).also { overWhite.getPixels(it, 0, width, 0, 0, width, height) }
            val pixels = IntArray(width * height) { pixelFromComposites(black[it], white[it]) }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } finally {
            runOnIdle {
                host.window.setBackgroundDrawable(background)
                content.alpha = 1f
            }
        }
    }

    /**
     * See [coverSystemBars]. A dialog that stays clear of the bars comes back untouched.
     *
     * A keyboard counts as a bar: it is another window, in a screenshot its keys are in the capture and were
     * caught half way up (on API 24, two runs of one fixture differed by 38%), and a sheet is laid out above it.
     */
    private fun SemanticsNodeInteraction.withSystemBarsCovered(capture: Bitmap): Bitmap {
        val node = fetchSemanticsNode("Failed to capture a dialog")
        val view = (node.root as ViewRootForTest).view
        val insets = ViewCompat.getRootWindowInsets(view) ?: return capture
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
        val nodeTop = IntArray(2).also { view.getLocationOnScreen(it) }[1] + node.positionInRoot.y.roundToInt()

        @Suppress("DEPRECATION") // WindowMetrics arrived in 30, and this runs from 24.
        val screenHeight = Point().also { view.display.getRealSize(it) }.y
        val status = if (Build.VERSION.SDK_INT < PIXEL_COPY_API) bars.top else 0
        val top = (status - nodeTop).coerceAtLeast(0)
        val bottomInset = maxOf(bars.bottom, keyboard.bottom)
        val bottom = (nodeTop + capture.height - (screenHeight - bottomInset)).coerceAtLeast(0)
        if (top == 0 && bottom == 0) return capture
        val width = capture.width
        val height = capture.height
        val pixels = IntArray(width * height).also { capture.getPixels(it, 0, width, 0, 0, width, height) }
        coverSystemBars(pixels, width, height, top, bottom)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** The window a dialog's compose view lives in, which is not the activity's. */
    private fun View.dialogWindow(): Window =
        generateSequence<ViewParent>(this as? ViewParent ?: parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
            ?.window
            ?: error("A dialog's semantics node with no dialog window above it")

    /**
     * Has every Compose layer of [this] node's window recorded again, twice, with a drawn frame after each.
     *
     * Below 28 a capture is a screenshot or a PixelCopy of what is already on screen, and nothing redraws the
     * window for it. A layer that draws from another layer's recording and is not told when that recording
     * changes (glass over a backdrop) can be left a frame behind, and then stays behind: Compose is idle,
     * every frame is the same, and [settled] agrees with it. Measured on a sheet whose pinned button is drawn
     * that way: once in 658 captures on 24 and once on 27, across three runs, the button and its scrim were
     * not in the picture.
     *
     * It changes glass too, and that is not settled. Measured on 24, the redraw also turned a glass segmented
     * control's track darker and flatter on 4 captures (1.6-2.3%), against its earlier capture and API 26.
     * A second pass, on the theory that the glass had been recorded before its backdrop, changed nothing:
     * byte for byte the same captures. What the redraw gives is the same picture on every run, which the
     * screen as it happened to be did not; which of the two pictures that glass should be is open.
     */
    private fun SemanticsNode.redrawLayers() {
        val root = root as ViewRootForTest
        repeat(REDRAW_PASSES) { awaitDraw(root) }
    }

    /** Re-records [root]'s layers and returns once the window has drawn a frame with them. */
    private fun awaitDraw(root: ViewRootForTest) {
        val drawn = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = root.view
            val listener =
                object : ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        drawn.countDown()
                        view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                    }
                }
            view.viewTreeObserver.addOnDrawListener(listener)
            root.invalidateDescendants()
            view.invalidate()
        }
        if (!drawn.await(DRAW_TIMEOUT_S, TimeUnit.SECONDS)) Log.w(LOG_TAG, "No frame was drawn after a redraw")
    }

    /**
     * `waitForIdle()` returns once Compose has drawn, not once SurfaceFlinger has shown that frame, and a
     * copy taken in between is the previous fixture. Nothing reports the composition, so wait.
     */
    private fun settleOnScreen() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(SCREEN_SETTLE_MS)
    }

    /**
     * The capture below API 26, where `captureToImage()` has no PixelCopy to stand on: a screenshot of the
     * whole display, cut down to the node. A dialog goes through [captureDialogFromScreen] first.
     *
     * Not `View.draw()` into a software Bitmap, though that is the obvious substitute. A software canvas
     * is drawn by Skia's raster backend, not by HWUI: anti-aliasing, shadows and hardware layers would
     * all come out of a different renderer, and the API 24 column would then differ from the reference
     * for a reason that has nothing to do with API 24. `UiAutomation.takeScreenshot()` (API 18) returns
     * what SurfaceFlinger composited — the pixels the device itself put on screen — which is what
     * PixelCopy returns on 26+ for an opaque window. The difference left is that the screen is
     * composited: a translucent pixel of the node shows what is behind it, here the window background.
     */
    private fun SemanticsNodeInteraction.captureFromScreen(): Bitmap {
        val node = fetchSemanticsNode("Failed to capture a node from the screen")
        val view = (node.root as ViewRootForTest).view
        val origin = IntArray(2).also { view.getLocationOnScreen(it) }
        val position = node.positionInRoot
        val left = origin[0] + position.x.roundToInt()
        val top = origin[1] + position.y.roundToInt()
        node.redrawLayers()
        settleOnScreen()
        val screen =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .takeScreenshot()
                ?: error("UiAutomation.takeScreenshot() returned nothing on API ${Build.VERSION.SDK_INT}")
        check(
            left >= 0 && top >= 0 && left + node.size.width <= screen.width && top + node.size.height <= screen.height,
        ) {
            "The node at ($left, $top) ${node.size} is not inside the ${screen.width}x${screen.height} screenshot"
        }
        return Bitmap.createBitmap(screen, left, top, node.size.width, node.size.height)
    }

    /** A fixture PixelCopy cannot photograph whole; it costs a capture, not the composition. */
    private class FixtureDoesNotFit(
        message: String,
    ) : IllegalStateException(message)

    private fun failIfItDoesNotFit(
        component: ViddikComponent,
        size: IntSize,
        available: IntSize,
    ) {
        // PixelCopy can only copy what is on screen, so a fixture that runs past the safe area would be
        // cut off — a capture that looks like a layout bug and is not one.
        val autoHeightHitTheBottom = component.height == AUTO_HEIGHT && size.height >= available.height
        val fits = size.width <= available.width && size.height <= available.height
        if (fits && !autoHeightHitTheBottom) return
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density
        throw FixtureDoesNotFit(
            "${size.width}x${size.height} px does not fit the ${available.width}x${available.height} px safe area " +
                "of this device (density $density). Pick a larger `device` in viddikAndroidMatrix { }, or give " +
                "the fixture an explicit size that fits.",
        )
    }

    private fun outputDirectory(): File {
        val argument = InstrumentationRegistry.getArguments().getString(OUTPUT_DIR_ARGUMENT)
        val root =
            if (argument != null) {
                File(argument)
            } else {
                // Run outside Gradle (from the IDE, say), or AGP did not find the directory on the device
                // and so will not pull anything back: somewhere the app may write, said loudly in logcat,
                // because the second case otherwise looks like a run that passed and captured nothing.
                val fallback =
                    InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
                        ?: error(
                            "No $OUTPUT_DIR_ARGUMENT argument and no external files directory to write captures to",
                        )
                Log.w(LOG_TAG, "No $OUTPUT_DIR_ARGUMENT argument: captures go to $fallback and will not be pulled back")
                fallback
            }
        return File(root, MATRIX_SUBDIR).apply { mkdirs() }.also { Log.i(LOG_TAG, "Captures go to $it") }
    }

    private fun writeDeviceFile(dir: File) {
        val metrics =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics
        File(dir, DEVICE_FILE).writeText(
            deviceJson(
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE,
                model = Build.MODEL,
                density = metrics.density,
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
            ),
        )
    }

    /** A wedged run should end in a failure and not in a CI timeout; no real suite comes near this. */
    private val RUN_TIMEOUT = 4.hours
}

/** Hand-written rather than a JSON library: six fields, and nothing else in the test APK needs one. */
internal fun deviceJson(
    sdkInt: Int,
    release: String,
    model: String,
    density: Float,
    widthPx: Int,
    heightPx: Int,
): String =
    buildString {
        append("{")
        append("\"sdkInt\":").append(sdkInt).append(',')
        append("\"release\":").append(release.jsonString()).append(',')
        append("\"model\":").append(model.jsonString()).append(',')
        append("\"density\":").append(density).append(',')
        append("\"widthPx\":").append(widthPx).append(',')
        append("\"heightPx\":").append(heightPx)
        append("}\n")
    }

private fun String.jsonString(): String =
    buildString {
        append('"')
        for (c in this@jsonString) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
