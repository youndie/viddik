package ru.workinprogress.viddik

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.Absolute.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.workinprogress.viddik.annotations.ViddikComponent

@Composable
fun ViddikShowroom(
    components: List<ViddikComponent>,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<ViddikComponent?>(null) }
    val current = selected

    Surface(modifier = modifier.fillMaxSize()) {
        if (current == null) {
            ViddikShowroomList(components) { selected = it }
        } else {
            ViddikShowroomDetail(current) { selected = null }
        }
    }
}

@Composable
private fun ViddikShowroomList(
    components: List<ViddikComponent>,
    onSelect: (ViddikComponent) -> Unit,
) {
    val grouped = remember(components) { components.groupBy { it.group }.toSortedMap() }

    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (group, items) ->
            item(key = "header_$group") {
                Text(
                    text = group,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            items(items, key = { "${it.group}_${it.name}" }) { component ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(component) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(component.name, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ViddikShowroomDetail(
    component: ViddikComponent,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(16.dp),
            horizontalArrangement = spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←", style = MaterialTheme.typography.titleLarge)
            Text("${component.group} / ${component.name}", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            component.content()
        }
    }
}
