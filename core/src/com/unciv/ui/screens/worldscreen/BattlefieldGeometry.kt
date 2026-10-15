package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.Layout
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.images.Portrait
import com.unciv.ui.screens.basescreen.BaseScreen

/** Scale the painted geometry as well as the layout cells when world units are smaller than points. */
internal object BattlefieldGeometry {
    private fun surface(color: Color): Drawable {
        val original = BaseScreen.skinStrings.getUiBackground("WorldScreen/BattlefieldControl",
            BaseScreen.skinStrings.roundedEdgeRectangleShape, color)
        val patch = NinePatch(original.patch)
        val corner = maxOf(patch.leftWidth, patch.rightWidth, patch.topHeight, patch.bottomHeight)
        if (corner > 0f) {
            val scale = minOf(1f, BattlefieldHud.points(18f) / corner)
            patch.scale(scale, scale)
        }
        return NinePatchDrawable(patch).apply { minWidth = 0f; minHeight = 0f }
    }

    private fun outlined(color: Color): Drawable {
        val edge = surface(ExpeditionBlueTheme.controlBorder)
        val fill = surface(color)
        return object : BaseDrawable() {
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                edge.draw(batch, x, y, width, height)
                val inset = BattlefieldHud.points(1f)
                fill.draw(batch, x + inset, y + inset, width - inset * 2f, height - inset * 2f)
            }
        }
    }

    fun fitControl(button: Button, flat: Boolean = false) {
        val style = if (button is TextButton) TextButton.TextButtonStyle(button.style)
            else Button.ButtonStyle(button.style)
        style.up = if (flat) null else outlined(ExpeditionBlueTheme.panel)
        style.down = outlined(ExpeditionBlueTheme.raised)
        style.over = style.down
        style.checked = style.up
        style.disabled = if (flat) null else outlined(ExpeditionBlueTheme.background)
        button.style = style
    }

    fun fitClose(group: Actor, size: Float) {
        group.setSize(size, size)
        if (group !is Group) return
        for (child in group.children) {
            if (child is Button) fitControl(child)
            child.setBounds(0f, 0f, size, size)
            (child as? Layout)?.validate()
        }
    }

    fun icon(actor: Actor): Actor = if (actor is FittedIcon) actor else FittedIcon(actor)

    private class FittedIcon(actor: Actor) : WidgetGroup() {
        private val picture = Group()
        private val naturalWidth = if (actor is Portrait) actor.size + actor.borderSize else actor.width.coerceAtLeast(1f)
        private val naturalHeight = if (actor is Portrait) actor.size + actor.borderSize else actor.height.coerceAtLeast(1f)
        init {
            actor.setBounds(0f, 0f, naturalWidth, naturalHeight)
            picture.addActor(actor)
            addActor(picture)
            setSize(naturalWidth, naturalHeight)
        }
        override fun getPrefWidth() = naturalWidth
        override fun getPrefHeight() = naturalHeight
        override fun layout() {
            val scale = minOf(width / naturalWidth, height / naturalHeight)
            picture.setScale(scale)
            picture.setPosition((width - naturalWidth * scale) / 2f, (height - naturalHeight * scale) / 2f)
        }
    }
}
