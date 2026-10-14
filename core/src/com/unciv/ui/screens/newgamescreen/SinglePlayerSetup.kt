package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ExpanderTab

/** Compact forms used only by the iOS single-player setup. */
internal object SinglePlayerSetup {
    fun style(actor: Actor, font: BitmapFont) {
        when (actor) {
            is Label -> actor.setFontSize(22)
            is SelectBox<*> -> actor.style = SelectBox.SelectBoxStyle(actor.style).apply {
                this.font = font
                listStyle = ListStyle(listStyle).apply { this.font = font }
            }
            is TextField -> actor.style = TextField.TextFieldStyle(actor.style).apply { this.font = font }
        }
        if (actor is Group) actor.children.forEach { style(it, font) }
    }

    fun field(title: String, control: Actor, width: Float) = Table().apply {
        val labelWidth = (width * .38f).coerceAtMost(175f)
        add(title.toLabel(ExpeditionBlueTheme.muted, 22).apply { wrap = true })
            .width(labelWidth).left().padRight(12f)
        add(control).minWidth(0f).width(width - labelWidth - 12f).minHeight(44f).fillX()
    }

    fun form(width: Float, fields: List<Pair<String, Actor>>) = Table().apply {
        top()
        val columns = if (width >= 600f) 2 else 1
        val fieldWidth = (width - (columns - 1) * 24f) / columns
        for ((index, item) in fields.withIndex()) {
            add(field(item.first, item.second, fieldWidth)).width(fieldWidth).top()
                .padRight(if (index % columns == columns - 1) 0f else 24f).padBottom(16f)
            if (index % columns == columns - 1) row()
        }
    }

    fun group(title: String, width: Float, id: String, opened: Boolean = false,
              content: (Table) -> Unit) = ExpanderTab(title, fontSize = 22,
        startsOutOpened = opened, defaultPad = 12f, expanderWidth = width,
        expanderHeight = 44f, headerPad = 0f, persistenceID = "SinglePlayer.$id", initContent = content).apply {
        header.background = null
    }

    fun counter(value: Int, maximum: Int, enabled: Boolean, changed: (Int) -> Unit) = Table().apply {
        var current = value
        val label = current.toString().toLabel(fontSize = 22)
        val minus = "−".toTextButton()
        val plus = "+".toTextButton()
        fun refresh() {
            label.setText(current.toString())
            minus.isDisabled = !enabled || current <= 0
            plus.isDisabled = !enabled || current >= maximum
        }
        minus.onClick { current--; refresh(); changed(current) }
        plus.onClick { current++; refresh(); changed(current) }
        add(minus).size(44f)
        add(label).minWidth(44f).growX()
        add(plus).size(44f)
        refresh()
    }
}
