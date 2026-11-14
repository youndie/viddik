package io.github.youndie.viddik.gradle

/**
 * The system properties that keep a forked screenshot JVM from becoming a desktop application.
 *
 * A capture never opens a window — the scene renders offscreen — but the JVM still initialises AWT,
 * and on macOS that registers the process with the window server as a foreground application: a Dock
 * icon per test fork, and the user's fullscreen Space left for the desktop every time a fork starts.
 * Windows shows the forks in the taskbar the same way. `lsappinfo` on macOS measured the three states:
 * no property — `type="Foreground"`; `apple.awt.UIElement` — `type="UIElement"` (an agent: no Dock
 * icon, no activation); `java.awt.headless` — not registered at all. The whole of
 * `viddik-testing-core`'s own suite passes headless on macOS, as it always did on Linux CI, where the
 * JDK falls back to headless by itself when `DISPLAY` is unset.
 *
 * `apple.awt.UIElement` is set in both modes, so that a module that has to turn headless off — a
 * fixture that reads the AWT clipboard or the screen size throws `HeadlessException` otherwise —
 * still keeps its forks out of the Dock. Every other platform ignores it.
 */
internal fun windowlessSystemProperties(headless: Boolean): Map<String, String> =
    buildMap {
        if (headless) put("java.awt.headless", "true")
        put("apple.awt.UIElement", "true")
    }
