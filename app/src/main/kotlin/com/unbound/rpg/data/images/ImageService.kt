package com.unbound.rpg.data.images

import android.content.Context
import com.unbound.core.ai.AIException
import com.unbound.core.ai.AIImageProvider
import com.unbound.core.ai.AIImageRequest
import com.unbound.core.engine.WorldStore
import com.unbound.core.images.CharacterLikeness
import com.unbound.core.images.ImagePrompt
import com.unbound.core.images.ImagePromptBuilder
import com.unbound.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generates, caches and version-tracks images (§57-§60, §87, §110, §123).
 *
 * Two rules carry most of the weight:
 *
 *  * The **first** image of a subject becomes its canonical reference. Every later image reuses
 *    that reference's bytes (through the provider's edit path) and the identical identity clause,
 *    so a character keeps their face.
 *  * A failed image **never** fails the turn. The narrative has already been committed by the time
 *    this runs, and image generation is a separate, independently retryable request (§98).
 */
class ImageService(
    context: Context,
    private val store: WorldStore,
    private val provider: AIImageProvider,
    private val clock: () -> Long,
    private val idFactory: () -> String,
    private val promptBuilder: ImagePromptBuilder = ImagePromptBuilder(),
) {
    private val cacheDir = File(context.filesDir, "images").apply { mkdirs() }

    suspend fun imageForNpc(gameId: String, npcId: String, modelId: String, situation: String? = null): ImageOutcome {
        val npc = store.getNpc(gameId, npcId) ?: return ImageOutcome.Failed("That person is not in this world.")
        val world = store.getWorld(gameId) ?: return ImageOutcome.Failed("World data missing.")
        val game = store.getGame(gameId)!!
        val existing = store.canonicalImageFor(gameId, npcId)
        val prompt = promptBuilder.forNpc(npc, world, game.settingName, situation, isFirstImage = existing == null)
        return generate(gameId, npcId, ImageKind.NPC, prompt, modelId, existing, npc.appearance.version)
    }

    suspend fun imageForPlayer(gameId: String, modelId: String, situation: String? = null): ImageOutcome {
        val player = store.getPlayer(gameId) ?: return ImageOutcome.Failed("No character.")
        val world = store.getWorld(gameId)!!
        val game = store.getGame(gameId)!!
        val existing = store.canonicalImageFor(gameId, player.id)
        val prompt = promptBuilder.forPlayer(player, world, game.settingName, situation, isFirstImage = existing == null)
        return generate(gameId, player.id, ImageKind.CHARACTER, prompt, modelId, existing, player.appearance.version)
    }

    suspend fun imageForLocation(gameId: String, locationId: String, modelId: String): ImageOutcome {
        val location = store.getLocation(gameId, locationId) ?: return ImageOutcome.Failed("Nowhere by that name.")
        val world = store.getWorld(gameId)!!
        val game = store.getGame(gameId)!!
        val existing = store.canonicalImageFor(gameId, locationId)
        val prompt = promptBuilder.forLocation(location, world, game.worldTime, game.settingName, isFirstImage = existing == null)
        return generate(gameId, locationId, ImageKind.LOCATION, prompt, modelId, existing, 1)
    }

    /**
     * The current moment: this place, this light, these people, doing this.
     *
     * A scene is never a canonical reference — it depicts an event, not an identity — so it is
     * always generated fresh rather than edited from a stored portrait, and identity is carried by
     * each character's own identity clause instead.
     */
    suspend fun imageForScene(
        gameId: String,
        modelId: String,
        moment: String,
        includePlayer: Boolean = true,
        npcIds: List<String> = emptyList(),
        closeUp: Boolean = false,
    ): ImageOutcome {
        val game = store.getGame(gameId) ?: return ImageOutcome.Failed("No such game.")
        val player = store.getPlayer(gameId) ?: return ImageOutcome.Failed("No character.")
        val world = store.getWorld(gameId) ?: return ImageOutcome.Failed("World data missing.")
        val location = store.getLocation(gameId, player.currentLocationId)
            ?: return ImageOutcome.Failed("Nowhere to draw.")

        val npcs = if (npcIds.isEmpty()) {
            store.npcsAt(gameId, location.id).filter { it.alive }
        } else {
            store.npcsByIds(gameId, npcIds).filter { it.alive }
        }

        val cast = buildList {
            if (includePlayer) add(CharacterLikeness.of(player))
            addAll(npcs.map { CharacterLikeness.of(it) })
        }

        val prompt = if (closeUp) {
            promptBuilder.forInteraction(location, world, game.worldTime, game.settingName, cast, moment)
        } else {
            promptBuilder.forScene(location, world, game.worldTime, game.settingName, cast, moment)
        }

        return generate(
            gameId = gameId,
            entityId = SCENE_ENTITY_PREFIX + prompt.cacheKey,
            kind = prompt.kind,
            prompt = prompt,
            modelId = modelId,
            canonical = null,
            appearanceVersion = prompt.appearanceVersion,
        )
    }

    private suspend fun generate(
        gameId: String,
        entityId: String,
        kind: ImageKind,
        prompt: ImagePrompt,
        modelId: String,
        canonical: ImageRecord?,
        appearanceVersion: Int,
    ): ImageOutcome = withContext(Dispatchers.IO) {
        val imageProviderId = store.getGame(gameId)?.let { it.imageProviderId ?: it.textProviderId }

        // Never pay twice for the same picture (§60).
        val cached = store.imagesFor(gameId, entityId).firstOrNull { record ->
            record.prompt == prompt.prompt &&
                record.appearanceVersion == appearanceVersion &&
                record.localPath?.let { File(it).exists() } == true
        }
        if (cached != null) return@withContext ImageOutcome.Ready(cached, fromCache = true)

        // The canonical reference is reusable only while the subject still looks the way it did.
        // A haircut bumps the appearance version and forces a fresh reference (§59).
        val referenceBytes = canonical
            ?.takeIf { it.appearanceVersion == appearanceVersion }
            ?.localPath
            ?.let { File(it) }
            ?.takeIf { it.exists() }
            ?.readBytes()

        val response = try {
            provider.generateImage(
                AIImageRequest(
                    modelId = modelId,
                    // Pictures follow their own provider, defaulting to whoever tells the story.
                    // A canonical reference image is a file on the device and belongs to the
                    // character, not to a vendor, so switching provider keeps a face.
                    providerId = imageProviderId,
                    prompt = prompt.prompt,
                    referenceImage = referenceBytes,
                ),
            )
        } catch (e: AIException) {
            store.recordUsage(
                UsageRecord(
                    id = idFactory(), gameId = gameId, turnId = null, timestampMs = clock(),
                    requestType = RequestType.IMAGE, modelId = modelId,
                    providerId = imageProviderId ?: "openai",
                    success = false, errorKind = e.kind.name,
                ),
            )
            return@withContext ImageOutcome.Failed(e.message)
        }

        val file = File(cacheDir, gameId + "_" + entityId + "_" + idFactory() + ".png")
        file.writeBytes(response.bytes)

        val record = ImageRecord(
            id = Ids.image(idFactory()),
            gameId = gameId,
            entityId = entityId,
            kind = kind,
            localPath = file.absolutePath,
            prompt = prompt.prompt,
            modelId = response.modelId,
            createdAtEpochMs = clock(),
            // The first image of a subject, or the first at a new appearance version, becomes the
            // reference everything later is matched against.
            canonical = canonical == null || canonical.appearanceVersion != appearanceVersion,
            appearanceVersion = appearanceVersion,
            visualTraits = prompt.visualTraits,
        )
        store.upsertImage(record)
        store.recordUsage(
            UsageRecord(
                id = idFactory(), gameId = gameId, turnId = null, timestampMs = clock(),
                requestType = RequestType.IMAGE, modelId = response.modelId,
                providerId = imageProviderId ?: "openai",
                latencyMs = response.latencyMs, success = true,
            ),
        )
        ImageOutcome.Ready(record, fromCache = false)
    }

    /** Settings -> Storage -> Clear cached images. Keeps the records so the game knows what to re-fetch. */
    suspend fun clearCache(gameId: String) {
        store.allImages(gameId).forEach { it.localPath?.let { path -> File(path).delete() } }
        store.deleteImageFiles(gameId)
    }

    /**
     * Deletes the files a save owns. Deleting the save's rows alone leaves every PNG it generated
     * on disk forever, unreferenced and unreclaimable, which is how a device quietly fills up.
     * Must be called *before* the rows are deleted, since the rows are what name the files.
     */
    suspend fun deleteFilesFor(gameId: String) {
        store.allImages(gameId).forEach { it.localPath?.let { path -> File(path).delete() } }
        sweepOrphans()
    }

    /**
     * Removes files left behind by a crash between writing a PNG and committing its row, and by any
     * save deleted before this sweep existed. A file is an orphan when no record names it.
     */
    suspend fun sweepOrphans() {
        val files = cacheDir.listFiles() ?: return
        val referenced = store.listGames()
            .flatMap { store.allImages(it.id) }
            .mapNotNull { it.localPath }
            .toSet()
        files.filter { it.isFile && it.absolutePath !in referenced }.forEach { it.delete() }
    }

    fun cacheSizeBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
}

private const val SCENE_ENTITY_PREFIX = "scene_"

sealed interface ImageOutcome {
    data class Ready(val record: ImageRecord, val fromCache: Boolean) : ImageOutcome
    data class Failed(val reason: String) : ImageOutcome
}
