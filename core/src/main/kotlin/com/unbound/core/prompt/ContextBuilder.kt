package com.unbound.core.prompt

import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.GameEvent
import com.unbound.core.memory.RetrievedContext
import com.unbound.core.model.FactionRecord
import com.unbound.core.model.GameRecord
import com.unbound.core.model.ItemRecord
import com.unbound.core.model.LocationRecord
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.PlayerRecord
import com.unbound.core.model.ThreadRecord
import com.unbound.core.model.WorldRecord
import com.unbound.core.relationship.RelationshipRecord

/** Everything dynamic that a turn may send. Assembled by the pipeline, rendered by [ContextBuilder]. */
data class TurnContext(
    val game: GameRecord,
    val world: WorldRecord,
    val player: PlayerRecord,
    val location: LocationRecord,
    val presentNpcs: List<NpcRecord>,
    val relevantNpcs: List<NpcRecord>,
    val relationships: Map<String, RelationshipRecord>,
    val npcKnowledge: Map<String, List<KnowledgeRecord>>,
    val playerKnowledge: List<KnowledgeRecord>,
    val inventory: List<ItemRecord>,
    val factions: List<FactionRecord>,
    val threads: List<ThreadRecord>,
    val rumors: List<RumorRecord>,
    val retrieved: RetrievedContext,
    /** What the world did on its own since the player last acted. */
    val worldNotes: List<String> = emptyList(),
    val nearbyLocations: List<LocationRecord>,
    val repetitionWarning: String? = null,
    /** Staging for this turn that the player did not do — currently only the opening scene. */
    val directive: String? = null,
    /**
     * The last few turns as they were actually played, oldest first.
     *
     * This was the single largest continuity gap: recent turns were read every turn and used only
     * to detect repetition, so the model never saw a word of what had just been said and had to
     * reconstruct the conversation from summaries of it.
     */
    val recentTurns: List<com.unbound.core.model.TurnRecord> = emptyList(),
    /** Promises, debts and deals that are still open. Canonical state, not memory. */
    val commitments: List<com.unbound.core.continuity.CommitmentRecord> = emptyList(),
    /** Event id -> the event that caused it, so the present can explain itself. */
    val causes: Map<String, GameEvent> = emptyMap(),
    /** What has happened at this place before, so a location carries its own past. */
    val locationHistory: List<String> = emptyList(),
)

/**
 * Renders the dynamic half of the prompt: a dossier on the world as it stands, not a chat log.
 *
 * Output is deliberately terse, labelled and near-tabular rather than prose: every token spent
 * restating the world is a token not spent on the story, and this block is sent on *every* turn.
 * The section order is fixed so that diffs between turns are small and legible when debugging.
 *
 * ## The four kinds of statement
 *
 * The model is given, and told apart, four different things — and confusing them is what produces
 * a world that contradicts itself:
 *
 *  * **CURRENT TRUTH** — canonical state. The database says so, so it is so.
 *  * **HISTORY** — what happened, and where current truth came from. Fixed, past tense.
 *  * **MEMORY** — what a mind retains about the past. Partial by nature; silence is not evidence.
 *  * **BELIEF** — what someone holds to be true, which is allowed to be wrong.
 *
 * Sections are ordered stable-first: the world, the player's identity and the rules change rarely,
 * so keeping them at the front leaves a long shared prefix between consecutive turns for a
 * provider's prompt cache to hit. Volatile material — the scene, the transcript, this turn's
 * retrieval — comes last.
 */
class ContextBuilder {

    private companion object {
        const val MAX_RELATIONSHIP_REASONS = 3

        /** Per narration in the transcript. Enough to carry a scene, not enough to become one. */
        const val TRANSCRIPT_CHARS = 700
    }

