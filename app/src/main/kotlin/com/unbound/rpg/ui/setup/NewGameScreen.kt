package com.unbound.rpg.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unbound.core.content.Characters
import com.unbound.core.content.OpeningOption
import com.unbound.rpg.domain.OpeningSuggestions
import com.unbound.core.content.SeedWorld
import com.unbound.core.content.Settings
import com.unbound.core.model.CharacterTemplate
import com.unbound.core.model.Tone
import com.unbound.rpg.ui.CreationStage
import com.unbound.rpg.ui.CreationState
import com.unbound.rpg.ui.components.SectionHeading

/** New Game, in the order §12 sets out: character, setting, opening, then the world is built. */
enum class NewGameStep { CHARACTER, EDIT_CHARACTER, SETTING, CUSTOM_WORLD, OPENING }

@kotlinx.serialization.Serializable
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
    /** The player's own description, when they are inventing a world rather than picking one. */
    val premise: String = "",
    val hook: String? = null,
    /** An opening the player wrote instead of choosing one. */
    val customOpening: String = "",
    /**
     * Blank until something decides it: a chosen template's authored value, or the amount the
     * opening generator judged for this character in this world. Never a fixed default.
     */
    val startingCurrency: String = "",
    val startingCurrencyReason: String = "",
    val startingPossessions: List<String> = emptyList(),
    val tone: Tone? = null,
    val limits: String = "",
) {
    val ageInt: Int? get() = age.toIntOrNull()

    companion object {
        private val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }

        /**
         * Character creation is a lot of typing across several steps, and losing it to a rotation
         * or a backgrounded process is not something a player forgives. The whole draft round-trips
         * through JSON so every field survives, not just the ones that happen to be primitives.
         */
        val Saver: androidx.compose.runtime.saveable.Saver<NewGameDraft, String> =
            androidx.compose.runtime.saveable.Saver(
                save = { runCatching { json.encodeToString(serializer(), it) }.getOrNull() },
                restore = { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() },
            )
    }
    val valid: Boolean
        get() = name.isNotBlank() && (ageInt ?: 0) >= 18 && appearance.isNotBlank() && seed != null

    /** What the opening scene is actually built from. Null means "just drop me in". */
    fun chosenOpening(): String? =
        customOpening.trim().takeIf { it.isNotBlank() } ?: hook?.takeIf { it.isNotBlank() }

    val currencyAmount: Long? get() = startingCurrency.trim().takeIf { it.isNotBlank() }?.toLongOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGameScreen(
    onCreate: (NewGameDraft) -> Unit,
    onCancel: () -> Unit,
    creation: CreationState,
    customWorld: SeedWorld?,
    suggestions: OpeningSuggestions,
    onGenerateWorld: (NewGameDraft) -> Unit,
    onGenerateOpenings: (NewGameDraft) -> Unit,
    onClearGeneratedWorld: () -> Unit,
    onDismissError: () -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(NewGameStep.CHARACTER) }
    // Saveable, not merely remembered. The step survived a rotation while everything the player
    // had typed did not, so they landed back on step three with an empty character.
    var draft by rememberSaveable(stateSaver = NewGameDraft.Saver) { mutableStateOf(NewGameDraft()) }

    // Adopt the generated purse and possessions, but never overwrite a number the player typed.
    LaunchedEffect(suggestions) {
        val suggested = suggestions.startingCurrency
        if (suggested != null && draft.startingCurrency.isBlank()) {
            draft = draft.copy(
                startingCurrency = suggested.toString(),
                startingCurrencyReason = suggestions.startingCurrencyReason,
            )
        }
        if (suggestions.startingPossessions.isNotEmpty() && draft.startingPossessions.isEmpty()) {
            draft = draft.copy(startingPossessions = suggestions.startingPossessions)
        }
    }

    // When a world finishes generating, adopt it and move on. Doing this here rather than in the
    // callback keeps the generation asynchronous without the screen having to poll.
    LaunchedEffect(customWorld) {
        val generated = customWorld ?: return@LaunchedEffect
        if (draft.seed?.id != generated.id) {
            draft = draft.copy(seed = generated, tone = draft.tone ?: generated.toneHint)
            step = NewGameStep.OPENING
            onGenerateOpenings(draft)
        }
    }

    fun back() {
        when (step) {
            NewGameStep.CHARACTER -> onCancel()
            NewGameStep.EDIT_CHARACTER -> step = NewGameStep.CHARACTER
            NewGameStep.SETTING -> step = NewGameStep.EDIT_CHARACTER
            NewGameStep.CUSTOM_WORLD -> step = NewGameStep.SETTING
            NewGameStep.OPENING -> {
                // Going back from a generated world discards it, so the player is never left with
                // openings that belong to a world they have navigated away from.
                onClearGeneratedWorld()
                draft = draft.copy(seed = null, hook = null, customOpening = "")
                step = NewGameStep.SETTING
            }
        }
    }

    // A hardware back press during a network call would strand the request and confuse the flow.
    BackHandler(enabled = !creation.busy) { back() }

    Box {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when (step) {
                                NewGameStep.CHARACTER -> "Who are you?"
                                NewGameStep.EDIT_CHARACTER -> "Your character"
                                NewGameStep.SETTING -> "Where?"
                                NewGameStep.CUSTOM_WORLD -> "Your own world"
                                NewGameStep.OPENING -> "How does it start?"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { back() }, enabled = !creation.busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
                            onGenerateOpenings(draft.copy(seed = seed, tone = draft.tone ?: seed.toneHint))
                        },
                        onCustom = { step = NewGameStep.CUSTOM_WORLD },
                    )

                    NewGameStep.CUSTOM_WORLD -> CustomWorldEditor(
                        draft = draft,
                        onChange = { draft = it },
                        busy = creation.busy,
                        onGenerate = { onGenerateWorld(draft) },
                    )

                    NewGameStep.OPENING -> OpeningPicker(
                        draft = draft,
                        onChange = { draft = it },
                        openings = suggestions.openings,
                        openingsFailed = suggestions.failed,
                        loadingOpenings = (creation as? CreationState.Working)?.stage == CreationStage.FINDING_OPENINGS,
                        busy = creation.busy,
                        onRegenerate = { onGenerateOpenings(draft) },
                        onBegin = { onCreate(draft) },
                    )
                }
            }
        }

        // Blocking, on top of everything, so no control can be tapped while work is in flight.
        (creation as? CreationState.Working)?.let { CreationOverlay(it.stage) }
    }

    (creation as? CreationState.Failed)?.let { failure ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("That did not work") },
            text = {
                Column {
                    Text(failure.message)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Nothing was saved. Your character and your choices are still here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismissError) { Text("Try again") } },
        )
    }
}

