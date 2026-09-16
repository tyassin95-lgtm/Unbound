package com.unbound.core.journal

import com.unbound.core.engine.WorldStore
import com.unbound.core.knowledge.Certainty
import com.unbound.core.model.Ids
import com.unbound.core.model.ThreadStatus
import com.unbound.core.model.ThreadVisibility
import com.unbound.core.relationship.RelationshipRecord

/**
 * The journal is *derived*, never authored (§51). The model is never asked what should be in it,
 * because the database already knows and asking would both cost money and risk inventing facts.
 *
 * It also respects the knowledge system: it shows what the player knows, at the certainty the
 * player holds it, and never reveals an NPC's hidden knowledge or a HIDDEN thread.
 */
class JournalBuilder(private val store: WorldStore) {

    suspend fun build(gameId: String): Journal {
        val game = store.getGame(gameId) ?: error("No such game")
        val player = store.getPlayer(gameId) ?: error("No player")
        val world = store.getWorld(gameId) ?: error("No world")
        val location = store.getLocation(gameId, player.currentLocationId)

        val factions = store.factions(gameId)
        val relationships = store.relationshipsFrom(gameId, Ids.PLAYER, 200)
        // Anyone actually encountered, not only anyone the relationship engine happened to score.
        // Meeting someone and never speaking to them left familiarity at zero and kept them out of
        // the journal entirely, which is not what the player experienced.
        val encountered = store.persistentNpcs(gameId, 300).filter { it.firstEncounteredTurn != null || it.introduced }
        val knownNpcIds = (relationships.filter { it.vector.familiarity > 0 }.map { it.toEntityId } +
            encountered.map { it.id }).distinct()
        val npcs = store.npcsByIds(gameId, knownNpcIds)
        val relByNpc = relationships.associateBy { it.toEntityId }

        val threads = store.activeThreads(gameId, 50).filter { it.visibility != ThreadVisibility.HIDDEN }
        val playerKnowledge = store.knowledgeOf(gameId, Ids.PLAYER, 100)

        return Journal(
            currentSituation = buildString {
                append(player.name).append(" is at ").append(location?.name ?: "somewhere unmapped")
                append(", ").append(game.worldTime.display())
                append(". Weather: ").append(world.weather).append(". ")
                append("Condition: ").append(player.body.describe()).append(". ")
                append("Carrying ").append(player.currency).append(' ').append(world.currencyName).append('.')
            },
            character = CharacterPage(
                name = player.name,
                age = player.age,
                gender = player.gender,
                appearance = player.appearance.summary,
                personality = player.personality,
                strengths = player.skills,
                weaknesses = player.weaknesses,
                goals = player.goals,
                condition = player.body.describe(),
                currency = "${player.currency} ${world.currencyName}",
                // Derived from where the player actually stands, rather than a field nothing
                // ever wrote — the character page used to render an empty map indefinitely.
                reputation = (player.reputation + factions.filter { it.discovered }
                    .associate { it.name to it.playerStanding })
                    .filterValues { it != 0 },
            ),
            inventory = store.itemsOwnedBy(gameId, Ids.PLAYER).map {
                InventoryEntry(it.id, it.name, it.description, it.condition.name.lowercase(), it.quantity)
            },
            people = npcs.map { npc ->
                val rel = relByNpc[npc.id]
                PersonEntry(
                    id = npc.id,
                    name = npc.name,
                    occupation = npc.occupation,
                    appearance = npc.appearance.summary,
                    relationship = rel?.vector?.describe() ?: "unknown",
                    relationshipScore = rel?.vector?.overall ?: 0,
                    lastSeen = npc.lastSeenWorldMinutes?.let {
                        game.worldTime.describeGapSince(com.unbound.core.model.WorldTime(it))
                    } ?: "not since you met",
                    // How you know them at all — the question players ask most about an old face.
                    firstMet = npc.firstEncounteredTurn?.let { "met on turn $it" }.orEmpty(),
                    alive = npc.alive,
                    // Only what the *player* has learned about them, at the player's certainty.
                    knownFacts = playerKnowledge.filter { npc.id in it.subjectEntityIds }
                        .map { "${it.statement} (${it.certainty.name.lowercase()})" },
                    notes = rel?.history?.takeLast(3)?.map { it.reason } ?: emptyList(),
                    canonicalImageId = npc.canonicalImageId,
                )
            }.sortedByDescending { it.relationshipScore },
            places = store.discoveredLocations(gameId, 100).map {
                PlaceEntry(it.id, it.name, it.description, it.condition, it.id == player.currentLocationId, it.canonicalImageId)
            },
            factions = factions.filter { it.discovered }.map {
                FactionEntry(it.id, it.name, it.purpose, it.playerStanding, it.playerIsMember, it.publicReputation)
            },
            threads = threads.map {
                ThreadEntry(
                    id = it.id,
                    title = it.title,
                    description = it.description,
                    stakes = it.stakes,
                    status = it.status.name.lowercase(),
                    momentum = it.momentum,
                    uncertain = it.visibility == ThreadVisibility.SUSPECTED,
                    deadlineNote = it.deadlineWorldMinutes?.let { d ->
                        val left = d - game.worldTime.totalMinutes
                        if (left <= 0) "the moment has passed" else "about ${left / 60} hours"
                    },
                )
            },
            secrets = playerKnowledge.filter { it.certainty == Certainty.KNOWN && it.secret }
                .map { it.statement },
            rumors = playerKnowledge.filter { it.certainty == Certainty.RUMORED || it.certainty == Certainty.SUSPECTED }
                .map { "${it.statement} (${it.certainty.name.lowercase()})" },
            importantEvents = store.importantEventsBefore(
                gameId, Long.MAX_VALUE, com.unbound.core.model.Importance.HIGH, 40,
            ).map {
                TimelineEntry(
                    it.id,
                    com.unbound.core.model.WorldTime(it.worldMinutes).display(),
                    it.summary,
                    it.importance.name.lowercase(),
                )
            },
        )
    }

