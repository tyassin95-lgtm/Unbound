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
import com.unbound.core.simulation.WorldNote

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
    val worldNotes: List<WorldNote>,
    val nearbyLocations: List<LocationRecord>,
    val repetitionWarning: String? = null,
)

/**
 * Renders the dynamic half of the prompt.
 *
 * Output is deliberately terse, labelled and near-tabular rather than prose: every token spent
 * restating the world is a token not spent on the story, and this block is sent on *every* turn.
 * The section order is fixed so that diffs between turns are small and legible when debugging.
 */
class ContextBuilder {

    fun build(ctx: TurnContext): String = buildString {
        section("WORLD") {
            line("Setting: ${ctx.game.settingName} — ${ctx.world.region}, ${ctx.world.era}")
            line(ctx.world.summary)
            line("Time: ${ctx.game.worldTime.display()} (${ctx.game.worldTime.partOfDay.name.lowercase().replace('_', ' ')}, ${ctx.game.worldTime.season.name.lowercase()})")
            line("Weather: ${ctx.world.weather}")
            line("Currency: ${ctx.world.currencyName}")
            if (ctx.world.currentConflicts.isNotEmpty()) line("Tensions: ${ctx.world.currentConflicts.joinToString("; ")}")
            if (ctx.world.specialRules.isNotEmpty()) line("World rules that override ordinary physics: ${ctx.world.specialRules.joinToString("; ")}")
        }

        section("PLAYER") {
            line("${ctx.player.name}, age ${ctx.player.age}, ${ctx.player.gender}. id=player")
            line("Appearance: ${ctx.player.appearance.summary}")
            line("Personality: ${ctx.player.personality}")
            if (ctx.player.skills.isNotEmpty()) line("Good at: ${ctx.player.skills.joinToString(", ")}")
            if (ctx.player.weaknesses.isNotEmpty()) line("Bad at: ${ctx.player.weaknesses.joinToString(", ")}")
            line("Condition: ${ctx.player.body.describe()} (${ctx.player.body.health}/${ctx.player.body.maxHealth})")
            line("Carrying: ${ctx.player.currency} ${ctx.world.currencyName}" + if (ctx.inventory.isEmpty()) ", no notable items" else ", " + ctx.inventory.joinToString(", ") { "${it.name} [${it.id}]" })
            if (ctx.player.goals.isNotEmpty()) line("Goals: ${ctx.player.goals.joinToString("; ")}")
        }

        section("SCENE") {
            line("Location: ${ctx.location.name} [${ctx.location.id}] — ${ctx.location.description}")
            if (ctx.location.condition != "intact") line("Condition: ${ctx.location.condition}")
            if (ctx.location.environmentState.isNotBlank()) line("Right now: ${ctx.location.environmentState}")
            if (ctx.location.exits.isNotEmpty()) line("Exits: ${ctx.location.exits.entries.joinToString(", ") { "${it.key} -> ${it.value}" }}")
            if (ctx.nearbyLocations.isNotEmpty()) {
                line("Nearby: " + ctx.nearbyLocations.joinToString(", ") { "${it.name} [${it.id}]" })
            }
            if (ctx.location.hazards.isNotEmpty()) line("Hazards: ${ctx.location.hazards.joinToString(", ")}")
        }

        if (ctx.presentNpcs.isNotEmpty()) {
            section("PRESENT") {
                for (npc in ctx.presentNpcs) {
                    line(npcLine(npc, ctx))
                }
            }
        }

        val offScreen = ctx.relevantNpcs.filter { n -> ctx.presentNpcs.none { it.id == n.id } }
        if (offScreen.isNotEmpty()) {
            section("KNOWN PEOPLE ELSEWHERE") {
                for (npc in offScreen) line(npcLine(npc, ctx, brief = true))
            }
        }

        if (ctx.npcKnowledge.isNotEmpty()) {
            section("KNOWLEDGE — what each character knows, and nothing more") {
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
            section("WHAT THE PLAYER KNOWS") {
                ctx.playerKnowledge.forEach { line("- [${it.certainty}] ${it.statement}") }
            }
        }

        if (ctx.retrieved.memories.isNotEmpty()) {
            section("MEMORY — relevant to this moment only") {
                ctx.retrieved.memories.forEach { m ->
                    val age = ctx.game.worldTime.describeGapSince(
                        com.unbound.core.model.WorldTime(m.memory.createdAtWorldMinutes),
                    )
                    line("- ($age) ${m.memory.text}")
                }
            }
        }

        if (ctx.retrieved.summaries.isNotEmpty()) {
            section("EARLIER CHAPTERS") {
                ctx.retrieved.summaries.forEach { line("- ${it.text}") }
            }
        }

        if (ctx.retrieved.olderImportantEvents.isNotEmpty()) {
            section("IMPORTANT EARLIER EVENTS") {
                ctx.retrieved.olderImportantEvents.forEach { line("- ${describeEvent(it, ctx)}") }
            }
        }

        if (ctx.retrieved.recentEvents.isNotEmpty()) {
            section("RECENT EVENTS") {
                ctx.retrieved.recentEvents.forEach { line("- ${describeEvent(it, ctx)}") }
            }
        }

        if (ctx.worldNotes.isNotEmpty()) {
            section("WHAT MOVED WHILE THE PLAYER WAS BUSY") {
                ctx.worldNotes.forEach { line("- ${it.summary}") }
            }
        }

        if (ctx.threads.isNotEmpty()) {
            section("OPEN SITUATIONS") {
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
            section("FACTIONS") {
                ctx.factions.forEach { f ->
                    val member = if (f.playerIsMember) ", player is a member" else ""
                    line("- ${f.name} [${f.id}]: ${f.purpose} | standing toward player ${f.playerStanding}$member")
                }
            }
        }

        if (ctx.rumors.isNotEmpty()) {
            section("IN CIRCULATION") {
                ctx.rumors.forEach { line("- ${it.statement} (distortion ${it.distortion}%)") }
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

    private fun npcLine(npc: NpcRecord, ctx: TurnContext, brief: Boolean = false): String = buildString {
        append("- ${npc.name} [${npc.id}], age ${npc.age}")
        if (npc.occupation.isNotBlank()) append(", ${npc.occupation}")
        if (!npc.alive) { append(" — DEAD"); return@buildString }
        val rel = ctx.relationships[npc.id]
        if (rel != null) append(" | toward player: ${rel.vector.describe()}")
        if (!brief) {
            if (npc.personality.isNotBlank()) append(" | ${npc.personality}")
            if (npc.emotion.mood != "neutral") append(" | currently ${npc.emotion.mood}")
            if (npc.currentPlan.isNotBlank()) append(" | wants: ${npc.currentPlan}")
            append(" | looks: ${npc.appearance.summary}")
        } else {
            append(" | at ${npc.currentLocationId}")
        }
    }

    private fun describeEvent(e: GameEvent, ctx: TurnContext): String {
        val age = ctx.game.worldTime.describeGapSince(com.unbound.core.model.WorldTime(e.worldMinutes))
        return "($age) ${e.summary}"
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
