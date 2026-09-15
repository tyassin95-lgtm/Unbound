package com.unbound.core.model

import kotlinx.serialization.Serializable

enum class ThreadStatus { ACTIVE, STALLED, ADVANCING, RESOLVED, FAILED, TRANSFORMED, ABANDONED }

enum class ThreadType { PERSONAL, DEBT, FEUD, ROMANCE, POLITICAL, CRIMINAL, MYSTERY, SURVIVAL, ECONOMIC, FACTION, OTHER }

enum class ThreadVisibility {
    /** The player is aware of it. */
    KNOWN,
    /** The player has seen hints only. */
    SUSPECTED,
    /** Running entirely off-screen; drives world simulation but is not shown in the journal. */
    HIDDEN,
}

/**
 * Open-ended situations rather than quests. A thread has no step list and no completion condition —
 * it has stakes, momentum and a deadline, and the simulator moves it whether or not the player
 * engages (§36, §117).
 */
@Serializable
data class ThreadRecord(
    val id: String,
    val gameId: String,
    val type: ThreadType,
    val title: String,
    val description: String,
    val originatingEventId: String? = null,
    val involvedEntityIds: List<String> = emptyList(),
    val stakes: String = "",
    val status: ThreadStatus = ThreadStatus.ACTIVE,
    /** World-minute at which the situation resolves itself with or without the player. */
    val deadlineWorldMinutes: Long? = null,
    val lastActivityWorldMinutes: Long,
    val importance: Importance = Importance.MEDIUM,
    val visibility: ThreadVisibility = ThreadVisibility.KNOWN,
    val potentialConsequences: List<String> = emptyList(),
    /** 0..100. Raised by player engagement and by off-screen developments. */
    val momentum: Int = 50,
)
