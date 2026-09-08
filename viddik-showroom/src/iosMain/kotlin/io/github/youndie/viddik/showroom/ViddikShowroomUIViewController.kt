package io.github.youndie.viddik.showroom

import androidx.compose.ui.window.ComposeUIViewController
import io.github.youndie.viddik.annotations.ViddikComponent
import platform.UIKit.UIViewController

/**
 * The iOS half of the showroom: a view controller over the registry KSP generated for this module.
 *
 * From Swift, this is the whole app:
 *
 * ```swift
 * struct ContentView: UIViewControllerRepresentable {
 *     func makeUIViewController(context: Context) -> UIViewController {
 *         ShowroomViewControllerKt.ShowroomViewController()
 *     }
 *     func updateUIViewController(_ controller: UIViewController, context: Context) {}
 * }
 * ```
 *
 * where `ShowroomViewController()` is a one-line function in the shared module's `iosMain` that calls
 * this with `GeneratedViddikRegistry.components`. The indirection is not ceremony: the registry is
 * generated into the consumer's module and this one cannot see it, and passing it in keeps that a
 * compile-time reference rather than a reflective lookup Kotlin/Native has no answer for anyway.
 *
 * PascalCase on purpose, which is why ktlint's function-naming rule is suppressed below: this is the
 * shape Compose Multiplatform gives a `ComposeUIViewController` factory everywhere, and it is the name
 * Swift sees. Renaming it to satisfy the linter would make viddik the one library on the platform that
 * spells it differently.
 */
@Suppress("ktlint:standard:function-naming")
public fun ViddikShowroomUIViewController(components: List<ViddikComponent>): UIViewController =
    ComposeUIViewController { ViddikShowroomApp(components) }
