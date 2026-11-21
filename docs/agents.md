# Running viddik from an agent

This page is the same facts as the other docs, as steps, for an agent driving viddik in a project's
repository: "verify the goldens and fix what broke", "record the new screen", "find the rendering
problems across these Android levels". The interface is Gradle and files. viddik has no agent mode and
judges nothing itself; what it promises is that the files say everything and a run never goes green by
doing nothing — and the checks below are how to hold it to that.

Replace `:yourModule` with the module that applies `id("io.github.youndie.viddik")`.

## 1. Which command for which job

| job | command |
|---|---|
| check the goldens | `./gradlew :yourModule:viddikVerify` |
| one fixture, or a few | `./gradlew :yourModule:viddikVerify --component "Buttons - Primary"` (substring, `*` and `?` wildcards) |
| write the goldens a verification would reject | `./gradlew :yourModule:viddikRecord` |
| rewrite every golden (Compose, font or renderer bump) | `./gradlew :yourModule:viddikRecord --force` |
| capture on Android levels and compare them | `./gradlew :yourModule:viddikAndroidMatrix -PviddikMatrix.apiLevels=29,31,34,36` |
| only some fixtures on the matrix | add `"-PviddikMatrix.filter=Buttons\|Surfaces - Sheet"` (patterns separated by `\|`) |
| a level below 29 | start an emulator of that level, then `ANDROID_SERIAL=emulator-5554 ./gradlew :yourModule:connectedAndroidDeviceTest`, then `viddikAndroidMatrixReport` |
| make each level's last run its baseline | `./gradlew :yourModule:viddikAndroidMatrixBaseline` |

Gradle's own `--tests` cannot select a fixture (they are dynamic tests); `--component` and the
matrix filter can. A record on an unchanged tree writes nothing, by design.

## 2. Where the results are, and how to tell the run did its job

**JVM** — `build/reports/screenshots/`:

- `summary.json`: one entry per fixture the run compared — `status` (`match`, `mismatch`, `size`,
  `missingGolden`, `unreadableGolden`, `error`), the numbers, the `regions` that changed, the thresholds
  that decided it, and the paths below. Read this rather than the console.
- per failing fixture: `<name>_CROP.png` (open this first), `_ACTUAL.png`, `_EXPECTED.png`, `_DIFF.png`.

**Matrix** — `build/reports/viddik-matrix/`:

- `index.html`, `summary.json` (every cell: status, share, pronounced pixels, regions, and for an empty
  cell `missing`: `refused`, `tooLarge`, `lost`, `notInRun`, with the device's `reason`);
- `triage.json`: the suspects, ranked (section 4);
- `baseline.json`: each fixture against its level's baseline, when there are baselines;
- crops: `<level>/crop/` against the reference, `triage/<level>/` for suspects,
  `<level>/baseline-crop/` against the baseline.
- the kept captures in `build/viddik-matrix/{managed,connected}/api<N>/`: `failures.txt` (fixtures that
  threw), `does-not-fit.txt` (too large for the device), `pulled-nothing-at` (that level's latest run
  pulled nothing, and the column is an earlier run's).

**A green Gradle run is not proof on its own.** Before reporting success, check what the run should
have produced:

- verify: `summary.json`'s `compared` is the number of fixtures you expected, and `failed` is 0;
- record: the console's `viddik record: wrote N golden(s), kept M` line, and `git status` names exactly
  the goldens you meant to change;
- matrix: the console has a line per level you asked for, its `refused`, `lost` and `not in its run` are what
  you expect, no column says it is an earlier run's, and `index.html` has a column per level.

## 3. A verification failed: stale golden or regression?

1. Open `<name>_CROP.png`: the golden, the render and the diff side by side at 1:1.
2. If the task you were given meant to change that fixture, and the crop shows that change, the golden is
   stale: record it (`viddikRecord --component "<group> - <name>"`) and look at the new PNG.
3. If the task did not mean to change it, it is a regression: fix the code, not the golden.
4. Never re-record to make a check pass without having looked at the crop, and never raise a tolerance
   to silence a fixture. `@ViddikScreenshot(tolerancePercent = ...)` exists for one thing, text under a
   blur or other `RenderEffect` ([portability.md](portability.md)), not for a fixture that changed.
5. `missingGolden` is a new fixture: record it. `unreadableGolden` whose message says "Git LFS pointer"
   means the checkout needs `git lfs pull`, not a re-record.

## 4. Finding rendering problems across Android levels

1. Run every level on **one device profile and one renderer** (`viddikAndroidMatrix` does, for the
   managed levels; the levels below 29 need an emulator of the same profile). Columns of different
   density are not compared with each other, and renderers differ by up to ~0.4% on their own.
2. Read `triage.json`. Each suspect has `reasons` with numbers, `disagreesWith`, a `crop` against the
   level closest to the others, and a `rerun` command. The odd-one-out signal needs at least four levels
   with a capture of the fixture.
3. Look at each suspect's crop and decide: defect, or a known platform difference. These are platform
   differences, not defects — do not report them:
   - glyph rasterization and anti-aliasing (every level differs a little);
   - shadows before API 28 (coloured shadows arrive in 28);
   - status bar height, so a tall sheet sits a few dp lower on some levels;
   - below 28 a window hands focus to its first field, so a field's focused border shows there;
   - edge-to-edge is enforced only from 35 (the harness compensates; a residue can remain);
   - renderer differences, when the columns were not all on one renderer.
4. Confirm a defect with its `rerun` command: a finding worth reporting comes back on the same level.
5. Report fixture × level, the reason, and the crop.
6. With baselines (`baseline.json`), a fixture that changed on **some levels only** since the baseline
   is the regression case; one that changed on every level is the component, and wants a new baseline.

## 5. Machine care

- An emulator takes several GB. Check free memory before starting one, and do not start one when the
  machine is short; the API 27 image is known to leak host memory over a long run.
- Never let a connected run reach a phone: set `ANDROID_SERIAL` to the emulator's serial
  (`emulator-NNNN`), always. Without it the run installs on every device adb sees.
- Put a time limit on a matrix run from outside (`timeout`, your runner's limit). Stopping the Gradle
  client does not stop the daemon's build, and a wedged emulator can hold it for hours.
- An emulator left running by an interrupted run takes the next run's tests: `adb devices` shows it,
  `adb -s emulator-NNNN emu kill` stops it. A level stuck `offline` after a snapshot load wants
  `cleanManagedDevices`.

More on each: [running.md](running.md), [android-matrix.md](android-matrix.md),
[configuration.md](configuration.md).
