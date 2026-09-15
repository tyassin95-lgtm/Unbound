package com.unbound.rpg.ui.play

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.unbound.core.narrative.NarrativeFormatter
import com.unbound.core.narrative.NarrativeStyleKind
import com.unbound.rpg.ui.theme.NarrativeColors
import com.unbound.rpg.ui.theme.NarrativeStyle

/**
 * Renders a turn's prose with speech and messages styled.
 *
 * Parsing lives in `core` and is unit-tested there, including a property test that no text is ever
 * lost. This composable only maps the resulting span kinds onto colours, so a styling change never
 * risks the text itself.
 */
@Composable
fun NarrativeText(
    narrative: String,
    playerDialogue: List<String>,
    modifier: Modifier = Modifier,
) {
    val playerColor = NarrativeColors.playerSpeech
    val speechColor = NarrativeColors.speech
    val commColor = NarrativeColors.communication
    val proseColor = MaterialTheme.colorScheme.onBackground

    val paragraphs = remember(narrative, playerDialogue) {
        NarrativeFormatter.format(narrative, playerDialogue)
    }

    Column(modifier.fillMaxWidth()) {
        paragraphs.forEach { paragraph ->
            val annotated: AnnotatedString = buildAnnotatedString {
                paragraph.spans.forEach { span ->
                    val style = when (span.kind) {
                        NarrativeStyleKind.PROSE -> SpanStyle(color = proseColor)
                        NarrativeStyleKind.PLAYER_SPEECH ->
                            SpanStyle(color = playerColor, fontWeight = FontWeight.Medium)
                        NarrativeStyleKind.SPEECH -> SpanStyle(color = speechColor)
                        // Italic as well as coloured: a message is not spoken in the room, and the
                        // slant carries that even for a reader who cannot distinguish the hue.
                        NarrativeStyleKind.COMMUNICATION ->
                            SpanStyle(color = commColor, fontStyle = FontStyle.Italic)
                    }
                    withStyle(style) { append(span.text) }
                }
            }
            Text(
                text = annotated,
                style = NarrativeStyle,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}
