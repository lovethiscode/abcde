package com.unciv.models.tilesets

class TileSet(val name: String) {

    var config = TileSetConfig()
    var fallback: TileSet? = null

    private val modNameToConfig = HashMap<String, TileSetConfig>()
    private var modConfigActive = false
    fun hasActiveModConfig() = modConfigActive

    fun cacheConfigFromMod(modName: String, config: TileSetConfig) {
        modNameToConfig[modName] = config
    }

    fun mergeModConfig(modName: String) {
        val configToMerge = modNameToConfig[modName] ?: return
        if (modName != DEFAULT) modConfigActive = true
        config.updateConfig(configToMerge)
    }

    fun resetConfig() {
        config = TileSetConfig()
        modConfigActive = false
    }

    companion object {
        const val DEFAULT = "INTERNAL"
    }

    override fun toString(): String = name

}
