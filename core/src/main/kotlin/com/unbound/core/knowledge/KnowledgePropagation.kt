package com.unbound.core.knowledge

import com.unbound.core.ledger.GameEvent
import com.unbound.core.ledger.KnowledgeScope
import com.unbound.core.model.Importance
import com.unbound.core.model.NpcRecord
import kotlin.random.Random

/**
 * Decides who learns what, and how badly they get it wrong.
 *
 * This is the only path by which an NPC acquires knowledge from an event. Nothing else writes
 * knowledge rows, which is what makes the guarantee in §24 structural: an NPC who was not a
 * witness, not in the room, and not reached by a rumor simply has no row to retrieve, so the
 * prompt cannot contain the fact, so the model cannot have them mention it.
 */
class KnowledgePropagator(
    private val idFactory: () -> String,
    private val random: Random = Random(0),
) {

    /**
     * @param presentNpcs NPCs physically at the event's location when it happened.
     */
    fun propagate(
        event: GameEvent,
        presentNpcs: List<NpcRecord>,
        factionMembers: Map<String, List<NpcRecord>> = emptyMap(),
        factKey: String? = null,
        statement: String? = null,
    ): PropagationResult {
        val key = factKey ?: "${event.type.name.lowercase()}:${event.id}"
        val text = statement ?: event.summary
        val learners = mutableListOf<KnowledgeRecord>()
        var rumor: RumorRecord? = null

        fun record(knowerId: String, certainty: Certainty, source: String?, distorted: Boolean = false, secret: Boolean = false) {
            learners += KnowledgeRecord(
                id = idFactory(),
                gameId = event.gameId,
                knowerId = knowerId,
                factKey = key,
                statement = text,
                certainty = certainty,
                isDistorted = distorted,
                subjectEntityIds = (listOfNotNull(event.actorId, event.targetId) + event.relatedEntityIds).distinct(),
                learnedFromEventId = event.id,
                sourceEntityId = source,
                learnedAtWorldMinutes = event.worldMinutes,
                importance = event.importance,
                secret = secret,
            )
        }

        when (event.knowledgeScope) {
            KnowledgeScope.PRIVATE -> {
                event.actorId?.let { record(it, Certainty.KNOWN, null, secret = true) }
            }

            KnowledgeScope.SECRET -> {
                event.actorId?.let { record(it, Certainty.KNOWN, null, secret = true) }
                event.witnessIds.forEach { record(it, Certainty.KNOWN, event.actorId, secret = true) }
            }

            KnowledgeScope.WITNESSED -> {
                event.actorId?.let { record(it, Certainty.KNOWN, null) }
                val witnesses = (presentNpcs.map { it.id } + event.witnessIds).distinct()
                    .filter { it != event.actorId }
                witnesses.forEach { record(it, Certainty.KNOWN, null) }
            }

            KnowledgeScope.LOCAL_RUMOR -> {
                event.actorId?.let { record(it, Certainty.KNOWN, null) }
                presentNpcs.forEach { record(it.id, Certainty.KNOWN, null) }
                rumor = RumorRecord(
                    id = idFactory(),
                    gameId = event.gameId,
                    factKey = key,
                    statement = text,
                    originEventId = event.id,
                    spreadLocationIds = listOfNotNull(event.locationId),
                    knownByEntityIds = presentNpcs.map { it.id },
                    createdAtWorldMinutes = event.worldMinutes,
                    lastSpreadWorldMinutes = event.worldMinutes,
                    virality = viralityFor(event.importance),
                )
            }

            KnowledgeScope.FACTION -> {
                event.actorId?.let { record(it, Certainty.KNOWN, null) }
                factionMembers.forEach { (factionId, members) ->
                    record(factionId, Certainty.BELIEVED, event.actorId)
                    members.forEach { record(it.id, Certainty.BELIEVED, factionId) }
                }
            }

            KnowledgeScope.PUBLIC -> {
                event.actorId?.let { record(it, Certainty.KNOWN, null) }
                presentNpcs.forEach { record(it.id, Certainty.KNOWN, null) }
                rumor = RumorRecord(
                    id = idFactory(),
                    gameId = event.gameId,
                    factKey = key,
                    statement = text,
                    originEventId = event.id,
                    spreadLocationIds = listOfNotNull(event.locationId),
                    knownByEntityIds = presentNpcs.map { it.id },
                    createdAtWorldMinutes = event.worldMinutes,
                    lastSpreadWorldMinutes = event.worldMinutes,
                    virality = 90,
                )
            }
        }

        return PropagationResult(learners, rumor)
    }

    /**
     * Advances a rumor by one simulation tick. Secondhand tellings arrive as [Certainty.RUMORED]
     * and may be distorted, which is how the "neighbour blames the dock gang" case in §24 arises
     * naturally instead of being scripted.
     */
    fun spread(
        rumor: RumorRecord,
        candidates: List<NpcRecord>,
        nowWorldMinutes: Long,
        maxNewKnowers: Int = 3,
    ): RumorSpread {
        if (rumor.virality <= 5) return RumorSpread(rumor.copy(virality = 0), emptyList())

        val elapsedDays = (nowWorldMinutes - rumor.lastSpreadWorldMinutes) / (60.0 * 24)
        if (elapsedDays < 0.25) return RumorSpread(rumor, emptyList())

        val known = rumor.knownByEntityIds.toSet()
        val reachable = candidates.filter { it.id !in known && it.alive && it.currentLocationId in rumor.spreadLocationIds }
        if (reachable.isEmpty()) {
            return RumorSpread(rumor.copy(virality = (rumor.virality - 10).coerceAtLeast(0), lastSpreadWorldMinutes = nowWorldMinutes), emptyList())
        }

        val count = ((rumor.virality / 30).coerceAtLeast(1)).coerceAtMost(minOf(maxNewKnowers, reachable.size))
        val newKnowers = reachable.shuffled(random).take(count)
        val distortion = (rumor.distortion + random.nextInt(0, 12)).coerceAtMost(100)

        val newKnowledge = newKnowers.map { npc ->
            KnowledgeRecord(
                id = idFactory(),
                gameId = rumor.gameId,
                knowerId = npc.id,
                factKey = rumor.factKey,
                statement = rumor.statement,
                certainty = Certainty.RUMORED,
                isDistorted = distortion > 40,
                learnedFromEventId = rumor.originEventId,
                sourceEntityId = null,
                learnedAtWorldMinutes = nowWorldMinutes,
                importance = Importance.LOW,
            )
        }

        return RumorSpread(
            rumor.copy(
                knownByEntityIds = (rumor.knownByEntityIds + newKnowers.map { it.id }).distinct(),
                distortion = distortion,
                virality = (rumor.virality - 8).coerceAtLeast(0),
                lastSpreadWorldMinutes = nowWorldMinutes,
            ),
            newKnowledge,
        )
    }

    private fun viralityFor(importance: Importance) = when (importance) {
        Importance.CRITICAL -> 95
        Importance.HIGH -> 75
        Importance.MEDIUM -> 50
        else -> 25
    }
}

data class PropagationResult(val knowledge: List<KnowledgeRecord>, val rumor: RumorRecord?)

data class RumorSpread(val rumor: RumorRecord, val newKnowledge: List<KnowledgeRecord>)
