package ru.workinprogress.viddik.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.AndroidUiModes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import androidx.compose.ui.unit.dp
import ru.workinprogress.viddik.LocalViddikDarkTheme
import ru.workinprogress.viddik.ViddikShowroom
import ru.workinprogress.viddik.annotations.ViddikComponent
import ru.workinprogress.viddik.annotations.ViddikScreenshot
import ru.workinprogress.viddik.core.viddikTypography

// The demo has no font of its own, so it takes the bundled one — that is the only reason these
// goldens are reproducible on any OS. A project with its own bundled font keeps that font and runs it
// through normalizeVerticalMetrics() instead.
private val demoTypography: Typography by lazy { viddikTypography() }

@Composable
private fun DemoTheme(
    dark: Boolean = LocalViddikDarkTheme.current,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = demoTypography,
        content = content,
    )
}

@ViddikScreenshot(name = "Simple Text", group = "Demo", darkVariant = true)
@Composable
fun SampleTextPreview() {
    DemoTheme {
        Surface(Modifier.size(400.dp)) {
            Box(Modifier.size(400.dp)) {
                Text("Screenshot testing works")
            }
        }
    }
}

@ViddikScreenshot(name = "Simple Button", group = "Demo")
@Composable
fun SampleButtonPreview() {
    DemoTheme {
        Button(onClick = {}) {
            Text("Click me")
        }
    }
}

@ViddikScreenshot(name = "Showroom - list", group = "Showroom", width = 400, height = 300)
@Composable
fun ShowroomListPreview() {
    val sample =
        listOf(
            ViddikComponent(name = "Text", group = "Widgets") { DemoTheme(dark = false) { Text("Hi") } },
            ViddikComponent(name = "Button", group = "Widgets") {
                DemoTheme(dark = false) { Button(onClick = {}) { Text("Go") } }
            },
            ViddikComponent(name = "Text", group = "Screens") { DemoTheme(dark = false) { Text("Screen preview") } },
        )
    DemoTheme(dark = false) {
        ViddikShowroom(sample)
    }
}

class DemoButtonLabelProvider : PreviewParameterProvider<String> {
    override val values = sequenceOf("First", "Second", "Third")
}

@ViddikScreenshot(name = "Parameterized button", group = "Demo", darkVariant = true)
@Composable
fun ParameterizedButtonPreview(
    @PreviewParameter(DemoButtonLabelProvider::class) label: String,
) {
    DemoTheme {
        Surface {
            Button(onClick = {}) {
                Text(label)
            }
        }
    }
}

// The two fixtures below take their metadata from @Preview instead of from @ViddikScreenshot, which
// stays as the bare opt-in marker. Same declaration the IDE preview pane reads, same one Android's own
// screenshot tooling reads — in Compose Multiplatform 1.12 it is literally the same
// androidx.compose.ui.tooling.preview.Preview on both.

@ViddikScreenshot
@Preview(name = "Preview driven", group = "Demo", widthDp = 320, heightDp = 120)
@Composable
fun PreviewDrivenButton() {
    DemoTheme {
        Surface {
            Button(onClick = {}) {
                Text("From Preview")
            }
        }
    }
}

// uiMode says this fixture *is* dark, which is not the same as darkVariant asking for a second, dark
// copy of a light one — hence a single golden here, and no " Dark" suffix on its name.
@ViddikScreenshot
@Preview(name = "Preview driven night", group = "Demo", widthDp = 320, uiMode = AndroidUiModes.UI_MODE_NIGHT_YES)
@Composable
fun PreviewDrivenNightButton() {
    DemoTheme {
        Surface {
            Button(onClick = {}) {
                Text("Night from Preview")
            }
        }
    }
}

// --- Multipreview -----------------------------------------------------------------------------
//
// A multipreview is an ordinary annotation class carrying @Preview annotations, so one marker yields
// one fixture per preview. @PreviewLightDark is the shipped one; DemoTypeScale below is a hand-rolled
// one, which is also how the processor's recursion gets exercised.

@ViddikScreenshot(name = "Light dark", group = "Demo")
@PreviewLightDark
@Composable
fun MultiPreviewButton() {
    DemoTheme {
        Surface {
            Button(onClick = {}) {
                Text("Light or dark")
            }
        }
    }
}

@Preview(name = "Small", fontScale = 0.85f, widthDp = 320)
@Preview(name = "Large", fontScale = 1.5f, widthDp = 320)
annotation class DemoTypeScale

@ViddikScreenshot(name = "Type scale", group = "Demo")
@DemoTypeScale
@Composable
fun FontScaledText() {
    DemoTheme {
        Surface {
            Text("Scaled type")
        }
    }
}

// --- @PreviewWrapper --------------------------------------------------------------------------
//
// The fixture below deliberately does NOT call DemoTheme: the wrapper supplies it. That is the point
// of @PreviewWrapper — the bundled-font typography that makes a golden portable is exactly the kind of
// harness every fixture used to have to remember for itself, since a theme cannot be forced on a
// composable from outside the composition.

class DemoThemeWrapper : PreviewWrapperProvider {
    @Composable
    override fun Wrap(content: @Composable () -> Unit) {
        DemoTheme(content = content)
    }
}

@ViddikScreenshot
@PreviewWrapper(DemoThemeWrapper::class)
@Preview(name = "Wrapped", group = "Demo", widthDp = 320)
@Composable
fun UnthemedButton() {
    Surface {
        Button(onClick = {}) {
            Text("Themed by the wrapper")
        }
    }
}
