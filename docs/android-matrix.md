# The Android matrix

viddik's goldens are rendered on the JVM through Skiko, and everything platform-specific is kept out
of them on purpose — that is why a golden recorded on macOS verifies on Linux. The same choice means a
golden says nothing about how a fixture looks on a given Android release: Compose ships inside the
APK, but what it draws with does not. The device's HWUI, Skia, Minikin and fonts come with the OS, and
some of what Compose does branches on the API level — `Modifier.blur` and `RenderEffect` from 31 (a
no-op below), AGSL from 33, non-linear font scaling from 34, shadows for non-convex outlines from 30.

The matrix is a separate, opt-in tool for that question. It captures the module's fixtures on
Gradle-managed emulators of several API levels and writes one page with a column per level, each
compared against a reference level. It records no goldens and fails on no difference: platforms differ,
and the page shows where and by how much. It fails only when a capture did not come back.

```kotlin
plugins {
    id("io.github.youndie.viddik") version "<VERSION>"
    id("io.github.youndie.viddik.android-matrix") version "<VERSION>"
}

viddik { showroomTargets = true }
```

Both ids are in one artifact, so give them the same version: Gradle loads that artifact once, and with two
versions one of them decides for both. A version catalog makes that one line.

```bash
./gradlew :yourModule:viddikAndroidMatrix                            # → build/reports/viddik-matrix/index.html
./gradlew :yourModule:viddikAndroidMatrix -PviddikMatrix.apiLevels=34   # one emulator, for a quick look
./gradlew :yourModule:viddikAndroidMatrix "-PviddikMatrix.device=Pixel 9 Pro XL"   # another profile, for one run
./gradlew :yourModule:viddikAndroidMatrix -PviddikMatrix.filter=Buttons  # the fixtures whose "group - name" matches
```

