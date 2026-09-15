package com.unbound.core.validate

/**
 * Who is in the scene this turn.
 *
 * This exists because a character's stored location is a poor answer to that question. It records
 * where they were *put*, which is not the same as where the story has them standing: a character
 * introduced as arriving from somewhere else keeps the location they arrived from, and from then on
 * the engine considers them absent from the very conversation they are having.
 *
 * That single stale column caused three separate failures — they were not offered for a picture of
 * the scene they were in, they did not witness events happening in front of them, and the validator
 * rejected knowledge they had obviously just acquired in person.
 *
 * So presence is resolved from what the narrative actually says, in this order of authority:
 *
 *  1. `present_character_ids`, where the narrator states outright who is in the room.
 *  2. Anyone the narrator gave an action to — you cannot act in a scene you are not in.
 *  3. Anyone named as actor or target of an event at the player's location.
 *  4. Anyone whose stored location already agrees.
 *
 * The result is then written back to the database, so the stored column stops drifting from the
 * story rather than being worked around forever.
 *
 * Callers pass the *validated* actions and events, never the raw response — an action the validator
 * refused must not be able to put a dead character in the room.
 */
object ScenePresence {

    /**
     * Everyone who took part in this scene, whether or not they are still here at the end of it.
     *
     * Used for witnessing (they were there when it happened) and for validation (they could
     * plausibly have learned something first-hand).
     */
    fun participants(
        declaredPresentIds: List<String>,
        npcActions: List<NpcActionDto>,
        events: List<EventDto>,
        playerLocationId: String,
        knownNpcIds: Set<String>,
        storedPresentIds: Set<String> = emptySet(),
    ): Set<String> = buildSet {
        addAll(storedPresentIds)
        addAll(declaredPresentIds.filter { it in knownNpcIds })
        addAll(npcActions.map { it.npcId }.filter { it in knownNpcIds })

        for (event in events) {
            // Only events in the room count. An event the narrator explicitly placed elsewhere is
            // being reported, not witnessed.
            val here = event.locationId == null || event.locationId == playerLocationId
            if (!here) continue
            listOfNotNull(event.actorId, event.targetId).forEach { if (it in knownNpcIds) add(it) }
        }
    }

    /**
     * Everyone still standing here when the turn ends — [participants] minus anyone the narrator
     * explicitly walked out of the scene. This is the set whose stored location is corrected.
     */
    fun remaining(
        declaredPresentIds: List<String>,
        npcActions: List<NpcActionDto>,
        events: List<EventDto>,
        playerLocationId: String,
        knownNpcIds: Set<String>,
        storedPresentIds: Set<String> = emptySet(),
    ): Set<String> {
        val leaving = npcActions
            .filter { it.movesToLocationId != null && it.movesToLocationId != playerLocationId }
            .map { it.npcId }
            .toSet()
        return participants(declaredPresentIds, npcActions, events, playerLocationId, knownNpcIds, storedPresentIds) - leaving
    }
}
