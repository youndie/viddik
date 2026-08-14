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
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
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