/**
 * Full-screen, non-dismissable progress.
 *
 * World creation is several seconds of network work. Without this the buttons simply stop
 * responding, which reads as a hang and invites the player to tap repeatedly — so it names the
 * stage, shows how far along it is, and swallows every touch behind it.
 */
@Composable
private fun CreationOverlay(stage: CreationStage) {
    Surface(
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.86f),
        modifier = Modifier
            .fillMaxSize()
            // Consumes all input, including taps aimed at the controls underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { contentDescription = "${stage.headline}. Please wait." },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(36.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(52.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                stage.headline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stage.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            // A step counter, so a long wait still reads as motion.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(CreationStage.TOTAL) { index ->
                    val done = index < stage.step
                    Box(
                        Modifier
                            .size(width = if (index == stage.step - 1) 28.dp else 8.dp, height = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (done) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Step ${stage.step} of ${CreationStage.TOTAL}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    // An authored character comes with an authored purse; it is still editable below.
    startingCurrency = t.startingCurrency.toString(),
    startingCurrencyReason = "what ${t.name} is written as having",
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
private fun SettingPicker(onPick: (SeedWorld) -> Unit, onCustom: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Each world already has its own people, places and quarrels running before you " +
                    "arrive. You can go anywhere in it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Offered first, because a player who wants their own world should not have to scroll past
        // six they do not want.
        item {
            OutlinedCard(
                onClick = onCustom,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Build your own world",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Describe anywhere you like, in a sentence or a page. UNBOUND will lay out " +
                            "its streets, fill them with people who already want things, and set its " +
                            "quarrels running before you arrive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Text(
                "OR START FROM ONE OF THESE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
                modifier = Modifier.padding(top = 8.dp),
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

/** Where the player describes a world in their own words (§16). */
@Composable
private fun CustomWorldEditor(
    draft: NewGameDraft,
    onChange: (NewGameDraft) -> Unit,
    busy: Boolean,
    onGenerate: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Describe the world you want to be in. A sentence is enough; a page works too.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Say what kind of place it is, what is wrong with it, and what people there care about. " +
                "You do not need to invent names — that part is handled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = draft.premise,
            onValueChange = { onChange(draft.copy(premise = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your world") },
            placeholder = { Text(WORLD_PLACEHOLDER) },
            minLines = 6,
            maxLines = 14,
            enabled = !busy,
        )

        Spacer(Modifier.height(14.dp))
        SectionHeading("Or start from an idea")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WORLD_SPARKS.forEach { spark ->
                OutlinedCard(
                    onClick = { onChange(draft.copy(premise = spark)) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        spark,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        SectionHeading("Tone")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tone.entries.forEach { tone ->
                FilterChip(
                    selected = draft.tone == tone,
                    onClick = { onChange(draft.copy(tone = tone)) },
                    label = { Text(tone.display) },
                    enabled = !busy,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGenerate,
            enabled = draft.premise.trim().length >= 12 && !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Build this world")
        }
        Text(
            "This makes one request to your AI provider and usually takes a few seconds.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
    }
}

private const val WORLD_PLACEHOLDER =
    "A mountain monastery that has taken in refugees from a war it is trying very hard not to " +
        "have an opinion about. Winter is closing the passes. Somebody down in the guest wing is " +
        "not who they say they are."

private val WORLD_SPARKS = listOf(
    "A generation ship four hundred years into a six-hundred-year voyage. The people who planned it are long dead and the people flying it have started to disagree about where they are going.",
    "A river town where the water has begun running backwards two days a month, and the church has declared it a miracle before anyone could check.",
    "The last functioning hospital in a city under siege, staffed by people who no longer ask which side a patient fought on.",
    "A luxury hotel in a country that stopped existing eight months ago. The guests have not left and the staff have not been paid.",
)

@Composable
private fun OpeningPicker(
    draft: NewGameDraft,
    onChange: (NewGameDraft) -> Unit,
    openings: List<OpeningOption>,
    openingsFailed: Boolean,
    loadingOpenings: Boolean,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onBegin: () -> Unit,
) {
    val seed = draft.seed ?: return
    var writingOwn by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {

        // What the world turned out to be — particularly worth showing for a generated one, since
        // the player has not seen it before.
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(seed.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${seed.region} · ${seed.era}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(seed.summary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${seed.locations.size} places · ${seed.npcs.size} people · " +
                        "${seed.factions.size} factions · ${seed.threads.size} situations already running",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionHeading("Ways in")
        Text(
            "Openings written for this character, in this place. Take one, or write your own, or " +
                "refuse all of them and start in the ordinary run of your life.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (openingsFailed && !loadingOpenings) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Text(
                    "These were put together from your world rather than written for your " +
                        "character — your AI provider could not be reached. You can try again, write your " +
                        "own, or take one of these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (loadingOpenings) {
            repeat(3) { OpeningPlaceholder() }
        } else {
            openings.forEach { opening ->
                val selected = !writingOwn && draft.hook == opening.situation
                OutlinedCard(
                    onClick = {
                        writingOwn = false
                        onChange(draft.copy(hook = if (selected) null else opening.situation, customOpening = ""))
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    border = BorderStroke(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            opening.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(opening.situation, style = MaterialTheme.typography.bodyMedium)
                        if (opening.pressure.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                opening.pressure,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Always available, never conditional on there being something to replace. When the
            // list came back empty this button was hidden too, which left no way out of the step.
            TextButton(onClick = onRegenerate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (openingsFailed) "Try again" else "Show me different ones")
            }
        }

        // Writing your own, which must be as easy as taking one of the offered ones (§2.3).
        OutlinedCard(
            onClick = {
                writingOwn = true
                onChange(draft.copy(hook = null))
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            border = BorderStroke(
                if (writingOwn) 2.dp else 1.dp,
                if (writingOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Write my own opening",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (writingOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (writingOwn) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = draft.customOpening,
                        onValueChange = { onChange(draft.copy(customOpening = it, hook = null)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("You have been awake for two days and the thing you were watching for has finally arrived.") },
                        minLines = 3,
                        maxLines = 8,
                    )
                }
            }
        }

        OutlinedCard(
            onClick = {
                writingOwn = false
                onChange(draft.copy(hook = null, customOpening = ""))
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            border = BorderStroke(
                if (!writingOwn && draft.hook == null) 2.dp else 1.dp,
                if (!writingOwn && draft.hook == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Text(
                "None of these. Just drop me into the world.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }

        SectionHeading("What you have on you")
        if (draft.startingCurrencyReason.isNotBlank()) {
            Text(
                draft.startingCurrencyReason.replaceFirstChar { it.uppercase() } + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = draft.startingCurrency,
            onValueChange = { onChange(draft.copy(startingCurrency = it.filter { c -> c.isDigit() }.take(7))) },
            label = { Text(seed.currencyName.replaceFirstChar { it.uppercase() }) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(
                    if (draft.startingCurrency.isBlank()) {
                        "Judged from who you are and what you do. Change it if it does not fit."
                    } else {
                        "Change it if it does not fit your idea of this character."
                    },
                )
            },
        )
        if (draft.startingPossessions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Carrying", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            // Tapping removes it, which is the only edit worth having here — anything the player
            // wants to add, they can pick up in the story.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.startingPossessions.forEach { item ->
                    InputChip(
                        selected = true,
                        onClick = { onChange(draft.copy(startingPossessions = draft.startingPossessions - item)) },
                        label = { Text(item) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove $item", Modifier.size(16.dp)) },
                    )
                }
            }
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
            enabled = draft.valid && !busy && !loadingOpenings,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Begin")
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** Skeleton rows while openings are being written, so the step does not sit empty. */
@Composable
private fun OpeningPlaceholder() {
    val shimmer = rememberInfiniteTransition(label = "opening-placeholder")
    val alpha by shimmer.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth(0.5f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
