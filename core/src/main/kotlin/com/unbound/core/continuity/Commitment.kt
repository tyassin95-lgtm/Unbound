package com.unbound.core.continuity

import com.unbound.core.model.Importance
import kotlinx.serialization.Serializable

/**
 * A promise, a debt, a deal or a threat — an obligation between two parties that outlives the scene
 * it was made in.
 *
 * Before this existed the world could only remember that a relationship carried some numeric
 * `obligation`, and that an event of type `PLAYER_MADE_PROMISE` had once occurred. Neither of those
 * can answer the question a player actually asks three hundred turns later: *what did I promise,
 * to whom, and is it still outstanding?* A number cannot be broken, and an event summary is not
 * state — nothing consults it when deciding how a character behaves.
 *
 * Commitments are canonical state, not memory. They are true whether or not anyone currently
 * remembers them, which is exactly what makes a creditor able to turn up unannounced.
 */
@Serializable
data class CommitmentRecord(
    val id: String,
    val gameId: String,
    val kind: CommitmentKind,
    /** Who owes. `player` when it is the protagonist's obligation. */
    val fromEntityId: String,
    /** Who is owed. */
    val toEntityId: String,
    /** What was actually undertaken, in the words of the world rather than a code. */
    val terms: String,
    val status: CommitmentStatus = CommitmentStatus.OUTSTANDING,
    val importance: Importance = Importance.MEDIUM,
    /** Amount, when the commitment is a debt. Zero for everything else. */
    val amount: Long = 0,
    val createdWorldMinutes: Long,
    /** When it falls due, if it does. A commitment past its due time is overdue, not void. */
    val dueWorldMinutes: Long? = null,
    val resolvedWorldMinutes: Long? = null,
    /** The event in which it was made, so the history behind it can be reconstructed. */
    val originEventId: String? = null,
    /** The event that settled or broke it. */
    val resolutionEventId: String? = null,
    val relatedThreadId: String? = null,
) {
    fun isOpen(): Boolean = status == CommitmentStatus.OUTSTANDING

    fun isOverdue(nowWorldMinutes: Long): Boolean =
        isOpen() && dueWorldMinutes != null && nowWorldMinutes > dueWorldMinutes

    /** How this reads in a dossier: "owes Mara 40 crowns, overdue by two days". */
    fun describe(nowWorldMinutes: Long, nameOf: (String) -> String): String {
        val subject = if (fromEntityId == com.unbound.core.model.Ids.PLAYER) "You" else nameOf(fromEntityId)
        val other = if (toEntityId == com.unbound.core.model.Ids.PLAYER) "you" else nameOf(toEntityId)
        val verb = when (kind) {
            CommitmentKind.PROMISE -> "promised $other"
            CommitmentKind.DEBT -> if (subject == "You") "owe $other" else "owes $other"
            CommitmentKind.DEAL -> "has a deal with $other"
            CommitmentKind.THREAT -> "threatened $other"
            CommitmentKind.OATH -> "swore to $other"
        }
        val state = when {
            status != CommitmentStatus.OUTSTANDING -> status.name.lowercase()
            isOverdue(nowWorldMinutes) -> "OVERDUE"
            dueWorldMinutes != null -> "due in ${((dueWorldMinutes - nowWorldMinutes) / 60).coerceAtLeast(0)}h"
            else -> "outstanding"
        }
        val sum = if (kind == CommitmentKind.DEBT && amount > 0) " $amount" else ""
        return "$subject $verb$sum: $terms [$state]"
    }
}

enum class CommitmentKind { PROMISE, DEBT, DEAL, THREAT, OATH }

enum class CommitmentStatus {
    OUTSTANDING,
    KEPT,
    BROKEN,
    /** Released by the party who was owed, rather than fulfilled. */
    FORGIVEN,
    /** Overtaken by events — the creditor died, the thing promised no longer exists. */
    VOID,
}
