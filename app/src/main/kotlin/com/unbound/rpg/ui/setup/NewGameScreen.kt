package com.unbound.rpg.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unbound.core.content.Characters
import com.unbound.core.content.SeedWorld
import com.unbound.core.content.Settings
import com.unbound.core.model.CharacterTemplate
import com.unbound.core.model.Tone
import com.unbound.rpg.ui.components.SectionHeading

/** New Game, in the order §12 sets out: character, setting, opening, then the world is built. */
enum class NewGameStep { CHARACTER, EDIT_CHARACTER, SETTING, OPENING }

data class NewGameDraft(
    val template: CharacterTemplate? = null,
    val name: String = "",
    val age: String = "",
    val gender: String = "",
    val appearance: String = "",
    val personality: String = "",
    val desires: String = "",
    val fears: String = "",
    val skills: String = "",
    val weaknesses: String = "",
    val background: String = "",
    val goal: String = "",
    val secret: String = "",
    val seed: SeedWorld? = null,
    val hook: String? = null,
    val tone: Tone? = null,
    val limits: String = "",
) {
    val ageInt: Int? get() = age.toIntOrNull()
    val valid: Boolean
        get() = name.isNotBlank() && (ageInt ?: 0) >= 18 && appearance.isNotBlank() && seed != null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGameScreen(
    onCreate: (NewGameDraft) -> Unit,
    onCancel: () -> Unit,
    creating: Boolean,
) {
    var step by rememberSaveable { mutableStateOf(NewGameStep.CHARACTER) }
    var draft by remember { mutableStateOf(NewGameDraft()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            NewGameStep.CHARACTER -> "Who are you?"
                            NewGameStep.EDIT_CHARACTER -> "Your character"
                            NewGameStep.SETTING -> "Where?"
                            NewGameStep.OPENING -> "How does it start?"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        step = when (step) {
                            NewGameStep.CHARACTER -> return@IconButton onCancel()
                            NewGameStep.EDIT_CHARACTER -> NewGameStep.CHARACTER
                            NewGameStep.SETTING -> NewGameStep.EDIT_CHARACTER
                            NewGameStep.OPENING -> NewGameStep.SETTING
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (step) {
                NewGameStep.CHARACTER -> CharacterPicker(
                    onPick = { template ->
                        draft = draft.fromTemplate(template)
                        step = NewGameStep.EDIT_CHARACTER
                    },
                    onBlank = {
                        draft = NewGameDraft()
                        step = NewGameStep.EDIT_CHARACTER
                    },
                )

                NewGameStep.EDIT_CHARACTER -> CharacterEditor(
                    draft = draft,
                    onChange = { draft = it },
                    onNext = { step = NewGameStep.SETTING },
                )

                NewGameStep.SETTING -> SettingPicker(
                    onPick = { seed ->
                        draft = draft.copy(seed = seed, tone = draft.tone ?: seed.toneHint)
                        step = NewGameStep.OPENING
                    },
                )

                NewGameStep.OPENING -> OpeningPicker(
                    draft = draft,
                    onChange = { draft = it },
                    creating = creating,
                    onBegin = { onCreate(draft) },
                )
            }
        }
    }
}

private fun NewGameDraft.fromTemplate(t: CharacterTemplate) = copy(
    template = t,
    name = t.name,
    age = t.age.toString(),
    gender = t.gender,
    appearance = t.appearance.summary,
    personality = t.personality,
    desires = t.desires,
    fears = t.fears,
    skills = t.skills.joinToString(", "),
    weaknesses = t.weaknesses.joinToString(", "),
    background = t.background,
    goal = t.goals.firstOrNull().orEmpty(),
    secret = t.secrets.firstOrNull().orEmpty(),
)

@Composable
private fun CharacterPicker(onPick: (CharacterTemplate) -> Unit, onBlank: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Take one of these as they are, change anything you like, or start from nothing. " +
                    "None of it is fixed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(Characters.ALL, key = { it.id }) { template ->
            OutlinedCard(
                onClick = { onPick(template) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${template.age} · ${template.gender}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(template.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        template.appearance.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onBlank, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Write my own from scratch")
            }
        }
    }
}

@Composable
private fun CharacterEditor(draft: NewGameDraft, onChange: (NewGameDraft) -> Unit, onNext: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Field("Name", draft.name) { onChange(draft.copy(name = it)) }

        OutlinedTextField(
            value = draft.age,
            onValueChange = { onChange(draft.copy(age = it.filter { c -> c.isDigit() }.take(3))) },
            label = { Text("Age") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = draft.age.isNotBlank() && (draft.ageInt ?: 0) < 18,
            supportingText = {
                Text(
                    if (draft.age.isNotBlank() && (draft.ageInt ?: 0) < 18) {
                        "The protagonist must be 18 or older."
                    } else {
                        "Stored explicitly. Used by the content safeguards."
                    },
                )
            },
        )

        Field("Gender / presentation", draft.gender) { onChange(draft.copy(gender = it)) }
        Field("Appearance", draft.appearance, lines = 3) { onChange(draft.copy(appearance = it)) }
        Field("Personality", draft.personality, lines = 2) { onChange(draft.copy(personality = it)) }
        Field("What they want", draft.desires, lines = 2) { onChange(draft.copy(desires = it)) }
        Field("What they fear", draft.fears, lines = 2) { onChange(draft.copy(fears = it)) }
        Field("Good at (comma separated)", draft.skills) { onChange(draft.copy(skills = it)) }
        Field("Bad at (comma separated)", draft.weaknesses) { onChange(draft.copy(weaknesses = it)) }
        Field("Background", draft.background, lines = 3) { onChange(draft.copy(background = it)) }
        Field("Current goal", draft.goal, lines = 2) { onChange(draft.copy(goal = it)) }
        Field("A secret nobody else knows", draft.secret, lines = 2) { onChange(draft.copy(secret = it)) }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onNext,
            enabled = draft.name.isNotBlank() && (draft.ageInt ?: 0) >= 18 && draft.appearance.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Choose a world") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Field(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        singleLine = lines == 1,
        minLines = lines,
        maxLines = lines.coerceAtLeast(1) + 2,
    )
}

@Composable
private fun SettingPicker(onPick: (SeedWorld) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Each world already has its own people, places and quarrels running before you " +
                    "arrive. You can go anywhere in it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(Settings.ALL, key = { it.id }) { seed ->
            OutlinedCard(
                onClick = { onPick(seed) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(seed.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(seed.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        seed.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${seed.locations.size} places · ${seed.npcs.size} people · ${seed.factions.size} factions already in motion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpeningPicker(
    draft: NewGameDraft,
    onChange: (NewGameDraft) -> Unit,
    creating: Boolean,
    onBegin: () -> Unit,
) {
    val seed = draft.seed ?: return
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionHeading("Opening situation")
        Text(
            "Pick one, or refuse all of them and start in the ordinary run of your life.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        seed.openingHooks.forEach { hook ->
            val selected = draft.hook == hook
            OutlinedCard(
                onClick = { onChange(draft.copy(hook = if (selected) null else hook)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Text(hook, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
            }
        }

        OutlinedCard(
            onClick = { onChange(draft.copy(hook = null)) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            border = BorderStroke(
                if (draft.hook == null) 2.dp else 1.dp,
                if (draft.hook == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Text(
                "None of these. Just drop me into the world.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }

        SectionHeading("Tone")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tone.entries.forEach { tone ->
                FilterChip(
                    selected = draft.tone == tone,
                    onClick = { onChange(draft.copy(tone = tone)) },
                    label = { Text(tone.display) },
                )
            }
        }

        SectionHeading("Your limits")
        OutlinedTextField(
            value = draft.limits,
            onValueChange = { onChange(draft.copy(limits = it)) },
            label = { Text("Anything you do not want in your story") },
            placeholder = { Text("no spiders, no sexual violence") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Text(
            "Separated by commas. These are respected exactly as written, and you can change them " +
                "at any time by typing \"limits: …\".",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onBegin,
            enabled = draft.valid && !creating,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (creating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Building the world…")
            } else {
                Text("Begin")
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
