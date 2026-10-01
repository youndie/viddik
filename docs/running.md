# Recording and verifying

```bash
./gradlew :yourModule:viddikRecord   # write the goldens a verification would reject
./gradlew :yourModule:viddikVerify   # compare against them; a mismatch fails with a _DIFF.png
```

Goldens go to `src/<test source set>/snapshots/` (`snapshotsDir` changes it); a failed comparison
writes `<group>_<name>_DIFF.png`, every mismatched pixel in red, to `build/reports/screenshots/`.
Recording does not validate anything — look at what it wrote before committing it.

## What a record writes

Recording renders every selected fixture, compares the render with the existing golden through the
same differ and thresholds `viddikVerify` uses, and writes only where that comparison fails. A record
on an unchanged tree writes nothing, so `git status` afterwards shows what actually moved — which
matters most when the goldens are in Git LFS and every rewritten file is another blob. The run says
what happened:

```
viddik record: wrote 2 golden(s), kept 746 the verification already accepts. Written: Buttons - Primary, Buttons - Primary Dark
```

`--force` writes every selected golden regardless — what a re-record after a Compose, font or
renderer bump wants:

```bash
./gradlew :yourModule:viddikRecord --force
```

## One component

Gradle's `--tests` cannot select a fixture: every fixture is a JUnit 5 *dynamic* test inside a
generated class, and `--tests` only matches classes and methods. Use `--component`:

```bash
./gradlew :yourModule:viddikRecord --component "Buttons - Primary"  # one fixture
./gradlew :yourModule:viddikVerify --component Primary              # bare substring
./gradlew :yourModule:viddikVerify --component "Buttons*Dark"       # * and ? are wildcards
```

The pattern is a case-insensitive substring of `"$group - $name"`. A pattern that matches nothing
fails the task and lists the components the module does have, rather than passing with zero
screenshots.

## In `check`

The goldens are not wired into `check` by default, and the generated tests are excluded from the
module's ordinary test task. Goldens are portable once the fixtures bundle a font
([portability.md](portability.md)); until they do, they are host-specific and would redden
`./gradlew build` on every machine that did not record them. Turn the check on with
`viddik { verifyOnCheck = true }`, or for one run with `./gradlew check -Pviddik.verify`.

## Speed

Three settings, all on by default in the plugin.

**One scene for the whole run** (`sceneReuse`). Standing a Compose scene up is most of what a capture
costs — an empty capture measures 11.7 ms against a median fixture's 12.1 ms — so one scene serves
every capture. Measured on 403 fixtures of one size: 13.3 ms per capture becomes 5.7 ms; on viddik's
own suite, whose fixtures differ in size, 22.4 ms becomes 13.4 ms. The scene is reopened whenever
the next fixture has a different canvas, because a `Dialog` centres itself in the window, so
fixtures are visited in size order. The one constraint: no other Compose test that stands up a
harness of its own may run in the same JVM, because two harnesses wedge each other. `viddikVerify`
and `viddikRecord` run only the generated tests, so that holds there; set `sceneReuse = false` for a
task that mixes them in. The goldens are the same either way — CI records viddik's own suite both
ways on three operating systems and compares.

**Several forks** (`shards`, two by default). Gradle divides test work by class, so `shards` emits
that many test classes, each taking every Nth fixture, and sets a matching `maxParallelForks` on
`viddikVerify` and `viddikRecord`. A fork costs its own JVM start plus Compose and skiko class
loading, about 1.9 s, so a suite of a few dozen fixtures is faster with `shards = 1`. Measure before
raising it: on a 748-fixture suite on an 8-core laptop two forks took a verification from ~52 s to
~25 s and four to ~18 s, while on a 20-core Linux box four forks were slower than one on a synthetic
suite of 400. `viddikDesignParity` ignores this setting.

**KSP only re-runs when a declaration changes** (`kspDeclarationSnapshot`). The registry depends on
the fixtures and the declarations they see, never on a function body — but KSP re-runs whenever a
class on its classpath changes, and with Compose that is almost every edit: the compiler records
each composable's source offsets in `@FunctionKeyMeta`, so retyping one string changes every
composable after it. The plugin hands the test source set's KSP run a snapshot of the main classes,
and of the other modules of the build on the test classpath, with bodies, debug information and
those offsets taken out. Measured on a module of 1000 fixtures: editing a component and recording
one golden takes ~3.3 s instead of ~4.1 s; with the fixtures in a module of their own and the
components in a sibling module, ~3.3 s instead of ~6 s. A new or changed declaration still re-runs
KSP. Set `kspDeclarationSnapshot = false` if another processor in the same KSP run needs the real
classes.

## Without the plugin

Recording is the `VIDDIK_RECORD_MODE` environment variable on whatever test task runs the generated
class, and `-Dviddik.forceRecord=true` is what `--force` does:

```bash
VIDDIK_RECORD_MODE=true ./gradlew :yourModule:test --rerun
```

The rest of the setup without the plugin is in [configuration.md](configuration.md#declaring-the-dependencies-by-hand).
