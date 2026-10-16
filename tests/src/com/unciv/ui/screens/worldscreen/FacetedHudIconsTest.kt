package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Graphics
import com.badlogic.gdx.Input
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.utils.GdxNativesLoader
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.logic.files.UncivFiles
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.TestGame
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

/** Loads the real packaged atlas; only GPU calls are mocked. */
class FacetedHudIconsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun icon(kind: String, name: String): Actor? {
        val type = Class.forName("com.unciv.ui.screens.worldscreen.FacetedHudIcons")
        return type.getMethod(kind, String::class.java, Float::class.javaPrimitiveType)
            .invoke(type.getField("INSTANCE").get(null), name, 20f) as Actor?
    }

    @Test fun packagedHudArtKeepsColorsAliasesResourceSizingAndModPrecedence() {
        val oldApp = Gdx.app
        val oldGraphics = Gdx.graphics
        val oldInput = Gdx.input
        val oldFiles = Gdx.files
        val oldGl = Gdx.gl
        val oldGl20 = Gdx.gl20
        val oldGame = if (UncivGame.isCurrentInitialized()) UncivGame.Current else null
        val oldFont = runCatching { Fonts.fontImplementation }.getOrNull()
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
            fun reload(vararg mods: String) {
                settings.visualMods.clear()
                settings.visualMods.addAll(mods)
                ImageGetter.setNewRuleset(ruleset)
            }
            reload()

            for (name in listOf("Gold", "Science", "Culture", "Faith", "Happiness", "Malcontent")) {
                val image = icon("stat", name) as Image
                assertEquals("Full-color stat art is not tinted", Color.WHITE, image.color)
                assertSame(ImageGetter.getDrawable("FacetedHudIcons/Stats/$name"), image.drawable)
            }
            for (name in listOf("Horses", "Iron", "Coal", "Oil", "Aluminum", "Uranium")) {
                val resource = icon("resource", name) as WidgetGroup
                resource.setSize(16f, 16f)
                resource.validate()
                assertEquals("The picture fits the 16pt resource cell", .8f,
                    (resource.children.first() as com.badlogic.gdx.scenes.scene2d.Group).scaleX, .001f)
            }
            val aliases = mapOf("StopMovement" to "Stop", "StopAutomation" to "Stop",
                "StopExploration" to "Stop", "SleepUntilHealed" to "Sleep",
                "SwapUnits" to "Swap", "ConnectRoad" to "RoadConnection",
                "ShowAdditionalActions" to "ShowMore")
            for ((action, art) in aliases) {
                val image = icon("action", action) as Image
                assertSame("Action aliases resolve to the packed art", ImageGetter.getDrawable("FacetedHudIcons/Actions/$art"), image.drawable)
            }
            assertNull("Unknown mod content uses the existing renderer", icon("resource", "Mod-only resource"))

            val folder = UncivGame.Current.files.getModFolder("Hud-override-fixture")
            folder.mkdirs()
            folder.child("Atlases.json").writeString("[\"fixture\"]", false)
            Gdx.files.internal("FacetedHudIcons.png").copyTo(folder.child("fixture.png"))
            val regions = listOf("StatIcons/Gold", "ResourcePortraits/Horses", "UnitActionIcons/StopMove")
            folder.child("fixture.atlas").writeString("\nfixture.png\nsize: 1024, 1024\nformat: RGBA8888\nfilter: Linear, Linear\nrepeat: none\n" +
                regions.joinToString("") { "$it\n  rotate: false\n  xy: 0, 0\n  size: 1, 1\n  orig: 1, 1\n  offset: 0, 0\n  index: -1\n" }, false)
            reload("Hud-override-fixture")
            assertTrue(ImageGetter.hasModImage("StatIcons/Gold"))
            assertEquals(1, ImageGetter.getDrawable("StatIcons/Gold").minWidth.toInt())
            assertNull("A mod stat icon wins", icon("stat", "Gold"))
            assertNull("A mod resource portrait wins", icon("resource", "Horses"))
            assertNull("A mod StopMove icon wins over the Stop alias", icon("action", "StopMovement"))
            assertNotNull("Unrelated art stays active", icon("stat", "Science"))

            reload()
            assertFalse("Removing the mod clears its source guard", ImageGetter.hasModImage("StatIcons/Gold"))
            assertNotNull(icon("stat", "Gold"))
            settings.skin = "Custom-fixture-skin"
            assertNull("Custom skins retain their established art", icon("stat", "Gold"))
            settings.skin = "Minimal"
            `when`(Gdx.app.type).thenReturn(Application.ApplicationType.Desktop)
            assertNull("Other platforms retain their established art", icon("action", "Fortify"))
        } finally {
            Gdx.app = oldApp
            Gdx.graphics = oldGraphics
            Gdx.input = oldInput
            Gdx.files = oldFiles
            Gdx.gl = oldGl
            Gdx.gl20 = oldGl20
            oldFont?.let { Fonts.fontImplementation = it }
            if (oldGame != null) UncivGame.Current = oldGame
        }
    }
}
