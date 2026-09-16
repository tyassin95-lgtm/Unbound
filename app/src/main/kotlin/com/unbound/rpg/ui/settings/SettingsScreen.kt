package com.unbound.rpg.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.unbound.core.ai.CostSummary
import com.unbound.core.ai.ModelProfile
import com.unbound.core.model.ImageMode
import com.unbound.core.model.NarrationLength
import com.unbound.rpg.data.settings.SettingsState
import com.unbound.rpg.ui.components.SectionHeading

/**
 * One vendor, as the settings screen sees it.
 *
 * Each provider is configured independently — its own key, its own connection state, its own model
 * list — because the player may have one, the other, or both, and nothing about having one should
 * imply anything about the other.
 */
data class ProviderUiState(
    val id: String,
    val displayName: String,
    val maskedKey: String? = null,
    val models: List<ModelProfile> = emptyList(),
    val loadingModels: Boolean = false,
    val modelError: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testSucceeded: Boolean? = null,
) {
    val connected: Boolean get() = maskedKey != null
    val storyModels: List<ModelProfile> get() = models.filter { it.supportsStructuredOutput }
    val imageModels: List<ModelProfile> get() = models.filter { it.supportsImages }
}

data class SettingsUiState(
    val settings: SettingsState = SettingsState(),
    val providers: List<ProviderUiState> = emptyList(),
    val usage: CostSummary = CostSummary(),
    val imageCacheBytes: Long = 0,
) {
    fun provider(id: String?): ProviderUiState? = providers.firstOrNull { it.id == id }

    /** The vendor currently selected for the story. */
    val storyProvider: ProviderUiState? get() = provider(settings.textProviderId)

    /** Where pictures come from: its own choice, defaulting to whoever tells the story. */
    val pictureProvider: ProviderUiState? get() = provider(settings.imageProviderId ?: settings.textProviderId)

    val anyConnected: Boolean get() = providers.any { it.connected }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onStoreKey: (String, String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onTestKey: (String) -> Unit,
    onRefreshModels: (String) -> Unit,
    onPickProvider: (String) -> Unit,
    onPickFallbackProvider: (String?) -> Unit,
    onPickTextModel: (String) -> Unit,
    onPickImageModel: (String?) -> Unit,
    onPickImageProvider: (String?) -> Unit,
    onImageMode: (ImageMode) -> Unit,
    onNarrationLength: (NarrationLength) -> Unit,
    onSuggestions: (Boolean) -> Unit,
    onReducedMotion: (Boolean) -> Unit,
    onDeveloperMode: (Boolean) -> Unit,
    onLimits: (List<String>) -> Unit,
    onClearImageCache: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            ProviderSection(state, onStoreKey, onRemoveKey, onTestKey, onPickProvider, onPickFallbackProvider)
            ModelSection(state, onRefreshModels, onPickTextModel, onPickImageModel, onPickImageProvider, onImageMode)
            GameplaySection(state, onNarrationLength, onSuggestions, onReducedMotion)
            ContentSection(state, onLimits)
            UsageSection(state)
            StorageSection(state, onClearImageCache)
            DiagnosticsSection(state, onDeveloperMode)
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun ProviderSection(
    state: SettingsUiState,
    onStoreKey: (String, String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onTestKey: (String) -> Unit,
    onPickProvider: (String) -> Unit,
    onPickFallback: (String?) -> Unit,
) {
    SectionHeading("AI provider")
    Text(
        "UNBOUND uses your own account. Add a key for whichever service you want to play with — " +
            "you do not need both.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    state.providers.forEach { provider ->
        ProviderCard(
            provider = provider,
            isStoryProvider = provider.id == state.settings.textProviderId,
            onSelect = { onPickProvider(provider.id) },
            onStoreKey = { onStoreKey(provider.id, it) },
            onRemoveKey = { onRemoveKey(provider.id) },
            onTestKey = { onTestKey(provider.id) },
        )
    }

    // Only meaningful once two keys exist, so it stays out of the way until it does.
    val connected = state.providers.filter { it.connected }
    if (connected.size > 1) {
        Text("If the chosen provider is unavailable", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        ListItem(
            headlineContent = { Text("Stop and tell me") },
            supportingContent = { Text("The turn is not taken. Nothing in the world changes.") },
            trailingContent = {
                RadioButton(selected = state.settings.fallbackProviderId == null, onClick = { onPickFallback(null) })
            },
            modifier = Modifier.clickableRow { onPickFallback(null) },
        )
        connected.filter { it.id != state.settings.textProviderId }.forEach { other ->
            ListItem(
                headlineContent = { Text("Use ${other.displayName} for that turn") },
                supportingContent = {
                    Text(
                        "Only when ${state.storyProvider?.displayName ?: "your provider"} is rate-limited, " +
                            "down or unreachable — never for a refused request or a rejected key. " +
                            "You will be told when it happens.",
                    )
                },
                trailingContent = {
                    RadioButton(
                        selected = state.settings.fallbackProviderId == other.id,
                        onClick = { onPickFallback(other.id) },
                    )
                },
                modifier = Modifier.clickableRow { onPickFallback(other.id) },
            )
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderUiState,
    isStoryProvider: Boolean,
    onSelect: () -> Unit,
    onStoreKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onTestKey: () -> Unit,
) {
    var editing by rememberSaveable(provider.id) { mutableStateOf(false) }
    var newKey by rememberSaveable(provider.id) { mutableStateOf("") }
    var visible by rememberSaveable(provider.id) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(provider.displayName) },
        supportingContent = {
            Text(if (provider.connected) "Connected" else "Not connected")
        },
        trailingContent = {
            // Selectable only once it can actually serve a turn; choosing a provider with no key
            // would look like it had been accepted and then fail on the next turn.
            if (provider.connected) {
                RadioButton(selected = isStoryProvider, onClick = onSelect)
            }
        },
        modifier = if (provider.connected) Modifier.clickableRow(onSelect) else Modifier,
    )

    if (provider.connected && !editing) {
        ListItem(
            headlineContent = {
                // Never the whole key, even to its owner, once it has been stored (§82).
                Text(provider.maskedKey.orEmpty(), fontFamily = FontFamily.Monospace)
            },
            supportingContent = { Text("Stored in this device's secure hardware keystore.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTestKey, enabled = !provider.testing) { Text("Test") }
            OutlinedButton(onClick = { editing = true; newKey = "" }) { Text("Replace") }
            TextButton(onClick = { confirmRemove = true }) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        OutlinedTextField(
            value = newKey,
            onValueChange = { newKey = it.trim() },
            label = { Text("${provider.displayName} API key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text(keyHint(provider.id)) },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "Hide key" else "Show key",
                    )
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStoreKey(newKey); editing = false; newKey = "" }, enabled = newKey.isNotBlank()) {
                Text("Save key")
            }
            if (provider.connected) {
                TextButton(onClick = { editing = false; newKey = "" }) { Text("Cancel") }
            }
        }
    }

    if (provider.testing) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking with ${provider.displayName}…", style = MaterialTheme.typography.bodySmall)
        }
    }
    provider.testResult?.let { result ->
        Text(
            result,
            style = MaterialTheme.typography.bodySmall,
            color = if (provider.testSucceeded == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the ${provider.displayName} key?") },
            text = {
                Text(
                    "The key will be erased from this device. Your worlds, characters and history " +
                        "are untouched, and any other provider you have set up keeps working.",
                )
            },
            confirmButton = {
                TextButton(onClick = { onRemoveKey(); confirmRemove = false }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep it") } },
        )
    }
}

private fun keyHint(providerId: String): String = when (providerId) {
    "gemini" -> "Begins with AIza. Free from Google AI Studio."
    else -> "Begins with sk-."
}

@Composable
private fun ModelSection(
    state: SettingsUiState,
    onRefresh: (String) -> Unit,
    onPickText: (String) -> Unit,
    onPickImage: (String?) -> Unit,
    onPickImageProvider: (String?) -> Unit,
    onImageMode: (ImageMode) -> Unit,
) {
    val story = state.storyProvider
    if (story == null || !story.connected) {
        SectionHeading("Models")
        Text(
            "Add a key above and the models your account can reach will be listed here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    SectionHeading("Story model")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                story.loadingModels -> "Asking ${story.displayName} what your key can reach…"
                story.models.any { it.fromLiveCatalog } -> "From your ${story.displayName} account"
                else -> "Not yet fetched — showing what is known offline"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (story.loadingModels) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            TextButton(onClick = { onRefresh(story.id) }) { Text("Refresh") }
        }
    }
    // An indeterminate bar, because the request has no meaningful percentage and a bar that
    // pretends otherwise is worse than one that does not.
    if (story.loadingModels) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
    }
    story.modelError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    if (story.storyModels.isEmpty()) {
        Text(
            "No compatible model found yet. A story model must be able to return a strict " +
                "structured response, which is how the world stays consistent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    story.storyModels.forEach { model ->
        ModelRow(model, selected = state.settings.defaultTextModelId == model.id) { onPickText(model.id) }
    }

    val unusable = story.models.filter { !it.supportsStructuredOutput && !it.supportsImages && it.capabilities.isNotEmpty() }
    if (unusable.isNotEmpty()) {
        // Shown, but explained — never silently hidden, and never silently substituted (§7).
        Text(
            "Not usable for storytelling: ${unusable.take(8).joinToString(", ") { it.id }}. " +
                "These cannot guarantee the structured output the world engine needs.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    SectionHeading("Pictures")
    val picture = state.pictureProvider
    val withImages = state.providers.filter { it.connected && it.imageModels.isNotEmpty() }
    if (withImages.size > 1) {
        Text("Drawn by", style = MaterialTheme.typography.titleSmall)
        withImages.forEach { p ->
            ListItem(
                headlineContent = { Text(p.displayName) },
                trailingContent = {
                    RadioButton(
                        selected = picture?.id == p.id,
                        onClick = { onPickImageProvider(p.id.takeIf { it != state.settings.textProviderId }) },
                    )
                },
                modifier = Modifier.clickableRow {
                    onPickImageProvider(p.id.takeIf { it != state.settings.textProviderId })
                },
            )
        }
    }

    Text("Image model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    ListItem(
        headlineContent = { Text("None") },
        supportingContent = { Text("No pictures will be generated.") },
        trailingContent = {
            RadioButton(selected = state.settings.defaultImageModelId == null, onClick = { onPickImage(null) })
        },
        modifier = Modifier.clickableRow { onPickImage(null) },
    )
    picture?.imageModels.orEmpty().forEach { model ->
        ModelRow(model, selected = state.settings.defaultImageModelId == model.id) { onPickImage(model.id) }
    }

    Text("Image generation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    ImageMode.entries.forEach { mode ->
        ListItem(
            headlineContent = { Text(mode.display) },
            supportingContent = {
                Text(
                    when (mode) {
                        ImageMode.DISABLED -> "No pictures at all."
                        ImageMode.ON_DEMAND -> "Only when you ask. Recommended — every image is a separate charge."
                        ImageMode.AUTOMATIC_IMPORTANT -> "Also for major moments. Costs more."
                    },
                )
            },
            trailingContent = { RadioButton(selected = state.settings.imageMode == mode, onClick = { onImageMode(mode) }) },
            modifier = Modifier.clickableRow { onImageMode(mode) },
        )
    }
}

@Composable
private fun ModelRow(model: ModelProfile, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(model.displayName) },
        supportingContent = {
            Column {
                Text(model.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                if (model.recommendedFor.isNotBlank()) Text(model.recommendedFor, style = MaterialTheme.typography.labelSmall)
                model.knownLimitations.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                val price = model.inputCostPerMillion
                if (price != null) {
                    Text(
                        "\u2248 \$%.2f in / \$%.2f out per million tokens (estimate)".format(price, model.outputCostPerMillion ?: 0.0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickableRow(onClick),
    )
}

@Composable
private fun GameplaySection(
    state: SettingsUiState,
    onNarrationLength: (NarrationLength) -> Unit,
    onSuggestions: (Boolean) -> Unit,
    onReducedMotion: (Boolean) -> Unit,
) {
    SectionHeading("Gameplay")
    Text("Narration length", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))

    // A segmented row rather than chips: three equal-weight segments always fit the width, where
    // chips carrying their word ranges wrapped and left the last one half off-screen.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        NarrationLength.entries.forEachIndexed { index, length ->
            SegmentedButton(
                selected = state.settings.narrationLength == length,
                onClick = { onNarrationLength(length) },
                shape = SegmentedButtonDefaults.itemShape(index, NarrationLength.entries.size),
                label = { Text(length.display, maxLines = 1) },
            )
        }
    }
    Text(
        state.settings.narrationLength.range +
            " per turn, typically. Major moments may run longer. Applies to all your games.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    ListItem(
        headlineContent = { Text("Suggested actions") },
        supportingContent = { Text("Hints after each turn. You can always type anything instead.") },
        trailingContent = { Switch(checked = state.settings.suggestedActions, onCheckedChange = onSuggestions) },
    )
    ListItem(
        headlineContent = { Text("Reduced motion") },
        supportingContent = { Text("Fewer animations.") },
        trailingContent = { Switch(checked = state.settings.reducedMotion, onCheckedChange = onReducedMotion) },
    )
}

@Composable
private fun ContentSection(state: SettingsUiState, onLimits: (List<String>) -> Unit) {
    var text by remember(state.settings.personalLimits) {
        mutableStateOf(state.settings.personalLimits.joinToString(", "))
    }
    SectionHeading("Content")
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Your limits") },
        placeholder = { Text("no spiders, no sexual violence") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    Row {
        Button(onClick = { onLimits(text.split(',').map { it.trim() }.filter { it.isNotBlank() }) }) {
            Text("Save limits")
        }
    }
    Text(
        "Applied to new games. Inside a game, type \"limits: …\" to change them there. " +
            "UNBOUND is adult fiction; sexual content involving anyone under 18 is blocked in the " +
            "application itself, not merely discouraged.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun UsageSection(state: SettingsUiState) {
    val u = state.usage
    SectionHeading("Usage on this device")
    UsageRow("AI requests", u.requests.toString())
    UsageRow("Narrative turns", u.narrativeTurns.toString())
    UsageRow("Image requests", u.imageRequests.toString())
    UsageRow("Input tokens", u.inputTokens.toString())
    UsageRow("· of which cached", "${u.cachedTokens} (${(u.cacheHitRatio * 100).toInt()}%)")
    UsageRow("Output tokens", u.outputTokens.toString())
    UsageRow("Average per turn", "${u.averageInputTokensPerTurn} in / ${u.averageOutputTokensPerTurn} out")
    UsageRow("Average latency", "${u.averageLatencyMs} ms")
    UsageRow("Failed requests", u.failures.toString())
    UsageRow("Estimated cost", "≈ \$%.4f".format(u.estimatedCostUsd))
    UsageRow("Estimated per turn", "≈ \$%.4f".format(u.averageCostPerTurnUsd))
    Text(
        "These are local estimates based on published prices and the token counts OpenAI returned. " +
            "Your OpenAI account is the authority on what you are actually billed. Nothing on this " +
            "screen is sent anywhere.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StorageSection(state: SettingsUiState, onClearImageCache: () -> Unit) {
    SectionHeading("Storage")
    UsageRow("Cached images", "%.1f MB".format(state.imageCacheBytes / 1024.0 / 1024.0))
    OutlinedButton(onClick = onClearImageCache) { Text("Clear cached images") }
    Text(
        "Image files are deleted; the records stay, so the game still knows which pictures existed " +
            "and can make them again if you ask.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiagnosticsSection(state: SettingsUiState, onDeveloperMode: (Boolean) -> Unit) {
    SectionHeading("Diagnostics")
    ListItem(
        headlineContent = { Text("Developer mode") },
        supportingContent = {
            Text("Shows retrieval, token counts, applied state changes and rejected ones under each turn.")
        },
        trailingContent = { Switch(checked = state.settings.developerMode, onCheckedChange = onDeveloperMode) },
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
