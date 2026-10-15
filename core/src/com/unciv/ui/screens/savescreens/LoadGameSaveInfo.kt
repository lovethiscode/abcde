package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.files.UncivFiles

/** A fingerprint prevents a late preview from describing a replaced or deleted save slot. */
data class LoadGameSaveKey(val path: String, val modified: Long, val length: Long) {
    companion object {
        fun of(file: FileHandle) = LoadGameSaveKey(file.path(), file.lastModified(), file.length())
    }
}

internal class LoadGameSaveInfo(
    val key: LoadGameSaveKey,
    val civilizations: List<String>,
    val difficulty: String,
    val turns: Int,
    val ruleset: String,
    val mods: List<String>,
    val online: Boolean,
)

internal data class LoadGamePreview(val info: LoadGameSaveInfo?, val error: Exception? = null) {
    companion object {
        fun read(files: UncivFiles, file: FileHandle): LoadGamePreview = try {
            val preview = files.loadGamePreviewFromFile(file)
            LoadGamePreview(LoadGameSaveInfo(LoadGameSaveKey.of(file),
                preview.civilizations.filter { it.isPlayerCivilization() }.map { it.civName },
                preview.difficulty, preview.turns, preview.gameParameters.baseRuleset,
                preview.gameParameters.mods.toList(), preview.gameParameters.isOnlineMultiplayer))
        } catch (error: Exception) { LoadGamePreview(null, error) }
    }
}

/** Shared by the worker and UI: never apply a result from an earlier selection or list generation. */
fun acceptsLoadGamePreview(
    requested: LoadGameSaveKey, selected: LoadGameSaveKey?, requestGeneration: Int, currentGeneration: Int,
) = requested == selected && requestGeneration == currentGeneration
