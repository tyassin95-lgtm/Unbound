package com.unbound.rpg.ui

/**
 * What the app is doing during the several-second waits in world creation, so the player is never
 * looking at an unresponsive button and wondering whether their tap registered.
 *
 * Every stage here corresponds to a real unit of work, most of them network calls. The stage is
 * surfaced rather than a bare spinner because "Forging the world" taking eight seconds reads as
 * progress, whereas an anonymous spinner taking eight seconds reads as a hang.
 */
enum class CreationStage(val step: Int, val headline: String, val detail: String) {
    FORGING_WORLD(1, "Forging the world", "Laying out streets, filling them with people who already have their own business."),
    FINDING_OPENINGS(2, "Looking for ways in", "Working out how your story could begin here."),
    BUILDING_WORLD(3, "Writing it into being", "Recording every place, person and quarrel."),
    OPENING_SCENE(4, "Beginning", "The first light on the first morning.");

    companion object {
        const val TOTAL = 4
    }
}

sealed interface CreationState {
    data object Idle : CreationState

    data class Working(val stage: CreationStage) : CreationState

    /** Creation stopped and nothing was kept. The player can change something and try again. */
    data class Failed(val message: String, val stage: CreationStage) : CreationState

    val busy: Boolean get() = this is Working
}
