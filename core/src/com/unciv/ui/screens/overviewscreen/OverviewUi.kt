package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable
import com.unciv.models.translations.tr
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen

/** Shared spacing and controls for the classic empire overview. */
internal object OverviewUi {
    val touch get() = UiNavigation.minimumTouchSize()
    // The approved landscape prototype is measured in phone points, not game world units.
    fun pt(value: Float) = value * touch / 44f
    fun font(value: TextRole) = UiTypography.size(value)
    val rowHeight get() = pt(46f)
    val background get() = ExpeditionBlueTheme.color(BaseScreen.skinStrings.skinConfig.baseColor, ExpeditionBlueTheme.background)
    val panel get() = ExpeditionBlueTheme.color(BaseScreen.skinStrings.skinConfig.baseColor, ExpeditionBlueTheme.panel)
    val raised get() = ExpeditionBlueTheme.color(Color.DARK_GRAY, ExpeditionBlueTheme.raised)
    val muted get() = ExpeditionBlueTheme.color(Color.LIGHT_GRAY, ExpeditionBlueTheme.muted)
    val accent get() = ExpeditionBlueTheme.color(Color.GOLD, ExpeditionBlueTheme.gold)
    val border get() = ExpeditionBlueTheme.color(Color.GRAY, ExpeditionBlueTheme.border)
    val selected get() = ExpeditionBlueTheme.color(raised, Color.valueOf("385B66"))
    val danger get() = ExpeditionBlueTheme.color(Color.RED, ExpeditionBlueTheme.danger)
    fun surface(color: Color) = ImageGetter.getWhiteDotDrawable().tint(color)

    fun rounded(color: Color, radius: Float = 8f) =
        (BaseScreen.skin.getDrawable("RoundedEdgeRectangle") as NinePatchDrawable).let { source ->
            NinePatchDrawable(NinePatch(source.patch, color).apply {
                scale(pt(radius) / leftWidth, pt(radius) / topHeight)
                setPadding(0f, 0f, 0f, 0f)
            }).apply { minWidth = 0f; minHeight = 0f }
        }

    fun button(selected: Boolean = false, flat: Boolean = false, action: () -> Unit): Button = Button(Button.ButtonStyle().apply {
        up = if (flat) surface(Color.CLEAR) else rounded(panel)
        over = if (flat) surface(raised) else rounded(raised)
        down = if (flat) surface(OverviewUi.selected) else rounded(OverviewUi.selected)
        checked = down
    }).apply {
        isChecked = selected
        onActivation { action() }
    }

    fun primaryButton(title: String, action: () -> Unit) = button(action = action).apply {
        style = Button.ButtonStyle(style).apply { up = rounded(accent); down = rounded(muted); over = down }
        add(title.toLabel(fontColor = ExpeditionBlueTheme.primaryInk, fontSize = font(TextRole.Control), hideIcons = true)).pad(0f, pt(16f), 0f, pt(16f))
    }

