package com.unbound.rpg.ui.play

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.unbound.rpg.ui.components.ErrorBanner
import com.unbound.rpg.ui.components.StatusChip
import com.unbound.rpg.ui.theme.NarrativeStyle
import com.unbound.rpg.ui.theme.PlayerEchoStyle

/**
 * The main screen. Designed as a place rather than a chat: the world's state is always visible at
 * the top, the prose is set like a page, and the input bar is persistent and reachable one-handed
 * (§76, §119).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    viewModel: PlayViewModel,
    onOpenJournal: () -> Unit,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    // Follow the newest entry, but only when new content actually arrives, so the player can scroll
    // back through their history without being yanked forward.
    LaunchedEffect(state.entries.size) {
        if (state.entries.isNotEmpty()) listState.animateScrollToItem(state.entries.lastIndex)
    }

    Scaffold(
        topBar = { SceneBar(state, onOpenJournal, onOpenMenu, onBack) },
        bottomBar = {
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = !state.thinking,
                suggestions = state.suggestedActions,
                onSuggestion = { suggestion ->
                    // A suggestion fills the box rather than submitting, so it remains a hint the
                    // player can edit rather than a button that takes their turn (§75).
                    input = suggestion
                },
                onSend = {
                    val text = input
                    input = ""
                    keyboard?.hide()
                    viewModel.submit(text)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { message ->
                ErrorBanner(
                    message = message,
                    retryable = state.errorRetryable,
                    onRetry = viewModel::retry,
                    onDismiss = viewModel::dismissError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (state.canLoadMore) {
                    item(key = "load-more") {
                        TextButton(onClick = viewModel::loadOlder, modifier = Modifier.fillMaxWidth()) {
                            Text("Read earlier")
                        }
                    }
                }
                items(state.entries, key = { it.id }) { entry ->
                    when (entry) {
                        is SceneEntry.PlayerAction -> PlayerEcho(entry.text)
                        is SceneEntry.Narration -> Narration(entry, state.developerMode)
                        is SceneEntry.SystemNote -> SystemNote(entry.text)
                        is SceneEntry.Picture -> ScenePicture(entry)
                    }
                }
                if (state.thinking) {
                    item(key = "thinking") { ThinkingIndicator() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SceneBar(state: PlayUiState, onOpenJournal: () -> Unit, onOpenMenu: () -> Unit, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Column {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.location?.name ?: "Nowhere",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        state.player?.let { player ->
                            Text(
                                "${player.name} · ${player.body.describe()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to your saves")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenJournal) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Open the journal")
                    }
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )

            // The world's state, always on screen: time, weather, money, who is here.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.game?.let { StatusChip(it.worldTime.display().substringAfter("— ")) }
                state.world?.let { StatusChip(it.weather) }
                state.player?.let { player ->
                    state.world?.let { StatusChip("${player.currency} ${it.currencyName}", emphasis = true) }
                }
            }

            if (state.presentNpcs.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp)
                        .semantics {
                            contentDescription = "Present: " + state.presentNpcs.joinToString(", ") { it.name }
                        },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.presentNpcs.take(4).forEach { npc -> StatusChip(npc.name) }
                    if (state.presentNpcs.size > 4) StatusChip("+${state.presentNpcs.size - 4}")
                }
            }
        }
    }
}

@Composable
private fun PlayerEcho(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                style = PlayerEchoStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Narration(entry: SceneEntry.Narration, developerMode: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        // Paragraphs are split so each gets real spacing; a single Text with newlines reads as a
        // wall on a phone.
        entry.text.split("\n").filter { it.isNotBlank() }.forEach { paragraph ->
            Text(
                paragraph.trim(),
                style = NarrativeStyle,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (developerMode && entry.diagnostics != null) {
            DiagnosticsStrip(entry)
        }
    }
}

@Composable
private fun DiagnosticsStrip(entry: SceneEntry.Narration) {
    val d = entry.diagnostics ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "turn ${entry.turnNumber} · ctx ${d.contextCharacters}c · in ${d.inputTokens} " +
                    "(cached ${d.cachedTokens}) · out ${d.outputTokens} · ${d.latencyMs}ms · " +
                    "${d.appliedOps} applied · ${d.rejected} rejected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "memories ${d.retrievedMemoryIds.size} · events ${d.retrievedEventIds.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.rejected.forEach { issue ->
                Text(issue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SystemNote(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun ScenePicture(entry: SceneEntry.Picture) {
    val bitmap = remember(entry.image.localPath) {
        entry.image.localPath?.let { path ->
            runCatching { android.graphics.BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.caption,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Text("Image no longer cached on this device") }
            }
        }
        Text(
            entry.caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            "The world is moving…",
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            AnimatedVisibility(visible = suggestions.isNotEmpty() && enabled) {
                LazyColumn(Modifier.heightIn(max = 148.dp)) {
                    items(suggestions, key = { it }) { suggestion ->
                        TextButton(
                            onClick = { onSuggestion(suggestion) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        ) {
                            Text(
                                suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text("What do you do?") },
                    maxLines = 5,
                    shape = RoundedCornerShape(20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && value.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) {
                    // The button itself carries the wait, so the player's thumb is already on the
                    // thing that is busy and there is nothing to tap again.
                    if (enabled) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
