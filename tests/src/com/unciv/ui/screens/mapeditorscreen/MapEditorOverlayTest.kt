package com.unciv.ui.screens.mapeditorscreen

import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import java.io.File

@RunWith(GdxTestRunner::class)
class MapEditorOverlayTest {
    @Test
    fun offscreenTextureOriginDoesNotCullTheFirstOverlayFrame() {
        val png = FileHandle(File.createTempFile("map-overlay", ".png"))
        val pixels = Pixmap(8, 8, Pixmap.Format.RGBA8888)
        try {
            pixels.setColor(0f, 0f, 1f, 1f)
            pixels.fill()
            PixmapIO.writePNG(png, pixels)
        } finally {
            pixels.dispose()
        }
        val screen = mock(MapEditorScreen::class.java)
        screen.javaClass.getDeclaredField("overlayFile").apply { isAccessible = true; set(screen, png) }
        screen.javaClass.getDeclaredField("overlayAlpha").apply { isAccessible = true; setFloat(screen, 0.5f) }
        val map = Group().apply {
            isTransform = false
            setSize(1000f, 1000f)
            cullingArea = Rectangle(400f, 400f, 100f, 100f)
        }
        try {
            screen.javaClass.getDeclaredMethod("addOverlayToMapHolder", Group::class.java)
                .apply { isAccessible = true }.invoke(screen, map)
            var drawCalls = 0
            val batch = mock(Batch::class.java) { invocation ->
                if (invocation.method.name == "draw") drawCalls++
                org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)
            }
            map.draw(batch, 1f)
            assertTrue("overlay is drawn on its first frame even when (0,0) is outside the viewport", drawCalls > 0)
            val overlay = map.children.single() as Image
            assertEquals(0.5f, overlay.color.a, 0.001f)
        } finally {
            (map.children.firstOrNull() as? Image)?.let {
                (it.drawable as TextureRegionDrawable).region.texture.dispose()
            }
            png.delete()
        }
    }
}
