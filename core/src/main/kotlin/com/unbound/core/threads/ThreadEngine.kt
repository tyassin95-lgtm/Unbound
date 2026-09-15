package com.unbound.core.threads

import com.unbound.core.model.Importance
import com.unbound.core.model.ThreadRecord
import com.unbound.core.model.ThreadStatus
import com.unbound.core.model.ThreadType
import com.unbound.core.model.WorldTime
import kotlin.random.Random

/**
 * Threads are situations, not quests. This engine's whole job is the requirement in §117: a thread
 * the player ignores must not sit frozen. Every tick, untouched threads lose momentum, pass
 * deadlines, and eventually resolve themselves — usually worse than if the player had engaged.
 *
 * It uses no AI. Thread drift is exactly the kind of bookkeeping that deterministic code does
 * better and for free.
 */
class ThreadEngine(private val random: Random = Random(0)) {

    fun advance(thread: ThreadRecord, now: WorldTime, playerEngagedThisTurn: Boolean): ThreadOutcome {
        if (thread.status in TERMINAL) return ThreadOutcome(thread, null)

        if (playerEngagedThisTurn) {
            return ThreadOutcome(
                thread.copy(
                    status = ThreadStatus.ADVANCING,
                    momentum = (thread.momentum + 12).coerceAtMost(100),
                    lastActivityWorldMinutes = now.totalMinutes,
                ),
                null,
            )
        }

        val idleDays = (now.totalMinutes - thread.lastActivityWorldMinutes) / WorldTime.MINUTES_PER_DAY
        val deadline = thread.deadlineWorldMinutes

        // A passed deadline forces a resolution whose direction depends on how much momentum the
        // situation had when the player walked away.
        if (deadline != null && now.totalMinutes >= deadline) {
            val (status, note) = when {
                thread.momentum >= 70 -> ThreadStatus.TRANSFORMED to
                    "${thread.title}: the situation moved on without you and became something else."
                thread.momentum >= 35 -> ThreadStatus.RESOLVED to
                    "${thread.title}: resolved itself while you were elsewhere."
                else -> ThreadStatus.FAILED to
                    "${thread.title}: collapsed unresolved. The chance has gone."
            }
            return ThreadOutcome(
                thread.copy(status = status, lastActivityWorldMinutes = now.totalMinutes, momentum = 0),
                note,
            )
        }

        if (idleDays < 1) return ThreadOutcome(thread, null)

        val drift = when (thread.importance) {
            Importance.CRITICAL -> 2
            Importance.HIGH -> 4
            else -> 7
        }
        val newMomentum = (thread.momentum - (idleDays / drift).toInt() - random.nextInt(0, 3)).coerceIn(0, 100)
        val newStatus = if (newMomentum <= 15) ThreadStatus.STALLED else thread.status

        val note = if (newStatus == ThreadStatus.STALLED && thread.status != ThreadStatus.STALLED) {
            "${thread.title}: has gone quiet. Nobody is pushing it any more."
        } else {
            null
        }

        return ThreadOutcome(
            thread.copy(status = newStatus, momentum = newMomentum, lastActivityWorldMinutes = thread.lastActivityWorldMinutes),
            note,
        )
    }

    fun defaultDeadline(type: ThreadType, importance: Importance, now: WorldTime): Long? {
        val days = when (type) {
            ThreadType.DEBT -> 14
            ThreadType.CRIMINAL -> 10
            ThreadType.POLITICAL -> 30
            ThreadType.FACTION -> 21
            ThreadType.SURVIVAL -> 3
            ThreadType.ECONOMIC -> 20
            ThreadType.MYSTERY -> 45
            ThreadType.FEUD -> 25
            ThreadType.ROMANCE -> 60
            ThreadType.PERSONAL -> 30
            ThreadType.OTHER -> return null
        }
        val scaled = when (importance) {
            Importance.CRITICAL -> days / 2
            Importance.HIGH -> (days * 3) / 4
            else -> days
        }
        return now.totalMinutes + scaled * WorldTime.MINUTES_PER_DAY
    }

    companion object {
        val TERMINAL = setOf(ThreadStatus.RESOLVED, ThreadStatus.FAILED, ThreadStatus.ABANDONED)
    }
}

data class ThreadOutcome(val thread: ThreadRecord, val worldNote: String?)