    /** The compact `status` reply (§105). Deliberately not the whole database. */
    suspend fun status(gameId: String): String {
        val game = store.getGame(gameId) ?: return "No game loaded."
        val player = store.getPlayer(gameId) ?: return "No character."
        val world = store.getWorld(gameId)!!
        val location = store.getLocation(gameId, player.currentLocationId)
        val threads = store.activeThreads(gameId, 3).filter { it.visibility != ThreadVisibility.HIDDEN }
        val recentMajor = store.importantEventsBefore(gameId, Long.MAX_VALUE, com.unbound.core.model.Importance.HIGH, 3)
        val relationships = store.relationshipsFrom(gameId, Ids.PLAYER, 100)
            .filter { it.history.isNotEmpty() }
            .sortedByDescending { it.lastInteractionWorldMinutes }
            .take(3)

        return buildString {
            appendLine("${player.name} — ${game.worldTime.display()}")
            appendLine("${location?.name ?: "Unknown"} · ${world.weather}")
            appendLine("${player.body.describe()} · ${player.currency} ${world.currencyName}")
            if (player.goals.isNotEmpty()) appendLine("Working on: ${player.goals.first()}")
            if (threads.isNotEmpty()) {
                appendLine()
                appendLine("Open:")
                threads.forEach { appendLine("  · ${it.title}") }
            }
            if (relationships.isNotEmpty()) {
                appendLine()
                appendLine("Lately:")
                val names = store.npcsByIds(gameId, relationships.map { it.toEntityId }).associate { it.id to it.name }
                relationships.forEach { r ->
                    val who = names[r.toEntityId] ?: r.toEntityId
                    appendLine("  · $who — ${r.history.last().reason}".take(92))
                }
            }
            if (recentMajor.isNotEmpty()) {
                appendLine()
                appendLine("Recent:")
                recentMajor.forEach { appendLine("  · ${it.summary}") }
            }
        }.trim()
    }
}

data class Journal(
    val currentSituation: String,
    val character: CharacterPage,
    val inventory: List<InventoryEntry>,
    val people: List<PersonEntry>,
    val places: List<PlaceEntry>,
    val factions: List<FactionEntry>,
    val threads: List<ThreadEntry>,
    val secrets: List<String>,
    val rumors: List<String>,
    val importantEvents: List<TimelineEntry>,
)

data class CharacterPage(
    val name: String, val age: Int, val gender: String, val appearance: String,
    val personality: String, val strengths: List<String>, val weaknesses: List<String>,
    val goals: List<String>, val condition: String, val currency: String,
    val reputation: Map<String, Int>,
)

data class InventoryEntry(val id: String, val name: String, val description: String, val condition: String, val quantity: Int)
data class PersonEntry(
    val id: String, val name: String, val occupation: String, val appearance: String,
    val relationship: String, val relationshipScore: Int, val lastSeen: String,
    val firstMet: String, val alive: Boolean,
    val knownFacts: List<String>, val notes: List<String>, val canonicalImageId: String?,
)
data class PlaceEntry(val id: String, val name: String, val description: String, val condition: String, val here: Boolean, val canonicalImageId: String?)
data class FactionEntry(val id: String, val name: String, val purpose: String, val standing: Int, val member: Boolean, val reputation: String)
data class ThreadEntry(
    val id: String, val title: String, val description: String, val stakes: String,
    val status: String, val momentum: Int, val uncertain: Boolean, val deadlineNote: String?,
)
data class TimelineEntry(val eventId: String, val whenText: String, val summary: String, val importance: String)