    private fun selectBackground(color: Color): BaseDrawable {
        val fill = surface(color)
        val arrow = ImageGetter.getDrawable("OtherIcons/ArrowRight").tint(muted) as TransformDrawable
        return object : BaseDrawable() {
            init { leftWidth = pt(12f); rightWidth = pt(32f) }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                fill.draw(batch, x, y, width, height)
                arrow.draw(batch, x + width - pt(22f), y + height / 2 - pt(6f),
                    pt(6f), pt(6f), pt(12f), pt(12f), 1f, 1f, -90f)
            }
        }
    }

    fun styleSearch(field: TextField) {
        fun underline(color: Color) = object : BaseDrawable() {
            private val line = surface(color)
            init { leftWidth = pt(4f); rightWidth = pt(4f) }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                line.draw(batch, x, y + pt(6f), width, pt(1f))
            }
        }
        field.style = TextField.TextFieldStyle(field.style).apply {
            background = underline(muted)
            focusedBackground = underline(accent)
        }
    }

    fun styleSelect(select: SelectBox<*>) {
        select.style = SelectBox.SelectBoxStyle(select.style).apply {
            background = selectBackground(Color.CLEAR)
            backgroundOpen = selectBackground(selected)
            backgroundOver = selectBackground(raised)
        }
    }

    fun plainLabel(text: String, size: TextRole, color: Color = com.unciv.models.skins.ExpeditionBlueTheme.text) =
        "".toLabel(fontColor = color, fontSize = font(size)).apply { setText(text.tr(hideIcons = true, hideStats = true)) }

    fun iconButton(path: String, label: String, action: () -> Unit) = button(flat = true, action = action).apply {
        add(ImageGetter.getImage(path).apply { color = accent }).size(pt(20f))
        name = label
        addTooltip(label)
    }

    fun infoButton(action: () -> Unit) = button(flat = true, action = action).apply {
        val icon = com.badlogic.gdx.scenes.scene2d.Group().apply {
            setSize(pt(22f), pt(22f))
            addActor(ImageGetter.getCircle(accent, pt(22f)))
            addActor(ImageGetter.getCircle(panel, pt(18f)).apply { setPosition(pt(2f), pt(2f)) })
            addActor(ImageGetter.getDot(accent).apply { setBounds(pt(10f), pt(5f), pt(2f), pt(8f)) })
            addActor(ImageGetter.getCircle(accent, pt(2.5f)).apply { setPosition(pt(9.75f), pt(15f)) })
        }
        icon.setScale(18f / 22f)
        add(icon).size(pt(18f))
        addTooltip("City details")
    }

    fun locateButton(action: () -> Unit) = button(flat = true, action = action).apply {
        val icon = com.badlogic.gdx.scenes.scene2d.Group().apply {
            setSize(pt(22f), pt(22f))
            addActor(ImageGetter.getCircle(accent, pt(16f)).apply { setPosition(pt(3f), pt(3f)) })
            addActor(ImageGetter.getCircle(panel, pt(12f)).apply { setPosition(pt(5f), pt(5f)) })
            addActor(ImageGetter.getCircle(accent, pt(4f)).apply { setPosition(pt(9f), pt(9f)) })
            for (y in listOf(pt(0f), pt(17f)))
                addActor(ImageGetter.getDot(accent).apply { setBounds(pt(10f), y, pt(2f), pt(5f)) })
            for (x in listOf(pt(0f), pt(17f)))
                addActor(ImageGetter.getDot(accent).apply { setBounds(x, pt(10f), pt(5f), pt(2f)) })
        }
        icon.setScale(20f / 22f)
        add(icon).size(pt(20f))
        addTooltip("Locate on map")
    }

    fun scroll(actor: Actor) = AutoScrollPane(actor, BaseScreen.skin).apply {
        style = ScrollPane.ScrollPaneStyle(style).apply {
            background = null
            vScroll = null
            hScroll = null
            // iPhone content remains touch-scrollable without adding a visible bar.
            vScrollKnob = if (UiNavigation.isIos) null else rounded(muted).apply { minWidth = pt(4f) }
            hScrollKnob = if (UiNavigation.isIos) null else rounded(muted).apply { minHeight = pt(4f) }
        }
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        setFadeScrollBars(true)
        setScrollbarsOnTop(true)
    }

    fun Table.detailRow(label: String, value: Actor) {
        if (value is Label) {
            value.wrap = true
            value.setAlignment(Align.right)
        }
        val entry = Table().apply {
            add(plainLabel(label, TextRole.Supporting, muted).apply { wrap = true }).minWidth(0f).growX().left()
            add(value).minWidth(0f).growX().right()
        }
        add(entry).colspan(2).growX().minHeight(pt(39f)).row()
        add(ImageGetter.getWhiteDot().apply { color = border.cpy().apply { a = .47f } })
            .colspan(2).growX().height(pt(1f)).row()
    }

    fun Table.detailRow(label: String, value: String) =
        detailRow(label, value.toLabel(fontSize = font(TextRole.Supporting), alignment = Align.right, hideIcons = true).apply { wrap = true })
}
