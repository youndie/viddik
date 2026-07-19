package ru.workinprogress.viddik.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@ViddikScreenshot(name = "Simple Text", group = "Demo", darkVariant = true)
@Composable
fun SampleTextPreview() {
    val dark = LocalViddikDarkTheme.current
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
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
    MaterialTheme {
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
            ViddikComponent(name = "Text", group = "Widgets") { MaterialTheme { Text("Hi") } },
            ViddikComponent(name = "Button", group = "Widgets") {
                MaterialTheme { Button(onClick = {}) { Text("Go") } }
            },
            ViddikComponent(name = "Text", group = "Screens") { MaterialTheme { Text("Screen preview") } },
        )
    MaterialTheme {
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
    val dark = LocalViddikDarkTheme.current
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface {
            Button(onClick = {}) {
                Text(label)
            }
        }
    }
}
