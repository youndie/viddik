# The showroom

The showroom is a component browser over the same registry the goldens come from: a list grouped by
`group`, a search field, and a detail view per fixture. Nothing is captured; it is the fixtures, live.

```bash
./gradlew :yourModule:viddikShowroom   # opens it in a desktop window
```

The list has a search field over it. The query is split on whitespace and every token has to appear
somewhere in `"$group $name"`, case-insensitively — so `wid but` finds `Widgets / Button`, and the
order of the words does not matter. The field shows how many of the module's components survived the
query, and clears with the `×` beside it.

Without the plugin, the desktop window is a `fun main()` of your own:

```kotlin
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Component Browser") {
        MaterialTheme {
            ViddikShowroom(GeneratedViddikRegistry.components)
        }
    }
}
```

## On Android and iOS

The desktop window is one host for the browser and not the only useful one: a component library is
judged on a phone. The obstacle is where the registry lives — KSP generates it into the module's
**test** source set, and a test source set is never compiled into an app, so that registry can reach
the machine that ran the build and nowhere else.

`viddik { showroomTargets = true }` changes where it comes from:

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.google.devtools.ksp")
    id("io.github.youndie.viddik")
}

viddik {
    showroomTargets = true
}
```

**Move the fixtures to `commonMain`.** That is the actual migration; everything else is wiring the
plugin does — the processor goes on `kspCommonMainMetadata`, the generated directory goes on
`commonMain`, and every compilation is ordered after the task that fills it. The registry is then
compiled for every target the module has.

Goldens keep working exactly as before. The JUnit 5 class that drives them is JVM-only and cannot be
common, so the plugin writes it into the test source set itself, over the registry `commonMain`
produced — `viddikVerify` and `viddikRecord` are unchanged, and so is `--component`.

Then add `viddik-showroom` and write the host. On Android that is a subclass and a manifest entry:

```kotlin
// build.gradle.kts of the app module
implementation("io.github.youndie.viddik:viddik-showroom:<VERSION>")
```

```kotlin
class ShowroomActivity : ViddikShowroomActivity() {
    override val components = GeneratedViddikRegistry.components
}
```

On iOS it is a view controller:

```kotlin
// iosMain
fun ShowroomViewController(): UIViewController =
    ViddikShowroomUIViewController(GeneratedViddikRegistry.components)
```

```swift
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ShowroomViewControllerKt.ShowroomViewController()
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
```

Both are `ViddikShowroomApp` underneath: the browser inside a default `MaterialTheme`, with Android's
back gesture wired to close a component's detail view rather than the activity. Host
`ViddikShowroomApp` — or `ViddikShowroom` itself, which imposes no theme — if you would rather put it
inside your own navigation.

The registry is **passed in**, not looked up. The desktop launcher can afford to load it reflectively,
since it runs against a classpath a Gradle task assembled; here it is an ordinary reference in your
own module, so a fixture that stopped compiling is a build error rather than an empty list at launch —
and Kotlin/Native has no reflective lookup to offer in the first place.

`samples/` in this repository is all of the above, running: a `fixtures` module with the fixtures in
`commonMain`, an Android app, and an iOS app that needs no Xcode project
(`samples/scripts/ios-showroom.sh` links the executable, wraps it in a bundle and launches the
simulator). It is a separate Gradle build that consumes viddik through `includeBuild("..")` — the
plugin id and the published coordinates, the way any other project would.
