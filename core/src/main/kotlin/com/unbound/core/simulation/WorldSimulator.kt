package com.unbound.core.simulation

import com.unbound.core.knowledge.KnowledgePropagator
import com.unbound.core.knowledge.KnowledgeRecord
import com.unbound.core.knowledge.RumorRecord
import com.unbound.core.ledger.EventType
import com.unbound.core.model.FactionRecord
import com.unbound.core.model.Importance
import com.unbound.core.model.NpcRecord
import com.unbound.core.model.NpcTier
import com.unbound.core.model.Season
import com.unbound.core.model.ThreadRecord
import com.unbound.core.model.WorldTime
import com.unbound.core.threads.ThreadEngine
import kotlin.random.Random

/**
 * The world moving on its own (§38, §39, §55, §91).
 *
 * Every rule here is deterministic local Kotlin and costs nothing. That is the point: a campaign
 * with fifty NPCs must not cost fifty model calls to advance an hour. The simulator is also
 * *bucketed* — it works in whole ticks of elapsed time rather than simulating minutes — so a
 * two-week time skip costs the same as a two-hour one.
 */
class WorldSimulator(
    private val idFactory: () -> String,
    private val threadEngine: ThreadEngine = ThreadEngine(),
    private val propagator: KnowledgePropagator = KnowledgePropagator(idFactory),
    private val random: Random = Random(0),
) {

    fun simulate(input: SimulationInput): SimulationOutput {
        val elapsed = input.now.totalMinutes - input.previous.totalMinutes
        if (elapsed <= 0) return SimulationOutput.empty(input.now)

        val npcUpdates = mutableListOf<NpcRecord>()
        val factionUpdates = mutableListOf<FactionRecord>()
        val threadUpdates = mutableListOf<ThreadRecord>()
        val notes = mutableListOf<WorldNote>()
        val rumorUpdates = mutableListOf<RumorRecord>()
        val knowledge = mutableListOf<KnowledgeRecord>()

        // --- Weather -----------------------------------------------------------------------
        var weather = input.weather
        val weatherTicks = elapsed / WEATHER_TICK_MINUTES
        if (weatherTicks > 0 && random.nextDouble() < 0.45) {
            weather = nextWeather(input.weather, input.now.season)
            if (weather != input.weather) {
                notes += WorldNote(EventType.WEATHER_CHANGED, "The weather turns $weather.", Importance.TRIVIAL)
            }
        }

        // --- NPC movement by schedule -------------------------------------------------------
        // Only NPCs the player is not currently with are moved, and only persistent ones: ambient
        // crowd members have no schedule worth simulating.
        for (npc in input.npcs) {
            if (!npc.alive || npc.tier == NpcTier.AMBIENT) continue
            if (npc.currentLocationId == input.playerLocationId && elapsed < WorldTime.MINUTES_PER_DAY) continue

            val scheduled = npc.schedule.locationAt(input.now.hour)
            if (scheduled != null && scheduled != npc.currentLocationId) {
                npcUpdates += npc.copy(currentLocationId = scheduled)
                notes += WorldNote(
                    EventType.NPC_MOVED,
                    "${npc.name} moved to $scheduled.",
                    Importance.TRIVIAL,
                    entityId = npc.id,
                    locationId = scheduled,
                )
            }
        }

        // --- Rumor spread --------------------------------------------------------------------
        for (rumor in input.rumors) {
            val spread = propagator.spread(rumor, input.npcs, input.now.totalMinutes)
            if (spread.rumor != rumor) rumorUpdates += spread.rumor
            if (spread.newKnowledge.isNotEmpty()) {
                knowledge += spread.newKnowledge
                notes += WorldNote(
                    EventType.NPC_HEARD_RUMOR,
                    "Word spreads: ${rumor.statement}",
                    Importance.LOW,
                )
            }
        }

        // --- Threads ---------------------------------------------------------------------------
        for (thread in input.threads) {
            val outcome = threadEngine.advance(thread, input.now, playerEngagedThisTurn = thread.id in input.engagedThreadIds)
            if (outcome.thread != thread) threadUpdates += outcome.thread
            outcome.worldNote?.let {
                notes += WorldNote(
                    when (outcome.thread.status) {
                        com.unbound.core.model.ThreadStatus.RESOLVED -> EventType.THREAD_RESOLVED
                        com.unbound.core.model.ThreadStatus.FAILED -> EventType.THREAD_FAILED
                        com.unbound.core.model.ThreadStatus.TRANSFORMED -> EventType.THREAD_TRANSFORMED
                        else -> EventType.THREAD_STALLED
                    },
                    it,
                    outcome.thread.importance,
                    threadId = outcome.thread.id,
                )
            }
        }

        // --- Factions ---------------------------------------------------------------------------
        // Factions act on a slow clock, and only when they have an objective and enough strength to
        // pursue it. A faction with nothing to do stays quiet rather than manufacturing drama.
        val factionTicks = elapsed / FACTION_TICK_MINUTES
        if (factionTicks > 0) {
            for (faction in input.factions) {
                if (faction.currentObjectives.isEmpty()) continue
                if (random.nextDouble() > 0.25 * factionTicks.coerceAtMost(4)) continue
                val objective = faction.currentObjectives.random(random)
                val strengthShift = random.nextInt(-4, 7)
                factionUpdates += faction.copy(strength = (faction.strength + strengthShift).coerceIn(0, 100))
                notes += WorldNote(
                    EventType.FACTION_ACTED,
                    "${faction.name} pushed ahead with: $objective.",
                    Importance.MEDIUM,
                    entityId = faction.id,
                )
            }
        }

        return SimulationOutput(
            now = input.now,
            weather = weather,
            npcUpdates = npcUpdates,
            factionUpdates = factionUpdates,
            threadUpdates = threadUpdates,
            rumorUpdates = rumorUpdates,
            newKnowledge = knowledge,
            notes = notes,
        )
    }

    private fun nextWeather(current: String, season: Season): String {
        val table = when (season) {
            Season.SPRING -> listOf("clear", "overcast", "light rain", "showers", "windy", "mild and damp")
            Season.SUMMER -> listOf("clear", "hot and still", "hazy", "thunder building", "warm wind", "overcast")
            Season.AUTUMN -> listOf("overcast", "cold rain", "fog", "windy", "clear and sharp", "drizzle")
            Season.WINTER -> listOf("freezing fog", "sleet", "hard frost", "snow", "bitter wind", "cold and clear")
        }
        return table.filter { it != current }.random(random)
    }

    companion object {
        const val WEATHER_TICK_MINUTES = 180L
        const val FACTION_TICK_MINUTES = 60L * 24
    }
}

data class SimulationInput(
    val previous: WorldTime,
    val now: WorldTime,
    val playerLocationId: String,
    val weather: String,
    val npcs: List<NpcRecord>,
    val factions: List<FactionRecord>,
    val threads: List<ThreadRecord>,
    val rumors: List<RumorRecord>,
    val engagedThreadIds: Set<String> = emptySet(),
)

data class WorldNote(
    val type: EventType,
    val summary: String,
    val importance: Importance,
    val entityId: String? = null,
    val locationId: String? = null,
    val threadId: String? = null,
)

data class SimulationOutput(
    val now: WorldTime,
    val weather: String,
    val npcUpdates: List<NpcRecord>,
    val factionUpdates: List<FactionRecord>,
    val threadUpdates: List<ThreadRecord>,
    val rumorUpdates: List<RumorRecord>,
    val newKnowledge: List<KnowledgeRecord>,
    val notes: List<WorldNote>,
) {
    companion object {
        fun empty(now: WorldTime) = SimulationOutput(now, "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}
