package com.unbound.rpg.ui.journal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unbound.core.journal.Journal
import com.unbound.rpg.ui.components.EmptyState
import com.unbound.rpg.ui.components.SectionHeading
import com.unbound.rpg.ui.components.StatusChip

private enum class JournalTab(val label: String) {
    OVERVIEW("Overview"), PEOPLE("People"), PLACES("Places"), FACTIONS("Factions"),
    THREADS("Open"), INVENTORY("Carried"), EVENTS("History"), SECRETS("Secrets"),
}

/**
 * Everything here is read straight from canonical state (§79). Nothing is generated, so the journal
 * works offline and costs nothing to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(journal: Journal?, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(JournalTab.OVERVIEW) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Journal") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the scene")
                        }
                    },
                )
                ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
                    JournalTab.entries.forEach { entry ->
                        Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.label) })
                    }
                }
            }
        },
    ) { padding ->
        if (journal == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (tab) {
                JournalTab.OVERVIEW -> {
                    item {
                        Text(journal.currentSituation, style = MaterialTheme.typography.bodyLarge)
                        SectionHeading("Character")
                        val c = journal.character
                        Text("${c.name}, ${c.age}, ${c.gender}", fontWeight = FontWeight.SemiBold)
                        Text(c.appearance, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(c.personality, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(c.condition)
                            StatusChip(c.currency, emphasis = true)
                        }
                        if (c.strengths.isNotEmpty()) {
                            SectionHeading("Good at")
                            Text(c.strengths.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (c.weaknesses.isNotEmpty()) {
                            SectionHeading("Bad at")
                            Text(c.weaknesses.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (c.goals.isNotEmpty()) {
                            SectionHeading("Working towards")
                            c.goals.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }

                JournalTab.PEOPLE -> {
                    if (journal.people.isEmpty()) item { EmptyState("Nobody yet", "People appear here once you have actually dealt with them.") }
                    items(journal.people, key = { it.id }) { person ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(person.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (!person.alive) {
                                        Spacer(Modifier.width(8.dp))
                                        StatusChip("dead")
                                    }
                                }
                                if (person.occupation.isNotBlank()) {
                                    Text(person.occupation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(person.appearance, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Toward you: ${person.relationship}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("Last seen ${person.lastSeen}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (person.knownFacts.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("What you know:", style = MaterialTheme.typography.labelSmall)
                                    person.knownFacts.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (person.notes.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Between you:", style = MaterialTheme.typography.labelSmall)
                                    person.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }

                JournalTab.PLACES -> {
                    if (journal.places.isEmpty()) item { EmptyState("Nowhere yet", "Places you have been are recorded here.") }
                    items(journal.places, key = { it.id }) { place ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(place.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (place.here) { Spacer(Modifier.width(8.dp)); StatusChip("you are here", emphasis = true) }
                                }
                                Text(place.description, style = MaterialTheme.typography.bodySmall)
                                if (place.condition != "intact") {
                                    Text("Condition: ${place.condition}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                JournalTab.FACTIONS -> {
                    if (journal.factions.isEmpty()) item { EmptyState("No powers yet", "Groups you learn about appear here.") }
                    items(journal.factions, key = { it.id }) { faction ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row {
                                    Text(faction.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (faction.member) { Spacer(Modifier.width(8.dp)); StatusChip("you are one of them", emphasis = true) }
                                }
                                Text(faction.purpose, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "They regard you: ${standingLabel(faction.standing)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (faction.reputation.isNotBlank()) {
                                    Text(faction.reputation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                JournalTab.THREADS -> {
                    if (journal.threads.isEmpty()) item { EmptyState("Nothing open", "Situations you are caught up in appear here.") }
                    items(journal.threads, key = { it.id }) { thread ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(thread.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(thread.description, style = MaterialTheme.typography.bodySmall)
                                if (thread.stakes.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("At stake: ${thread.stakes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusChip(thread.status)
                                    thread.deadlineNote?.let { StatusChip(it, emphasis = true) }
                                    if (thread.uncertain) StatusChip("you are not sure")
                                }
                            }
                        }
                    }
                }

                JournalTab.INVENTORY -> {
                    if (journal.inventory.isEmpty()) item { EmptyState("Empty-handed", "Nothing worth listing.") }
                    items(journal.inventory, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name + if (item.quantity > 1) " ×${item.quantity}" else "") },
                            supportingContent = { if (item.description.isNotBlank()) Text(item.description) },
                            trailingContent = { StatusChip(item.condition) },
                        )
                    }
                }

                JournalTab.EVENTS -> {
                    if (journal.importantEvents.isEmpty()) item { EmptyState("Nothing yet", "Things that mattered will be listed here.") }
                    items(journal.importantEvents, key = { it.eventId }) { event ->
                        ListItem(
                            headlineContent = { Text(event.summary, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text(event.whenText, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                JournalTab.SECRETS -> {
                    item {
                        SectionHeading("What you have found out")
                        if (journal.secrets.isEmpty()) {
                            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        journal.secrets.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }

                        SectionHeading("What you have heard")
                        if (journal.rumors.isEmpty()) {
                            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        journal.rumors.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

private fun standingLabel(standing: Int) = when {
    standing >= 60 -> "as one of their own"
    standing >= 25 -> "favourably"
    standing > -25 -> "with indifference"
    standing > -60 -> "with suspicion"
    else -> "as an enemy"
}