    fun build(ctx: TurnContext): String = buildString {
        // First, and stated as canon. The opening situation used to arrive as the player's typed
        // action, where it lost to the world state it contradicted — the clock said morning, the
        // situation said evening, and the narration followed the clock.
        ctx.directive?.let { directive ->
            section("HOW THIS BEGINS — this is what is true, establish it") {
                line(directive)
                line(
                    "Set the scene to fit this. If it happens at a different hour than the clock " +
                        "below, advance time to reach that hour; if the weather does not fit, change " +
                        "it. This situation outranks the starting values.",
                )
            }
        }

        section("CURRENT TRUTH — the world") {
            line("Setting: ${ctx.game.settingName} — ${ctx.world.region}, ${ctx.world.era}")
            line(ctx.world.summary)
            line("Time: ${ctx.game.worldTime.display()} (${ctx.game.worldTime.partOfDay.name.lowercase().replace('_', ' ')}, ${ctx.game.worldTime.season.name.lowercase()})")
            line("Weather: ${ctx.world.weather}")
            line("Currency: ${ctx.world.currencyName}")
            if (ctx.world.currentConflicts.isNotEmpty()) line("Tensions: ${ctx.world.currentConflicts.joinToString("; ")}")
            if (ctx.world.specialRules.isNotEmpty()) line("World rules that override ordinary physics: ${ctx.world.specialRules.joinToString("; ")}")
        }

        section("CURRENT TRUTH — the protagonist") {
            line("${ctx.player.name}, age ${ctx.player.age}, ${ctx.player.gender}. id=player")
            line("Appearance: ${ctx.player.appearance.summary}")
            line("Personality: ${ctx.player.personality}")
            if (ctx.player.skills.isNotEmpty()) line("Good at: ${ctx.player.skills.joinToString(", ")}")
            if (ctx.player.weaknesses.isNotEmpty()) line("Bad at: ${ctx.player.weaknesses.joinToString(", ")}")
            line("Condition: ${ctx.player.body.describe()} (${ctx.player.body.health}/${ctx.player.body.maxHealth})")
            line("Carrying: ${ctx.player.currency} ${ctx.world.currencyName}" + if (ctx.inventory.isEmpty()) ", no notable items" else ", " + ctx.inventory.joinToString(", ") { "${it.name} [${it.id}]" })
            // Where a thing came from is often the whole point of it. Only for items that have a
            // history worth the line — most do not.
            ctx.inventory.filter { it.provenance.isNotEmpty() || it.unique }.take(6).forEach { item ->
                val trail = item.provenance.takeLast(2).joinToString("; ")
                line("  ${item.name} [${item.id}]: ${item.description}" + if (trail.isNotBlank()) " | $trail" else "")
            }
            if (ctx.player.goals.isNotEmpty()) line("Goals: ${ctx.player.goals.joinToString("; ")}")
        }

        section("CURRENT TRUTH — the scene") {
            line("Location: ${ctx.location.name} [${ctx.location.id}] — ${ctx.location.description}")
            if (ctx.location.condition != "intact") line("Condition: ${ctx.location.condition}")
            if (ctx.location.environmentState.isNotBlank()) line("Right now: ${ctx.location.environmentState}")
            if (ctx.location.exits.isNotEmpty()) line("Exits: ${ctx.location.exits.entries.joinToString(", ") { "${it.key} -> ${it.value}" }}")
            if (ctx.nearbyLocations.isNotEmpty()) {
                line("Nearby: " + ctx.nearbyLocations.joinToString(", ") { "${it.name} [${it.id}]" })
            }
            if (ctx.location.hazards.isNotEmpty()) line("Hazards: ${ctx.location.hazards.joinToString(", ")}")
            if (ctx.locationHistory.isNotEmpty()) {
                line("What has happened here before: " + ctx.locationHistory.joinToString("; "))
            }
        }

        if (ctx.presentNpcs.isNotEmpty()) {
            section("CURRENT TRUTH — who is here") {
                for (npc in ctx.presentNpcs) {
                    line(npcLine(npc, ctx))
                }
            }
        }

        val offScreen = ctx.relevantNpcs.filter { n -> ctx.presentNpcs.none { it.id == n.id } }
        if (offScreen.isNotEmpty()) {
            section("CURRENT TRUTH — people known to the player, elsewhere right now") {
                for (npc in offScreen) line(npcLine(npc, ctx, brief = true))
            }
        }

        if (ctx.npcKnowledge.isNotEmpty()) {
            section("BELIEF — what each character knows, and nothing more") {
                for ((npcId, facts) in ctx.npcKnowledge) {
                    if (facts.isEmpty()) continue
                    val name = ctx.relevantNpcs.firstOrNull { it.id == npcId }?.name
                        ?: ctx.presentNpcs.firstOrNull { it.id == npcId }?.name ?: npcId
                    line("$name [$npcId]:")
                    facts.forEach { f ->
                        val distorted = if (f.isDistorted) ", DISTORTED" else ""
                        val src = f.sourceEntityId?.let { ", from $it" } ?: ""
                        line("  - [${f.certainty}$distorted$src] ${f.statement}")
                    }
                }
            }
        }

        if (ctx.playerKnowledge.isNotEmpty()) {
            section("BELIEF — what the player has learned") {
                ctx.playerKnowledge.forEach { line("- [${it.certainty}] ${it.statement}") }
            }
        }

        if (ctx.retrieved.memories.isNotEmpty()) {
            section("MEMORY — what is recalled right now, not everything that happened") {
                ctx.retrieved.memories.forEach { m ->
                    val age = ctx.game.worldTime.describeGapSince(
                        com.unbound.core.model.WorldTime(m.memory.createdAtWorldMinutes),
                    )
                    line("- ($age) ${m.memory.text}")
                }
            }
        }

        if (ctx.retrieved.summaries.isNotEmpty()) {
            section("HISTORY — earlier chapters") {
                ctx.retrieved.summaries.forEach { line("- ${it.text}") }
            }
        }

        if (ctx.retrieved.olderImportantEvents.isNotEmpty()) {
            section("HISTORY — important earlier events") {
                ctx.retrieved.olderImportantEvents.forEach { line("- ${describeEvent(it, ctx)}") }
            }
        }

        if (ctx.retrieved.sharedHistory.isNotEmpty()) {
            section("HISTORY — what the player and these people have been through together, oldest first") {
                ctx.retrieved.sharedHistory.forEach { line("- ${describeEvent(it, ctx)}") }
            }
        }

        if (ctx.retrieved.recentEvents.isNotEmpty()) {
            section("HISTORY — recent events") {
                ctx.retrieved.recentEvents.forEach { line("- ${describeEvent(it, ctx)}") }
            }
        }

        if (ctx.worldNotes.isNotEmpty()) {
            section("HISTORY — what moved while the player was busy") {
                ctx.worldNotes.forEach { line("- $it") }
            }
        }

        if (ctx.commitments.isNotEmpty()) {
            section("CURRENT TRUTH — outstanding obligations, true whether or not anyone remembers") {
                val nameOf: (String) -> String = { id -> nameFor(id, ctx) }
                ctx.commitments.forEach { line("- " + it.describe(ctx.game.worldTime.totalMinutes, nameOf)) }
            }
        }

        if (ctx.threads.isNotEmpty()) {
            section("CURRENT TRUTH — open situations") {
                ctx.threads.forEach { t ->
                    val deadline = t.deadlineWorldMinutes?.let { d ->
                        val left = d - ctx.game.worldTime.totalMinutes
                        if (left <= 0) " (deadline passed)" else " (about ${left / 60} hours left)"
                    } ?: ""
                    line("- [${t.id}] ${t.title} — ${t.description} | ${t.status}, momentum ${t.momentum}$deadline")
                    if (t.stakes.isNotBlank()) line("    at stake: ${t.stakes}")
                }
            }
        }

        if (ctx.factions.isNotEmpty()) {
            section("CURRENT TRUTH — factions") {
                ctx.factions.forEach { f ->
                    val member = if (f.playerIsMember) ", player is a member" else ""
                    line("- ${f.name} [${f.id}]: ${f.purpose} | standing toward player ${f.playerStanding}$member")
                }
            }
        }

        if (ctx.rumors.isNotEmpty()) {
            section("BELIEF — rumours in circulation, which may be false") {
                ctx.rumors.forEach { line("- ${it.statement} (distortion ${it.distortion}%)") }
            }
        }

        if (ctx.recentTurns.isNotEmpty()) {
            section("THE SCENE SO FAR — the last few turns as they were actually played") {
                line(
                    "This is the conversation in progress. Continue it; do not restate it. Where it " +
                        "disagrees with CURRENT TRUTH above, CURRENT TRUTH wins — prose is not the record.",
                )
                ctx.recentTurns.forEach { turn ->
                    if (turn.playerInput.isNotBlank()) line("[turn ${turn.turnNumber}] PLAYER: ${turn.playerInput}")
                    if (turn.narrative.isNotBlank()) line("[turn ${turn.turnNumber}] STORY: ${excerpt(turn.narrative)}")
                }
            }
        }

        section("DIRECTION") {
            line("Tone: ${ctx.game.tone.display} — ${ctx.game.tone.guidance}")
            line("Target length: ${ctx.game.narrationLength.minWords}-${ctx.game.narrationLength.maxWords} words.")
            if (ctx.game.limits.isNotEmpty()) {
                line("Player's hard limits, to be respected exactly: ${ctx.game.limits.joinToString("; ")}")
            }
            if (!ctx.game.suggestedActionsEnabled) line("Do not return suggested_actions.")
            ctx.repetitionWarning?.let { line(it) }
        }
    }.trim()

