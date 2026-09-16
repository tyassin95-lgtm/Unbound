package com.unbound.core.model

/**
 * Entity identifiers are plain strings so they survive JSON round-trips, Room columns and model
 * output without conversion, but they are always namespaced by prefix so that a malformed or
 * cross-kind reference is detectable by the validator rather than silently accepted.
 */
object Ids {
    const val PLAYER = "player"

    fun game(seed: String): String = "game_$seed"
    fun npc(seed: String): String = "npc_$seed"
    fun location(seed: String): String = "loc_$seed"
    fun faction(seed: String): String = "fac_$seed"
    fun item(seed: String): String = "item_$seed"
    fun thread(seed: String): String = "thr_$seed"
    fun event(seed: String): String = "evt_$seed"
    fun commitment(seed: String): String = "cmt_$seed"
    fun memory(seed: String): String = "mem_$seed"
    fun turn(seed: String): String = "turn_$seed"
    fun snapshot(seed: String): String = "snap_$seed"
    fun image(seed: String): String = "img_$seed"

    fun kindOf(id: String): EntityKind = when {
        id == PLAYER -> EntityKind.PLAYER
        id.startsWith("npc_") -> EntityKind.NPC
        id.startsWith("loc_") -> EntityKind.LOCATION
        id.startsWith("fac_") -> EntityKind.FACTION
        id.startsWith("item_") -> EntityKind.ITEM
        id.startsWith("thr_") -> EntityKind.THREAD
        else -> EntityKind.UNKNOWN
    }
}

enum class EntityKind { PLAYER, NPC, LOCATION, FACTION, ITEM, THREAD, UNKNOWN }