On a machine with a GPU, also put this in `gradle.properties`; see [Which renderer](#which-renderer):

```properties
android.testoptions.manageddevices.emulator.gpu=host
```

Nothing changes for `viddikVerify`, `viddikRecord` or the JVM goldens: the plugin adds device tests, a
group of managed devices and two tasks, and none of them is wired into `check`.

## What it needs

- **The registry in `commonMain`** — `viddik { showroomTargets = true }`, the same switch the Android
  showroom needs. A registry generated into a desktop test source set is invisible to every Android
  compilation.
- **A `com.android.kotlin.multiplatform.library` module** with `kotlin { android { } }`. The capture
  runs in its `androidDeviceTest`; the plugin switches device tests on if the module has none, and adds
  its devices to them if it has.
- **An emulator-capable machine** — the Android SDK with the emulator, and hardware virtualisation.
  System images are downloaded by Gradle the first time a level is used (about 1 GB each).

## Settings

```kotlin
viddikAndroidMatrix {
    apiLevels = listOf(29, 31, 34, 36)   // default; 24 is the floor, below 29 see "Levels below 29"
    device = "Pixel 6"                   // default; one hardware profile for every level
    systemImageSource = "aosp"           // default; or "aosp-atd", "google", "google-atd"
    referenceApi = 36                    // default: the highest of apiLevels
}
```

### Levels below 29

AGP does not create a Gradle-managed device below API 27 (*"API level 26 and lower is currently not
supported for Gradle Managed devices"*, at task creation), and on 27 and 28 it runs the tests and pulls
nothing back (*"additionalTestOutput is not supported on Gradle managed devices running API level <
29"*). Levels 24–28 stay in `apiLevels`, so the report expects their column, but the plugin makes no
managed device for them and says so at configuration. Their captures come from a run on an emulator you start yourself:

```bash
avdmanager create avd -n viddik_api24 -k "system-images;android-24;google_apis;x86_64" -d pixel_6
emulator -avd viddik_api24 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -read-only
ANDROID_SERIAL=emulator-5554 ./gradlew :yourModule:connectedAndroidDeviceTest
./gradlew :yourModule:viddikAndroidMatrix        # the managed levels, and the report over all of them
```

`ANDROID_SERIAL` matters: without it the connected run installs on every device adb sees, a phone on
the desk included. Switch animations off on that emulator (`settings put global …_scale 0`), which the
managed devices do through the DSL. Run one level after another: each connected run clears AGP's output
directory, so `viddikKeepConnectedCaptures` runs after it and keeps its captures in
`build/viddik-matrix/connected/api<N>`, where the report reads them and as everywhere identifies the column
by `device.json`. A new run of a level replaces everything kept for that level, whichever port it ran on, and
touches no other. `clean` removes them all.

AGP finds where to pull from by asking MediaStore for `/sdcard/Android`. A fresh boot may not have indexed it
yet and the API 27 image does not index it at all, and without it a run takes every capture and brings none
back. So `viddikPrepareConnectedEmulators` runs first and adds the row where it is missing, on emulators only:
a phone on the same adb is never written to.

A run that took its captures and still pulled none fails the build, in `viddikKeepConnectedCaptures`, saying
where the device wrote them and what that means. They cannot be fetched afterwards, since AGP has uninstalled
the test APK and they went with it: run that level again. One such case is not explained: twice, the first
connected run of API 26 after a boot captured everything and AGP did not start pulling at all, though it had
passed the directory itself; the run after it pulled everything.

A sheet with a text field comes up with the field focused on the emulators of API 24, 26 and 27, and
not on 31, 34 and 36: below 28 a new window hands focus to its first focusable view. The keyboard that
follows came up in some runs and not in others, so below 28 the capture keeps it away from a dialog's
window and the sheet is captured where it sits without one, as from 28. The field keeps its focus, so its
focused border is the one difference left in such a sheet between those levels and 31 and above.

One hardware profile for every column is the point: the API level should be the only thing that
changes from one column to the next. The emulators are named `viddikApi<N>` and grouped as
`viddikMatrix`, so `viddikApi34AndroidDeviceTest` runs one of them on its own.

### Which renderer

The emulator's renderer is a setting of the whole run, and it matters twice.

- **A software renderer can take the emulator down.** On a real suite, fixtures with AGSL shaders
  crashed the emulator on `swiftshader_indirect` and on the `auto` it falls back to without a window (3
  of 8 fresh boots each); `host` went through 8 of 8. A crash costs the level every capture not yet
  pulled, so the plugin says so when the property is anything but `host`. Without a GPU there is no
  choice: expect the occasional lost level on such fixtures, and use a `filter` to go around them.
- **Renderers do not agree on pixels.** The same level through `host` and through a software renderer
  differs on most fixtures, by up to about 0.4%. Compare columns from one renderer only.

## The tasks

- **`viddikAndroidMatrix`** — sets up and runs every emulator of the group, strictly one level at a
  time, then the report.
- **`viddikAndroidMatrixReport`** — the report alone, over whatever the last run pulled back.

A fixture that fails on one level does not stop the others, and neither does it stop the rest of that
level. The page is written either way, even when an emulator never came up, and then the task fails if
any capture did not come back, naming the fixtures: a fixture that threw, one too large for the device,
or a file that could not be pulled. Differences never fail it.

A level's device task run on its own writes no page, and it does not notice an emulator dying part
of the way through. Only `viddikAndroidMatrix` checks that every capture came back.

The device runs are never up to date and never taken from the build cache: Gradle would restore the
test results and not the captures, and the level would quietly drop out of the page.

Where AGP puts what a managed device pulled is not reliable: run one level at a time, it has put one level's
captures in the directory named after another, over what was there. So each level's captures are copied out
the moment its run ends, by `viddikKeepApi<N>Captures`, into `build/viddik-matrix/managed/api<N>`, chosen by
the level the device reported and by having been written during that run. The report reads only these and
`build/viddik-matrix/connected`. A level run again replaces its own directory; a level not run again keeps
the last one, and the page says when that is more than a day older than the newest column.

If a requested API level has no column, the console and the page say so. Two usual causes:

- **An emulator left running by an interrupted run.** It stays attached to adb and takes the next run's
  tests. `adb devices` shows it; `adb -s emulator-5554 emu kill` (with its own serial) stops it.
- **A damaged boot snapshot.** Setup keeps one per device, and one written while the machine was short
  of resources can load "successfully" and leave the device `offline` until the run times out, on every
  run after that. `./gradlew cleanManagedDevices` makes Gradle write them again.

On each emulator the device test is one JUnit 4 test, `captureAll`, that takes every fixture in one
Activity: a test per fixture spends most of its time starting Activities (a 876-fixture suite took 25
minutes a level that way, and takes about 7). A fixture that throws fails that one capture; the test
goes on with the next and fails at the end, listing them. The same list is `failures.txt` beside the
captures, which is where the report's reason for a `no capture` cell comes from. Logcat's `viddik` tag
says which fixture is being captured, which is what to look at when an emulator dies.

## How a capture is taken

- The fixture is laid out at its `width` (and `height`, unless it is auto) in **dp**, at the
  emulator's real density — so the PNG is larger than the JVM golden of the same fixture, which treats
  a dp as a pixel. The file name is the same up to 48 characters; a longer one keeps its start and gets
  a hash of the whole name, because past Windows' MAX_PATH adb cannot pull the file. The page shows the
  fixture's real name either way.
- **Font scale goes through a real `Configuration`** (`DeviceConfigurationOverride.FontScale`), not
  through `Density(density, fontScale)`: only that path reaches the converter that makes scaling
  non-linear from API 34.
- The window is edge to edge on every level, as in an app that calls `enableEdgeToEdge()`. The platform
  enforces that only from 35, and a host left to its default would give fixtures different
  `WindowInsets` on either side of that line, which would show up as a difference between the columns.
  An app that is not edge to edge below 35 looks different from the capture there.
- The fixture sits inside the safe drawing area, so neither a system bar nor the navigation bar's
  contrast scrim can end up in a capture.
- The capture is `captureToImage()`, which is PixelCopy of the window: hardware layers, `RenderEffect`
  and shadows are in it as the device drew them.
- On API 24 and 25, which have no PixelCopy, the capture is `UiAutomation.takeScreenshot()` cut down to
  the fixture's bounds — what SurfaceFlinger composited, so still the device's HWUI output. Not
  `View.draw()` into a software Bitmap: that is Skia's raster backend, and the column would differ from
  the reference because of the capture rather than the platform. Two differences remain: the capture
  waits a fixed 250 ms for the frame to reach the screen, and a translucent pixel shows the window
  background behind it instead of transparency.
- Below 28, before each capture every Compose layer of the window is recorded again, twice, with a frame
  drawn after each pass (`invalidateDescendants()`). There a capture copies what is already on screen, and
  a layer drawn from another layer's recording without being told when it changes could be left a frame
  behind and stay there. On 26 and 27 the redraw changed no pixel of any capture; on 24, the one level
  captured by screenshot, it changed some layers drawn that way, and which of the two pictures is the
  device's own is not known.
- A `Dialog` is captured on its own, as on the JVM, and a full-screen one (a bottom sheet) comes out the
  same on every level: what the dialog's window drew, its scrim still translucent, nothing from behind it.
  `captureToImage()` does this from 28, PixelCopy of the dialog's window on 26 and 27, and on 24 and 25
  two screenshots with black and then white behind the dialog, from which the window's own pixels are
  worked out, so such a capture costs about a second there.
- What the system put over a dialog's capture is taken out. The navigation bar and a keyboard go
  transparent, so a pinned button that reaches them is seen cut off. On 24 and 25, where a screenshot also
  has the status bar's icons and clock, each row of that band takes the colour most of it already has, which
  is what the window drew around them. A sheet going under the status bar loses its rounded corners there. A
  dialog clear of them is left alone.
- A fixture larger than the emulator's safe area fails with its size and the device's, instead of
  being cut off. Pick a larger `device`, or give the fixture a size that fits. `Pixel 6` has about
  817 dp of height to offer on API 34 and 36, a little more below them, since the system bars differ;
  a suite of full-screen fixtures wants a taller profile.
- Animations are switched off on the devices; a dark variant and a `@PreviewWrapper` theme arrive
  through the registry exactly as they do on the JVM.
- The window takes no focus, and each fixture gets a composition of its own within it. A focused text
  field in one fixture would otherwise raise the keyboard, and the next fixture would be captured while
  the window was still resizing back.

## Reading the report

Each row is a fixture, each column an API level, and each cell is compared with the reference column
through the same `ImageDiffer` and the same default thresholds `viddikVerify` uses:

| Cell | Meaning |
|---|---|
| `same` | within the default thresholds |
| `x% differ` | same size, pixels past the thresholds; `diff` links the red-painted comparison |
| `size …` | a different size — text wrapping differently, a row growing; the plainest divergence there is |
| `no capture` | the fixture failed on that device, and the cell says why; or that level's run did not have it; or, with no reason from the device, its file never reached this machine |

Rows are sorted by how far the furthest level is from the reference, so the page reads from the top:
first a fixture captured on some levels and missing on others, then the largest difference down, and
last whatever has no capture anywhere. Between Android releases nearly everything differs by something
— anti-aliasing and glyph rendering are part of what the OS provides, and on a real suite 494 of 811
fixtures passed that threshold, most by a fraction of a percent — so the header counts how many differ
by 1%, 5% and 25% or more, and "Differ by at least" hides the rest. `summary.json` beside the page
carries the same numbers for a script or a CI step.

The console and the column headers count each level's captures apart: how many came back, how many the
device refused (a fixture that threw, or one too large for it), how many were lost on the way back (no word
from the device and no file; on Windows, usually a machine out of sockets during `adb pull`), and how many
that level's run did not have. `summary.json` has the same, per device and in total.

**Columns of different density** cannot be compared pixel by pixel: a fixture is laid out in dp, so it comes
out another size. Each density therefore gets a reference of its own, the newest level of that density, and
a note at the top of the page says which column is compared with which. `referenceApi` picks the reference of
its own density. Nothing on the page compares one profile with another.

The page also says when a level has two columns, because two profiles reached it (a managed run and a
connected one, say), and when a column was captured more than a day before the newest one, which is usually
a level nobody ran again. Each column's header has the time of its capture. When a level's latest run pulled
nothing back, its column is what the run before left, and the header and a note at the top say so whatever
its age. `summary.json` lists the reference of each profile (`references`) and gives every device its own.

When captures are missing, `viddikAndroidMatrixReport` fails after writing the page, with the counts in the
failure: a fixture too large for the device stays refused on every run, so a suite with one keeps the task
red until the fixture or the `device` changes.

## Compose resources

Compose Multiplatform 1.12 adds a task copying compose resources into the device-test APK, and that
task fails in any module that has device tests but not `androidResources { enable = true }` —
with or without the matrix. Such a module packages no compose resources on Android to begin with, so
the plugin turns that one task off rather than switching the module's resource processing on. A
module whose fixtures use `stringResource`/`painterResource` needs `androidResources { enable = true }`
for its Android app anyway, and with it the task runs.
