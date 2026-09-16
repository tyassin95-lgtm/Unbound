package com.unbound.rpg.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.unbound.rpg.R

/**
 * First run (§5). States the BYOK arrangement plainly before asking for anything, because the
 * player is about to hand over a credential that will be billed to their own account.
 */
@Composable
fun OnboardingScreen(
    /** (providerId, key) — the player chooses which service to bring before typing anything. */
    onConnect: (String, String) -> Unit,
    onSkip: () -> Unit,
    testing: Boolean,
    testResult: String?,
    testSucceeded: Boolean?,
    onTest: (String) -> Unit,
    onContinue: () -> Unit,
) {
    var providerId by rememberSaveable { mutableStateOf("gemini") }
    var key by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    val providerName = if (providerId == "gemini") "Google Gemini" else "OpenAI"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Text(
            "UNBOUND",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Light,
            letterSpacing = 8.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(44.dp))

        Text(
            "Bring your own API key. It is stored in this device's secure hardware and used only " +
                "for requests to the service you choose, which is where usage and billing are " +
                "handled.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "UNBOUND has no account and no server of its own, and never sends your key or your " +
                "story anywhere but the service you pick. You can add the other one later.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))

        // Gemini leads because it has a free tier, which is the difference between trying the
        // game tonight and setting up billing first.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = providerId == "gemini",
                onClick = { providerId = "gemini"; key = "" },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Google Gemini") }
            SegmentedButton(
                selected = providerId == "openai",
                onClick = { providerId = "openai"; key = "" },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("OpenAI") }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            if (providerId == "gemini") {
                "Free to start: get a key from Google AI Studio. Free-tier requests are rate-limited."
            } else {
                "Pay as you go: get a key from platform.openai.com. Requires billing on your account."
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("$providerName API key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (visible) "Hide the key" else "Show the key while typing",
                    )
                }
            },
            supportingText = { Text(if (providerId == "gemini") "Begins with AIza" else "Begins with sk-") },
        )

        Spacer(Modifier.height(16.dp))

        if (testing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Checking the key with $providerName…", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }

        testResult?.let { message ->
            Surface(
                color = if (testSucceeded == true) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                    color = if (testSucceeded == true) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = { onConnect(providerId, key); onTest(providerId) },
            enabled = key.isNotBlank() && !testing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Connect $providerName")
        }

        if (testSucceeded == true) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Choose a model and begin")
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }

        Spacer(Modifier.height(8.dp))
        Text(
            "You can browse settings without a key, but a configured model is required before " +
                "a story can begin.",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}
