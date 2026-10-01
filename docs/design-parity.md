# Design parity

Goldens answer "did the rendering change". A different question comes first, while a screen is being
built: **how far is it from the design it was built to**. `viddikDesignParity` answers that one, and it
is deliberately a separate task with separate numbers, because the two comparisons have nothing in
common except the pixels: a golden is Compose against Compose, a design is Compose against whatever
drew the artboard.

Put a PNG of each design state next to the goldens, under `design/`, named exactly like the golden
the fixture would record — `<group>_<name>.png`, spaces and everything else outside `[A-Za-z0-9_.-]`
replaced by `_`. The fixture's `width`/`height` should be the artboard's size; a reference of another
size is reported as such rather than scored, because the percentage then counts the area outside
the overlap too.

```
src/desktopTest/snapshots/
├── Checkout_Empty.png            # golden, recorded by viddikRecord
└── design/
    └── Checkout_Empty.png        # reference, exported from the design — never written by viddik
```

```bash
./gradlew :yourModule:viddikDesignParity                              # every fixture
./gradlew :yourModule:viddikDesignParity --component "Checkout*"      # same filter as verify
./gradlew :yourModule:viddikDesignParity -Pviddik.designStrict        # fail on a mismatch
```

The task **reports rather than judges** by default: it passes, and prints one line per fixture:

```
Design parity: 1/3 within 5.0% (channel tolerance ±16), 6 without a reference
  ok      Buttons - Filled 0.00%
  no ref  Buttons - Filled Dark (expected src/desktopTest/snapshots/design/Buttons_Filled_Dark.png)
  DIFF    Buttons - Outlined 16.40% -> build/reports/screenshots/design/Buttons_Outlined_DIFF.png
```

A fixture without a reference is information, not a failure — a module rarely has a design for every
state of every component. The one thing that does fail the task in report mode is **no reference
matching any fixture at all**, which is a wrong `designDir` or a naming slip, and a green run that
measured nothing would hide it. `designStrict = true` (or `-Pviddik.designStrict`) turns each mismatch
into a failing test as well, for the module where matching the design is the acceptance criterion.

Everything it measured goes to `build/reports/screenshots/design/`, for a person or a tool to work
from: the render of every fixture as `<name>_ACTUAL.png` (put it next to the reference), a red-mask
`<name>_DIFF.png` wherever a pixel differed, `summary.txt` — the lines above — and `summary.json`
with the same per-fixture status, pixel counts, percentage, both sizes and both paths. Files from the
previous run are removed first, so a diff that no longer reproduces never sits next to a fresh
summary.

The defaults — 5% of pixels, ±16 per channel — are a starting point for "looks the same when drawn by
two rasterizers", not a measurement; the golden numbers (0.05%, ±2) would fail on anti-aliasing alone.
Calibrate them on a screen you consider done, then tighten. A fixture's own
`@ViddikScreenshot(tolerancePercent)` is not consulted here: it budgets rendering noise between two
runs of the same code, which is a different question.

`viddikRecord` never touches `design/`, and neither does this task. The reference is the design's, and
a run that could overwrite it with the render would turn "does the code match the design" into "does
the code match itself".
