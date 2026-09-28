package com.unciv.ui.components.fonts

import com.badlogic.gdx.Files
import com.badlogic.gdx.files.FileHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Finds font files shipped by mods, in `<mod>/fonts/` */
object ModFonts {
    // What both java.awt.Font.createFont and android.graphics.Typeface.createFromFile support:
    private val supportedExtensions = setOf("ttf", "otf")

    // Internal and Classpath handles may live inside a jar or apk and can't be listed via java.nio
    private val fileSystemTypes = setOf(Files.FileType.Local, Files.FileType.Absolute, Files.FileType.External)

    /**
     *  Lists the fonts of all mods in [modsDir] (expected to be [UncivFiles.getModsFolder][com.unciv.logic.files.UncivFiles.getModsFolder]).
     *  Requires a local, external, or absolute filesystem handle.
     *
     *  Cold flow doing blocking file IO - the collector picks the dispatcher.
     *  [AdvancedTab][com.unciv.ui.popups.options.AdvancedTab] collects it on [kotlinx.coroutines.Dispatchers.IO].
     */
    fun scan(modsDir: FileHandle): Flow<FontFamilyData> = flow {
        if (modsDir.type() !in fileSystemTypes || !modsDir.exists() || !modsDir.isDirectory) return@flow
        for (mod in modsDir.list())
            emitAll(scanMod(modsDir.name(), mod))
    }

    private fun scanMod(modsDirName: String, mod: FileHandle) = flow {
        if (!mod.isDirectory) return@flow
        val fontsFolder = mod.child("fonts")
        if (!fontsFolder.exists() || !fontsFolder.isDirectory) return@flow
        for (file in fontsFolder.list()) {
            if (file.extension().lowercase() !in supportedExtensions) continue
            emit(FontFamilyData(
                "${file.nameWithoutExtension()} (${mod.name()})",
                file.nameWithoutExtension(),
                // Relative to the data folder, as the font loaders resolve it with UncivFiles.getLocalFile
                "$modsDirName/${mod.name()}/fonts/${file.name()}"
            ))
        }
    }
}
