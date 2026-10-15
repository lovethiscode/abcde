package com.unciv.ui.components.tilegroups

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.GdxNativesLoader
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.models.skins.SkinCache
import com.unciv.models.skins.SkinConfig
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.tilesets.TileSetConfig
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

/** Exercise the real atlas loader and config merge against isolated local mod fixtures.
 * Only GPU calls and the tile group's view are mocked; live simulator mods are never touched.
 */
class BattlefieldModCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    // The terrain resolver is module-internal. Exercise its JVM entry without changing
    // production visibility or relying on compiler-specific internal-access suppression.
    private fun battlefieldEnabled(group: TileGroup): Boolean {
        val type = Class.forName("com.unciv.ui.components.tilegroups.layers.BattlefieldTerrain")
        return type.getMethod("enabled", TileGroup::class.java)
            .invoke(type.getField("INSTANCE").get(null), group) as Boolean
    }

    @Test fun activeModArtAndConfigsKeepTheirOwnTerrainWhileVanillaKeepsBattlefield() {
        val oldApp = Gdx.app
        val oldGraphics = Gdx.graphics
        val oldInput = Gdx.input
        val oldFiles = Gdx.files
        val oldGl = Gdx.gl
        val oldGl20 = Gdx.gl20
        val oldGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else null
        GdxNativesLoader.load()
        try {
            Gdx.app = mock(Application::class.java)
            `when`(Gdx.app.type).thenReturn(Application.ApplicationType.iOS)
            Gdx.graphics = mock(Graphics::class.java)
            Gdx.input = mock(Input::class.java)
            Gdx.files = HeadlessFiles()
            Gdx.gl = mock(GL20::class.java)
            Gdx.gl20 = Gdx.gl
            UncivGame.Current = UncivGame().apply {
                settings = GameSettings()
                files = UncivFiles(Gdx.files, temporary.root.absolutePath)
            }
            Fonts.fontImplementation = FontDesktop()
            val ruleset = TestGame(forUITesting = true).ruleset
            val settings = UncivGame.Current.settings
            val world = mock(WorldTileGroup::class.java)
            fun useTileSet(name: String) {
                val strings = mock(TileSetStrings::class.java)
                `when`(strings.tileSetName).thenReturn(name)
                `when`(world.tileSetStrings).thenReturn(strings)
            }
            fun reload(vararg mods: String) {
                settings.visualMods.clear()
                settings.visualMods.addAll(mods)
                ImageGetter.setNewRuleset(ruleset)
            }
            fun atlasMod(name: String, region: String) {
                val folder = UncivGame.Current.files.getModFolder(name)
                folder.mkdirs()
                folder.child("Atlases.json").writeString("[\"fixture\"]", false)
                // Use a shipped PNG; its pixels are irrelevant to atlas precedence.
                Gdx.files.internal("Battlefield.png").copyTo(folder.child("fixture.png"))
                folder.child("fixture.atlas").writeString("""
                    fixture.png
                    size: 2048, 2048
                    format: RGBA8888
                    filter: Linear, Linear
                    repeat: none
                    $region
                      rotate: false
                      xy: 0, 0
                      size: 1, 1
                      orig: 1, 1
                      offset: 0, 0
                      index: -1
                """.trimIndent(), false)
            }

            reload()
            TileSetCache.loadTileSetConfigs()
            useTileSet("HexaRealm")
            assertTrue("Vanilla iOS HexaRealm uses the new terrain", battlefieldEnabled(world))

            atlasMod("Battlefield-terrain-fixture", "TileSets/HexaRealm/Tiles/Grassland")
            reload("Battlefield-terrain-fixture")
            assertTrue(ImageGetter.hasModTerrainImages("HexaRealm"))
            assertEquals("The mod region actually won atlas precedence", 1,
                ImageGetter.getDrawable("TileSets/HexaRealm/Tiles/Grassland").minWidth.toInt())
            assertFalse("A same-name terrain mod is never replaced", battlefieldEnabled(world))

            reload()
            assertFalse("Removing a mod clears the source guard", ImageGetter.hasModTerrainImages("HexaRealm"))
            assertTrue("Vanilla reactivates after removing the mod", battlefieldEnabled(world))

            atlasMod("Battlefield-unit-fixture", "TileSets/HexaRealm/Units/Warrior")
            reload("Battlefield-unit-fixture")
            assertFalse(ImageGetter.hasModTerrainImages("HexaRealm"))
            assertTrue("Unit-only art does not suppress vanilla terrain", battlefieldEnabled(world))

            ruleset.mods.add("Battlefield-rules-fixture")
            reload()
            assertTrue("Rules-only mods do not suppress vanilla terrain", battlefieldEnabled(world))
            ruleset.mods.clear()

            val hexaRealm = TileSetCache["HexaRealm"]!!
            hexaRealm.cacheConfigFromMod("Battlefield-config-fixture", TileSetConfig())
            reload("Battlefield-config-fixture")
            assertTrue(hexaRealm.hasActiveModConfig())
            assertFalse("An explicitly active mod config controls terrain resolution", battlefieldEnabled(world))
            reload()
            assertFalse(hexaRealm.hasActiveModConfig())
            assertTrue(battlefieldEnabled(world))

            useTileSet("FantasyHex")
            assertFalse("Other tilesets keep their original art", battlefieldEnabled(world))
            useTileSet("HexaRealm")
            settings.skin = "Battlefield-custom-skin-fixture"
            assertFalse("An explicit skin keeps its UI and art world", battlefieldEnabled(world))
            settings.skin = "Minimal"
            SkinCache["Minimal"] = SkinConfig()
            assertFalse("A mod config for the default skin also keeps its own world", battlefieldEnabled(world))
            SkinCache.remove("Minimal")
            assertTrue(battlefieldEnabled(world))
        } finally {
            Gdx.app = oldApp
            Gdx.graphics = oldGraphics
            Gdx.input = oldInput
            Gdx.files = oldFiles
            Gdx.gl = oldGl
            Gdx.gl20 = oldGl20
            if (oldGame != null) UncivGame.Current = oldGame
        }
    }
}
