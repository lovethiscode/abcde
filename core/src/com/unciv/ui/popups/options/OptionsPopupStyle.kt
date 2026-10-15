package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.NinePatch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.center
import com.unciv.ui.images.IconCircleGroup
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.UncivSlider
import kotlin.math.roundToInt

/** Local styles for the options panel; other checkboxes keep their existing appearance. */
internal object OptionsPopupStyle {
    val background = Color.valueOf("0D303B")
    val sidebar = Color.valueOf("103843")
    val sectionBackground = Color.valueOf("194650")
    val selectedNavigation = Color.valueOf("2C5560")
    val text = Color.valueOf("F0F4EE")
    val muted = Color.valueOf("B8CBCB")
    val accent = Color.valueOf("EACB8E")
    val divider = Color.valueOf("426670")

    /** Options use iOS points so their reading size is consistent on phones and tablets. */
    fun points(value: Float) = value * UiNavigation.minimumTouchSize() / 44f
    fun fontSize(value: Float) = points(value).roundToInt().coerceAtLeast(11)

    fun surface(color: Color): Drawable {
        val source = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", color)
        if (source !is NinePatchDrawable) return source
        val patch = NinePatch(source.patch)
        val edge = maxOf(patch.leftWidth, patch.rightWidth, patch.topHeight, patch.bottomHeight)
        if (edge > 0f) patch.scale(points(12f) / edge, points(12f) / edge)
        return NinePatchDrawable(patch).apply { minWidth = 0f; minHeight = 0f }
    }

    fun outlinedSurface(color: Color, edge: Color = divider): Drawable = object : BaseDrawable() {
        private val outside = surface(edge)
        private val inside = surface(color)
        init {
            leftWidth = points(12f); rightWidth = points(12f)
            topHeight = points(8f); bottomHeight = points(8f)
        }
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val inset = points(1f)
            outside.draw(batch, x, y, width, height)
            inside.draw(batch, x + inset, y + inset, width - inset * 2, height - inset * 2)
        }
    }

    private fun selectBackground(color: Color): Drawable {
        val panel = outlinedSurface(color)
        val arrow = ImageGetter.getDrawable("OtherIcons/ArrowRight").tint(muted) as TransformDrawable
        return object : BaseDrawable(panel) {
            init { rightWidth = points(36f) }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                panel.draw(batch, x, y, width, height)
                val size = points(12f)
                arrow.draw(batch, x + width - points(24f), y + (height - size) / 2f,
                    size / 2f, size / 2f, size, size, 1f, 1f, -90f)
            }
        }
    }

    fun enlargeText(actor: Actor, formFont: BitmapFont) {
        // Expander headings have their own hierarchy; only style their setting rows.
        if (actor is ExpanderTab) {
            enlargeText(actor.innerTable, formFont)
            return
        }
        when (actor) {
            is UncivSlider -> actor.children.filterIsInstance<IconCircleGroup>().forEach { stepButton ->
                // The clickable group owns the full target; the circle stays quiet inside it.
                val target = points(44f)
                stepButton.setSize(target, target)
                stepButton.circle.setSize(points(20f), points(20f))
                stepButton.circle.center(stepButton)
                stepButton.actor.center(stepButton)
                actor.getCell(stepButton).size(target).fill()
            }
            is TextButton -> if (actor.style.javaClass == TextButton.TextButtonStyle::class.java) {
                actor.style = TextButton.TextButtonStyle(actor.style).apply {
                    up = outlinedSurface(selectedNavigation)
                    over = outlinedSurface(selectedNavigation, muted)
                    down = outlinedSurface(sectionBackground, accent)
                    checked = down
                    disabled = outlinedSurface(background)
                    disabledFontColor = muted
                }
                actor.pad(points(8f), points(12f), points(8f), points(12f))
                (actor.parent as? Table)?.getCell(actor)?.minHeight(points(44f))
            }
            is Label -> {
                actor.setFontSize(fontSize(17f))
                when (actor.style.fontColor) {
                    ExpeditionBlueTheme.text -> actor.setFontColor(text)
                    ExpeditionBlueTheme.muted -> actor.setFontColor(muted)
                    ExpeditionBlueTheme.gold -> actor.setFontColor(accent)
                }
            }
            is SelectBox<*> -> if (actor.style.font !== formFont) {
                actor.style = SelectBox.SelectBoxStyle(actor.style).apply {
                    font = formFont
                    listStyle = ListStyle(listStyle).apply {
                        font = formFont
                        selection = surface(selectedNavigation).apply {
                            topHeight = points(14f)
                            bottomHeight = points(14f)
                        }
                    }
                    background = selectBackground(selectedNavigation)
                    backgroundOver = selectBackground(selectedNavigation)
                    backgroundOpen = selectBackground(sectionBackground)
                    backgroundDisabled = selectBackground(OptionsPopupStyle.background)
                }
            }
            is TextField -> if (actor.style.font !== formFont)
                actor.style = TextField.TextFieldStyle(actor.style).apply {
                    font = formFont
                    background = surface(selectedNavigation)
                    focusedBackground = outlinedSurface(selectedNavigation, accent)
                }
        }
        if (actor is Group) actor.children.forEach { enlargeText(it, formFont) }
    }

    fun selectedNavigationDrawable() = surface(selectedNavigation)

    fun rowDividerDrawable() = object : BaseDrawable() {
        private val line = BaseScreen.skin.newDrawable("Rectangle", divider.cpy().apply { a = 0.45f })
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val inset = points(12f)
            line.draw(batch, x + inset, y, (width - 2 * inset).coerceAtLeast(0f), points(0.5f))
        }
    }

    fun styleSwitch(checkbox: CheckBox) {
        checkbox.style = CheckBox.CheckBoxStyle(checkbox.style).apply {
            checkboxOff = switchDrawable(false)
            checkboxOn = switchDrawable(true)
            checkboxOver = checkboxOff
            checkboxOnOver = checkboxOn
            fontColor = text
            checkedFontColor = text
            checkedOverFontColor = text
            checkedDownFontColor = text
            up = rowDividerDrawable()
        }
        checkbox.label.wrap = true
        checkbox.clearChildren()
        checkbox.add(checkbox.label).minWidth(0f).growX().left().padRight(points(16f))
        checkbox.add(checkbox.image).size(points(51f), points(31f))
        checkbox.pad(points(6f), points(12f), points(6f), points(12f))
    }

    private fun switchDrawable(checked: Boolean) = object : BaseDrawable() {
        private val trackColor = if (checked) accent else Color.valueOf("587780")
        private val track = BaseScreen.skin.newDrawable("Rectangle", trackColor)
        private val trackEnd = BaseScreen.skin.newDrawable("Circle", trackColor)
        private val thumb = BaseScreen.skin.newDrawable("Circle",
            Color.WHITE)

        init { minWidth = points(51f); minHeight = points(31f) }

        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            track.draw(batch, x + height / 2f, y, width - height, height)
            trackEnd.draw(batch, x, y, height, height)
            trackEnd.draw(batch, x + width - height, y, height, height)
            val inset = points(2f)
            val size = height - 2 * inset
            thumb.draw(batch, if (checked) x + width - size - inset else x + inset, y + inset, size, size)
        }
    }
}
