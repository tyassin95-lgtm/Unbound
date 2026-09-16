package com.unbound.rpg.ui.saves

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unbound.core.model.GameRecord
import com.unbound.rpg.ui.components.EmptyState
import java.text.DateFormat
import java.util.Date

/** Save/load (§80). Every slot shows enough to tell two campaigns apart at a glance. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavesScreen(
    saves: List<SaveSummary>,
    hasCredential: Boolean,
    onContinue: (String) -> Unit,
    onNewGame: () -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (String) -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
    /** What just happened to a save, when it happened somewhere the player cannot see. */
    notice: String? = null,
    onNoticeShown: () -> Unit = {},
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SaveSummary?>(null) }
    var deleting by remember { mutableStateOf<SaveSummary?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            onNoticeShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("UNBOUND", letterSpacing = 4.sp, fontWeight = FontWeight.Light)
                        Text(
                            "Living World AI RPG",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewGame,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New game") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!hasCredential) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "No OpenAI key is set up yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Your existing saves are still here and can be read offline, but a new " +
                                "turn needs a key and a model.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onSettings) { Text("Set one up") }
                    }
                }
            }

            if (saves.isEmpty()) {
                EmptyState(
                    title = "No worlds yet",
                    body = "Start a new game and the world will begin without you — people, places " +
                        "and quarrels already in motion.",
                )
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(saves, key = { it.game.id }) { save ->
                    OutlinedCard(
                        onClick = { onContinue(save.game.id) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(save.game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${save.characterName} · ${save.game.settingName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    save.locationName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Turn ${save.game.turnNumber} · ${save.game.worldTime.display()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Last played ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(save.game.updatedAtEpochMs))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { menuFor = save.game.id }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Options for ${save.game.title}")
                                }
                                DropdownMenu(expanded = menuFor == save.game.id, onDismissRequest = { menuFor = null }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; renaming = save })
                                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuFor = null; onDuplicate(save.game.id) })
                                    DropdownMenuItem(text = { Text("Export") }, onClick = { menuFor = null; onExport(save.game.id) })
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                        onClick = { menuFor = null; deleting = save },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import a save file") }
                }
            }
        }
    }

    renaming?.let { save ->
        var title by remember(save.game.id) { mutableStateOf(save.game.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title") })
            },
            confirmButton = {
                TextButton(onClick = { onRename(save.game.id, title); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { save ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this world?") },
            text = {
                Text(
                    "\"${save.game.title}\" and everything in it — ${save.game.turnNumber} turns, its " +
                        "people, its history — will be permanently removed from this device. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onDelete(save.game.id); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }
}

data class SaveSummary(
    val game: GameRecord,
    val characterName: String,
    val locationName: String,
)
