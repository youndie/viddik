package io.github.youndie.viddik.samples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.LocalViddikDarkTheme
import io.github.youndie.viddik.annotations.ViddikScreenshot

// THE FIXTURES LIVE IN commonMain, and that is the whole difference between this sample and viddik's
// own. A test source set is never compiled into an application, so a registry generated there can be
// opened on the machine that ran the build and nowhere else. Here `viddik { showroomTargets = true }`
// generates it from commonMain instead: the Android app links it, the iOS executable links it, and
// the desktop goldens are captured from the same list.
//
// The cost is that fixtures ship in the module. For a design-system module that is usually what you
// want — the showroom is a product of the library, not of its tests.

@Composable
private fun SampleTheme(
    dark: Boolean = LocalViddikDarkTheme.current,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

@ViddikScreenshot(name = "Filled", group = "Buttons", darkVariant = true)
@Composable
public fun FilledButton() {
    SampleTheme {
        Button(onClick = {}, modifier = Modifier.padding(8.dp)) { Text("Continue") }
    }
}

@ViddikScreenshot(name = "Outlined", group = "Buttons")
@Composable
public fun OutlinedSampleButton() {
    SampleTheme {
        OutlinedButton(onClick = {}, modifier = Modifier.padding(8.dp)) { Text("Cancel") }
    }
}

@ViddikScreenshot(name = "Text field", group = "Inputs", width = 320)
@Composable
public fun SampleTextField() {
    SampleTheme {
        OutlinedTextField(
            value = "hello@example.com",
            onValueChange = {},
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
}

@ViddikScreenshot(name = "Switch", group = "Inputs")
@Composable
public fun SampleSwitch() {
    SampleTheme {
        Row(
            Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Switch(checked = true, onCheckedChange = {})
            Switch(checked = false, onCheckedChange = {})
        }
    }
}

@ViddikScreenshot(name = "Progress", group = "Feedback", width = 320)
@Composable
public fun SampleProgress() {
    SampleTheme {
        LinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

@ViddikScreenshot(name = "Chip", group = "Feedback")
@Composable
public fun SampleChip() {
    SampleTheme {
        AssistChip(onClick = {}, label = { Text("Filter") }, modifier = Modifier.padding(8.dp))
    }
}

@ViddikScreenshot(name = "Summary card", group = "Surfaces", width = 320, darkVariant = true)
@Composable
public fun SampleCard() {
    SampleTheme {
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Order 4821", style = MaterialTheme.typography.titleMedium)
                Text("Shipped 2 days ago", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
