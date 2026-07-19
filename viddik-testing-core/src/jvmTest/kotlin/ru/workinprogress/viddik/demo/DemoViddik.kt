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
import ru.workinprogress.viddik.core.ViddikConsistentRendering
import ru.workinprogress.viddik.core.viddikTypography

// This module's own build.gradle.kts sets -Dviddik.consistentRendering=true on the jvmTest task, so
// this always resolves to viddikTypography() here in practice — a real consumer that never sets the
// property gets a plain, native-looking Typography() instead (see ViddikConsistentRendering's doc
// for the tradeoff between the two).
private val demoTypography: Typography by lazy {
    if (ViddikConsistentRendering.isEnabled) viddikTypography() else Typography()
}

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
