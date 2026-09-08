package io.github.youndie.viddik

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement.Absolute.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.youndie.viddik.annotations.ViddikComponent

/**
 * The component browser: every fixture the KSP processor found, grouped, searchable, one tap from its
 * live rendering.
 *
 * The same composable serves every platform viddik can be looked at on — the `viddikShowroom` Gradle
 * task's desktop window, an Android activity, an iOS view controller — so it stays plain Compose
 * Multiplatform with no platform API in it at all. What a platform *does* have, such as Android's back
 * gesture, is wired by the host against [state]; see [ViddikShowroomState].
 *
 * Insets are handled here rather than in each host: a host that embeds this composable gets a
 * showroom that already keeps its search field out from under a notch, and on desktop
 * `WindowInsets.safeDrawing` is empty, so goldens are unaffected.
 */
@Composable
public fun ViddikShowroom(
    components: List<ViddikComponent>,
    modifier: Modifier = Modifier,
    state: ViddikShowroomState = rememberViddikShowroomState(),
) {
    val current = state.selected

    Surface(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (current == null) {
                ViddikShowroomList(
                    components = components,
                    query = state.query,
                    onQueryChange = { state.query = it },
                    onSelect = { state.selected = it },
                )
            } else {
                ViddikShowroomDetail(current) { state.selected = null }
            }
        }
    }
}

/**
 * Whether [component] answers [query].
 *
 * The query is split on whitespace and every token has to appear somewhere in `"group name"`, so
 * `"wid but"` finds `Widgets / Button` — a browser is searched by half-remembered fragments, not by
 * exact prefixes. Matching is case-insensitive, and an all-whitespace query matches everything rather
 * than nothing.
 */
private val WHITESPACE = Regex("""\s+""")

internal fun matchesQuery(
    component: ViddikComponent,
    query: String,
): Boolean {
    val tokens = query.split(WHITESPACE).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return true
    val haystack = "${component.group} ${component.name}"
    return tokens.all { haystack.contains(it, ignoreCase = true) }
}

@Composable
private fun ViddikShowroomList(
    components: List<ViddikComponent>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (ViddikComponent) -> Unit,
) {
    val matches = remember(components, query) { components.filter { matchesQuery(it, query) } }
    // `toSortedMap()` would read better and is JVM-only (it returns a java.util.SortedMap), which
    // this module stopped being able to use the moment it grew iOS targets.
    val grouped = remember(matches) { matches.groupBy { it.group }.entries.sortedBy { it.key } }
    val listState = rememberLazyListState()

    // A narrowed list scrolled to where the wider one was is a list of blank space: the entries that
    // were under the viewport are the ones the query just removed.
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        ViddikShowroomSearchField(
            query = query,
            onQueryChange = onQueryChange,
            matchCount = matches.size,
            totalCount = components.size,
        )
        HorizontalDivider()
        if (grouped.isEmpty()) {
            ViddikShowroomEmpty(query)
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
    }
}

@Composable
private fun ViddikShowroomSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    totalCount: Int,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        singleLine = true,
        label = { Text("Search") },
        placeholder = { Text("group or name") },
        // The count is the field's own supporting text rather than a row of its own: it is a property
        // of the query, and putting it here means an empty query costs no vertical space on a phone.
        supportingText =
            if (query.isEmpty()) {
                null
            } else {
                { Text("$matchCount of $totalCount") }
            },
        trailingIcon =
            if (query.isEmpty()) {
                null
            } else {
                {
                    Text(
                        // "×" and not "✕": U+00D7 is in every font viddik can end up drawing with,
                        // including the bundled Roboto, and a glyph that is missing does not just
                        // draw wrong, it moves its neighbours.
                        text = "×",
                        style = MaterialTheme.typography.titleLarge,
                        modifier =
                            Modifier
                                .clickable { onQueryChange("") }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun ViddikShowroomEmpty(query: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = if (query.isBlank()) "No components in this module." else "Nothing matches \"$query\".",
            style = MaterialTheme.typography.bodyLarge,
        )
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
