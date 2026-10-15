@file:Suppress("InvalidPackageDeclaration")
package com.unciv.build

import com.badlogic.gdx.tools.texturepacker.TexturePacker
import com.badlogic.gdx.utils.Json
import java.io.File

object FacetedHudIconPacker {
    fun pack(input: String, output: String) {
        val settings = Json().fromJson(TexturePacker.Settings::class.java,
            File(input, "TexturePacker.settings").reader())
        TexturePacker.process(settings, input, output, "FacetedHudIcons")
    }
}
