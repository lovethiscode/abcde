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
    fun surface(color: Color = ExpeditionBlueTheme.raised, outline: Color = ExpeditionBlueTheme.border): BaseDrawable {
        val fill = BaseScreen.skinStrings.getUiBackground("SinglePlayerSetup/Surface", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, color)
        val edge = BaseScreen.skinStrings.getUiBackground("SinglePlayerSetup/Outline", BaseScreen.skinStrings.roundedEdgeRectangleSmallShape, outline)
        return object : BaseDrawable(fill) {
            override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, x: Float, y: Float, width: Float, height: Float) {
                edge.draw(batch, x, y, width, height)
                fill.draw(batch, x + 1f, y + 1f, width - 2f, height - 2f)
            }
        }
    }

    fun style(actor: Actor, font: BitmapFont) {
        when (actor) {
            is Label -> actor.setFontSize(18)
            is CheckBox -> actor.style = CheckBox.CheckBoxStyle(actor.style).apply {
                this.font = font
                checkedFontColor = ExpeditionBlueTheme.text
                checkboxOff = ExpeditionBlueTheme.outlinedControl(
                    ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.panel),
                    ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.controlBorder)).apply {
                    minWidth = 22f; minHeight = 22f
                }
                checkboxOver = checkboxOff
                checkboxOn = object : BaseDrawable() {
                    private val fill = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.gold)
                    private val check = ImageGetter.getDrawable("OtherIcons/Checkmark").tint(ExpeditionBlueTheme.primaryInk)
                    init { minWidth = 22f; minHeight = 22f }
                    override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, x: Float, y: Float, width: Float, height: Float) {
                        fill.draw(batch, x, y, width, height)
                        check.draw(batch, x + 4f, y + 4f, width - 8f, height - 8f)
                    }
                }
                checkboxOnOver = checkboxOn
            }
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
                val body = surface(ExpeditionBlueTheme.background, ExpeditionBlueTheme.border)
                val iconName = when (actor.name) {
                    "setup-field-Difficulty" -> "OtherIcons/Capital"
                    "setup-field-Game Speed" -> "OtherIcons/Timer"
                    "setup-field-Starting Era" -> "VictoryTypeIcons/Time"
                    "setup-field-City-States" -> "OtherIcons/Cities"
                    else -> null
                }
                val icon = iconName?.let { ImageGetter.getDrawable(it).tint(ExpeditionBlueTheme.gold) }
                val arrow = ImageGetter.getDrawable("OtherIcons/ArrowRight").tint(ExpeditionBlueTheme.text) as TransformDrawable
                background = object : BaseDrawable(body) {
                    init { leftWidth = if (icon == null) 10f else 38f; rightWidth = 28f; topHeight = 8f; bottomHeight = 8f }
                    override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, x: Float, y: Float, width: Float, height: Float) {
                        body.draw(batch, x, y, width, height)
                        icon?.draw(batch, x + 10f, y + height / 2f - 9f, 18f, 18f)
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
        background = surface(ExpeditionBlueTheme.panel)
        pad(3f, 8f, 3f, 4f)
        val labelWidth = (width * .35f).coerceAtMost(175f)
        val caption = when (title) {
            "City-States" -> "City-State count"
            "Map Type", "Map Generation Type" -> "Map type"
            "World Size" -> "Map size"
            else -> title
        }
        control.name = "setup-field-$title"
        val captionIcon = when (title) {
            "Map source" -> "OtherIcons/Resources"
            "Map Type", "Map Generation Type" -> "OtherIcons/Terrains"
            "World Size" -> "OtherIcons/Hexagon"
            else -> null
        }
        add(Table().apply {
            if (captionIcon != null) add(ImageGetter.getImage(captionIcon, ExpeditionBlueTheme.text)).size(18f).padRight(7f)
            add(caption.toLabel(ExpeditionBlueTheme.text, 18).apply { wrap = true }).minWidth(0f).growX().left()
        }).width(labelWidth).left().padRight(8f)
        add(control).minWidth(0f).width(width - labelWidth - 20f).height(36f).fillX()
    }

    fun form(width: Float, fields: List<Pair<String, Actor>>) = Table().apply {
        top()
        val columns = if (width >= 600f) 2 else 1
        val fieldWidth = (width - (columns - 1) * 12f) / columns
        for ((index, item) in fields.withIndex()) {
            add(field(item.first, item.second, fieldWidth)).width(fieldWidth).top()
                .padRight(if (index % columns == columns - 1) 0f else 12f).padBottom(6f)
            if (index % columns == columns - 1) row()
        }
    }

    fun group(title: String, width: Float, id: String, opened: Boolean = false,
              content: (Table) -> Unit) = ExpanderTab(title, fontSize = 18,
        icon = ImageGetter.getImage(when (title) {
            "Advanced Settings" -> "OtherIcons/Options"
            "Extension mods" -> "OtherIcons/Mods"
            else -> "OtherIcons/Settings"
        }, ExpeditionBlueTheme.text).apply { setSize(20f, 20f) },
        startsOutOpened = opened, defaultPad = 12f, expanderWidth = width,
        expanderHeight = 36f, headerPad = 0f, persistenceID = "SinglePlayer.$id", initContent = content).apply {
        name = "setup-group-$title"
        header.pad(0f, 10f, 0f, 10f)
        header.cells.first().size(20f).padRight(10f)
        header.background = surface()
        headerIcon.rotation = 180f
    }

}
