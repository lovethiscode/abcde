package com.unciv.ui.screens.mapeditorscreen

import com.unciv.json.json
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile

/** Bounded whole-map transactions also cover rivers on neighbours and nation starting locations. */
class MapEditorHistory(private val maxEntries: Int = 24, private val byteBudget: Int = 16 * 1024 * 1024) {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()
    private var pending: String? = null
    private var saved: String? = null
    val hasPending get() = pending != null
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    private fun snapshot(map: TileMap) = json().apply { setUsePrototypes(false); setDeprecated(Tile::class.java, "continent", true) }.toJson(map)
    fun reset(map: TileMap) { past.clear(); future.clear(); pending = null; markSaved(map) }
    fun markUnsaved() { saved = null }
    fun markSaved(map: TileMap) { saved = snapshot(map) }
    fun isSaved(map: TileMap) = saved == snapshot(map)
    fun begin(map: TileMap) { if (pending == null) pending = snapshot(map) }
    fun commit(map: TileMap): Boolean {
        val before = pending ?: return false
        pending = null
        if (before == snapshot(map)) return false
        past.addLast(before); future.clear()
        while (past.size > maxEntries || (past.size > 1 && past.sumOf { it.length * 2L } > byteBudget)) past.removeFirst()
        return true
    }
    fun undo(map: TileMap): TileMap? {
        if (!canUndo) return null
        future.addLast(snapshot(map))
        return restore(past.removeLast(), map)
    }
    private fun restore(snapshot: String, current: TileMap) = json().fromJson(TileMap::class.java, snapshot).apply {
        mapParameters.name = current.mapParameters.name
        mapParameters.type = current.mapParameters.type
    }
    fun redo(map: TileMap): TileMap? {
        if (!canRedo) return null
        past.addLast(snapshot(map))
        return restore(future.removeLast(), map)
    }
}
