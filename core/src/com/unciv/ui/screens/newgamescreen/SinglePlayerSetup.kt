package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.screens.basescreen.BaseScreen

/** Compact forms used only by the iOS single-player setup. */
internal object SinglePlayerSetup {
    fun surface(color: Color = ExpeditionBlueTheme.raised, outline: Color = ExpeditionBlueTheme.border) =
        ExpeditionBlueTheme.outlinedControl(
            BaseScreen.skinStrings.getUiBackground("SinglePlayerSetup/Surface", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color),
            BaseScreen.skinStrings.getUiBackground("SinglePlayerSetup/Outline", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, outline))

    fun style(actor: Actor, font: BitmapFont) {
        when (actor) {
            is Label -> actor.setFontSize(18)
            is CheckBox -> actor.style = CheckBox.CheckBoxStyle(actor.style).apply { this.font = font }
            is TextButton -> actor.style = TextButton.TextButtonStyle(actor.style).apply {
                this.font = font
                up = surface()
                down = surface(ExpeditionBlueTheme.hero, ExpeditionBlueTheme.controlBorder)
                over = down
                disabled = surface(ExpeditionBlueTheme.panel)
            }
            is SelectBox<*> -> actor.style = SelectBox.SelectBoxStyle(actor.style).apply {
                this.font = font
                listStyle = ListStyle(listStyle).apply { this.font = font }
                val body = surface(ExpeditionBlueTheme.raised, ExpeditionBlueTheme.controlBorder)
                val arrow = ImageGetter.getDrawable("OtherIcons/ArrowRight").tint(ExpeditionBlueTheme.text) as TransformDrawable
                background = object : BaseDrawable(body) {
                    init { leftWidth = 10f; rightWidth = 28f; topHeight = 8f; bottomHeight = 8f }
                    override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, x: Float, y: Float, width: Float, height: Float) {
                        body.draw(batch, x, y, width, height)
                        arrow.draw(batch, x + width - 24f, y + height / 2f - 7f, 7f, 7f, 14f, 14f, 1f, 1f, -90f)
                    }
                }
                backgroundOver = background
                backgroundOpen = background
            }
            is TextField -> actor.style = TextField.TextFieldStyle(actor.style).apply { this.font = font }
            is IconTextButton -> actor.style = Button.ButtonStyle(actor.style).apply {
                up = surface()
                down = surface(ExpeditionBlueTheme.hero, ExpeditionBlueTheme.controlBorder)
                over = down
                disabled = surface(ExpeditionBlueTheme.panel)
            }
        }
        if (actor is Group) actor.children.forEach { style(it, font) }
    }

    fun field(title: String, control: Actor, width: Float) = Table().apply {
        val labelWidth = (width * .36f).coerceAtMost(175f)
        control.name = "setup-field-$title"
        add(title.toLabel(ExpeditionBlueTheme.text, 18).apply { wrap = true })
            .width(labelWidth).left().padRight(8f)
        add(control).minWidth(0f).width(width - labelWidth - 8f).minHeight(44f).fillX()
    }

    fun form(width: Float, fields: List<Pair<String, Actor>>) = Table().apply {
        top()
        val columns = if (width >= 600f) 2 else 1
        val fieldWidth = (width - (columns - 1) * 12f) / columns
        for ((index, item) in fields.withIndex()) {
            add(field(item.first, item.second, fieldWidth)).width(fieldWidth).top()
                .padRight(if (index % columns == columns - 1) 0f else 12f).padBottom(8f)
            if (index % columns == columns - 1) row()
        }
    }

    fun group(title: String, width: Float, id: String, opened: Boolean = false,
              content: (Table) -> Unit) = ExpanderTab(title, fontSize = 18,
        startsOutOpened = opened, defaultPad = 12f, expanderWidth = width,
        expanderHeight = 44f, headerPad = 0f, persistenceID = "SinglePlayer.$id", initContent = content).apply {
        header.background = surface()
    }

}