    /**
     * Keeps a long narration from crowding out the structured state.
     *
     * The head and the tail are what carry a scene forward — how it opened and where it left the
     * player. The middle is description, which the state sections already cover.
     */
    private fun excerpt(text: String): String {
        val clean = text.replace('\n', ' ').trim()
        if (clean.length <= TRANSCRIPT_CHARS) return clean
        val head = clean.take(TRANSCRIPT_CHARS * 2 / 3).substringBeforeLast(' ')
        val tail = clean.takeLast(TRANSCRIPT_CHARS / 3).substringAfter(' ')
        return "$head […] $tail"
    }

    private fun nameFor(id: String, ctx: TurnContext): String = when (id) {
        com.unbound.core.model.Ids.PLAYER -> ctx.player.name
        else -> ctx.relevantNpcs.firstOrNull { it.id == id }?.name
            ?: ctx.presentNpcs.firstOrNull { it.id == id }?.name
            ?: ctx.factions.firstOrNull { it.id == id }?.name
            ?: id
    }

    private fun npcLine(npc: NpcRecord, ctx: TurnContext, brief: Boolean = false): String = buildString {
        append("- ${npc.name} [${npc.id}], age ${npc.age}")
        if (npc.occupation.isNotBlank()) append(", ${npc.occupation}")
        if (!npc.alive) { append(" — DEAD"); return@buildString }
        val rel = ctx.relationships[npc.id]
        if (rel != null) append(" | toward player: ${rel.vector.describe()}")
        if (!brief) {
            if (npc.personality.isNotBlank()) append(" | ${npc.personality}")
            if (npc.emotion.mood != "neutral") append(" | currently ${npc.emotion.mood}")
            if (npc.currentPlan.isNotBlank()) append(" | doing: ${npc.currentPlan}")
            append(" | looks: ${npc.appearance.summary}")

            // Interiority. All of this was already stored on every character and none of it was
            // ever sent, so the model knew what a character looked like and nothing about what
            // drove them — and characters behaved differently every time they appeared.
            if (npc.goals.isNotEmpty()) append("\n    wants: ${npc.goals.joinToString("; ")}")
            if (npc.desires.isNotEmpty()) append("\n    desires: ${npc.desires.joinToString("; ")}")
            if (npc.fears.isNotEmpty()) append("\n    afraid of: ${npc.fears.joinToString("; ")}")
            if (npc.values.isNotEmpty()) append("\n    will not compromise on: ${npc.values.joinToString("; ")}")
            // Their own secrets, marked as theirs. The player has not been told these; they shape
            // what the character avoids, deflects and lies about.
            if (npc.secrets.isNotEmpty()) {
                append("\n    keeping secret (the player does not know these): ${npc.secrets.joinToString("; ")}")
            }

            // How long they have known each other, so "we met last winter" is available rather
            // than guessed at.
            npc.firstEncounteredTurn?.let { append(" | first met on turn $it") }
            npc.lastSeenWorldMinutes?.let { minutes ->
                append(", last seen ${ctx.game.worldTime.describeGapSince(com.unbound.core.model.WorldTime(minutes))}")
            }

            // The reasons behind the feelings. Without these the model is told an NPC is
            // resentful and has to invent why, which is how fabricated history gets in.
            val reasons = rel?.history?.takeLast(MAX_RELATIONSHIP_REASONS)?.map { it.reason }?.filter { it.isNotBlank() }
            if (!reasons.isNullOrEmpty()) {
                append("\n    because: ")
                append(reasons.joinToString("; "))
            }
        } else {
            append(" | at ${npc.currentLocationId}")
        }
    }

    /**
     * Both an absolute stamp and a relative one. "Three days ago" cannot answer "what did we do on
     * the twelfth?", and a bare date cannot convey how long ago that feels — a turn's worth of
     * context needs both, and together they cost about twenty characters.
     */
    private fun describeEvent(e: GameEvent, ctx: TurnContext): String {
        val at = com.unbound.core.model.WorldTime(e.worldMinutes)
        val age = ctx.game.worldTime.describeGapSince(at)
        val base = "(d%d %02d:%02d, %s) %s".format(at.absoluteDay + 1, at.hour, at.minute, age, e.summary)
        // The immediate "because". Without it the model is told the world changed and has to
        // invent a reason, which is how fabricated history gets in.
        return ctx.causes[e.id]?.let { "$base — because: ${it.summary}" } ?: base
    }

    private fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        val start = length
        append("## ").append(title).append('\n')
        val bodyStart = length
        body()
        if (length == bodyStart) setLength(start) else append('\n')
    }

    private fun StringBuilder.line(text: String) {
        if (text.isNotBlank()) append(text).append('\n')
    }
}
