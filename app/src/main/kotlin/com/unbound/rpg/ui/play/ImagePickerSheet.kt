package com.unbound.rpg.ui.play

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unbound.rpg.domain.ImageOptions
import com.unbound.rpg.domain.ImageRequestKind

/**
 * What can be drawn at this exact point in the story.
 *
 * The options are built from [ImageOptions], which is rebuilt from canonical state every turn — so
 * the people offered here are the people actually in the room now, and the scene is the scene that
 * was just narrated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePickerSheet(
    options: ImageOptions,
    onPick: (ImageRequestKind) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Draw something", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            if (!options.available) {
                Text(
                    options.unavailableReason.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }

            Text(
                "Each picture is a separate request on your own OpenAI key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            PickerRow(
                icon = Icons.Default.Landscape,
                title = "This moment",
                subtitle = "The scene as it stands, everyone in it, doing what they are doing.",
                onClick = { onPick(ImageRequestKind.Scene) },
            )

            if (options.canDrawInteraction) {
                PickerRow(
                    icon = Icons.Default.Groups,
                    title = "What is passing between you",
                    subtitle = "Close on the faces and hands of " +
                        options.present.take(3).joinToString(", ") { it.name } + ".",
                    onClick = { onPick(ImageRequestKind.Interaction(options.present.map { it.id })) },
                )
            }

            PickerRow(
                icon = Icons.Default.Portrait,
                title = options.locationName,
                subtitle = "The place itself, empty, as it looks right now.",
                onClick = { onPick(ImageRequestKind.Place) },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                "SOMEONE'S FACE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            PickerRow(
                icon = Icons.Default.Person,
                title = options.playerName,
                subtitle = "Your own portrait.",
                onClick = { onPick(ImageRequestKind.Person(null)) },
            )

            options.present.forEach { npc ->
                PickerRow(
                    icon = Icons.Default.Person,
                    title = npc.name,
                    subtitle = npc.occupation.ifBlank { "Here with you now." },
                    onClick = { onPick(ImageRequestKind.Person(npc.id)) },
                )
            }

            if (options.present.isEmpty()) {
                Text(
                    "There is nobody else here to draw.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PickerRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
