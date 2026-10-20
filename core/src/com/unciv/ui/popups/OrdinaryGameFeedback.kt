package com.unciv.ui.popups

import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Notification
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.ui.screens.worldscreen.WorldScreen

/** Read-only results of completed actions. They never intercept the map or require confirmation. */
object OrdinaryGameFeedback {
    fun enabled(world: WorldScreen) = UiNavigation.isIos && world.gameInfo.campaign == null && world.gameInfo.endless == null

    fun puppet(world: WorldScreen, city: City) {
        if (!enabled(world)) return
        val lines = arrayListOf("You have no control over the the production of puppeted cities.",
            "Puppeted cities also generate 25% less Science and Culture.")
        if (!city.civ.hasUnique(UniqueType.MayNotAnnexCities)) lines += "A puppeted city can be annexed at any time."
        card(world, "[${city.name}] is now a puppet city", lines)
    }

    fun razing(world: WorldScreen, city: City) {
        if (!enabled(world)) return
        card(world, "[${city.name}] is being razed", listOf(
            if (city.isPuppet) "Razing the city puppets it, and starts burning the city to the ground."
            else "Razing the city annexes it, and starts burning the city to the ground.",
            "Current population: [${city.population.population}]",
            "The population will gradually dwindle until the city is destroyed."))
    }

    fun liberated(world: WorldScreen, cityName: String, owner: String) {
        if (enabled(world)) ToastPopup("[${cityName}] has been liberated and returned to [${owner}].", world, 8000)
    }

    fun keptCivilian(world: WorldScreen, unitName: String, owner: String) {
        if (enabled(world)) ToastPopup("[${unitName}] has joined [${owner}]. You can command it on the map.", world, 8000)
    }

    fun razed(world: WorldScreen, notification: Notification) {
        if (!enabled(world)) return
        card(world, notification.text, listOf("The city's population reached zero and the city was removed from the map."),
            "Locate the former city" to { notification.execute(world); world.shouldUpdate = true })
    }

    private fun card(world: WorldScreen, title: String, lines: List<String>, action: Pair<String, () -> Unit>? = null) {
        world.stage.actors.filterIsInstance<ResultCard>().forEach { it.remove() }
        world.stage.addActor(ResultCard(world, title, lines, action))
    }

    private class ResultCard(private val world: WorldScreen, private val title: String,
        private val lines: List<String>, private val action: Pair<String, () -> Unit>?) : Table() {
        private var signature = ""
        init { name = "ui-ordinary-result-feedback" }

        override fun act(delta: Float) {
            super.act(delta)
            // A forced decision always has priority over passive feedback.
            isVisible = !world.hasOpenPopups()
            if (isVisible) toFront()
            val unit = UiTypography.unitsPerPoint(world.stage)
            val safe = (world.stage.viewport as? SafeAreaViewport)?.safeAreaBoundsInWorld
                ?: Rectangle(0f, 0f, world.stage.width, world.stage.height)
            val current = "$safe:$unit"
            if (signature == current) return
            signature = current
            rebuild(safe, unit)
        }

        private fun rebuild(safe: Rectangle, unit: Float) {
            clearChildren()
            val ui = OrdinaryPopupStyle(unit)
            background = ui.surface(ui.background, 16f)
            val panelWidth = minOf(420f * unit, safe.width - 32f * unit).coerceAtLeast(1f)
            val contentWidth = (panelWidth - 32f * unit).coerceAtLeast(1f)
            pad(16f * unit)
            val heading = Table().apply {
                add(title.toLabel(TextRole.SectionTitle, unit = unit).apply { wrap = true; setAlignment(Align.left) })
                    .growX().minWidth(0f)
                add(UiNavigation.dismissButton(unit).apply {
                    name = "ui-result-feedback-close"
                    onActivation { this@ResultCard.remove() }
                }).size(44f * unit).top().padLeft(12f * unit)
            }
            add(heading).width(contentWidth).row()
            val scrollbar = BaseScreen.skin.get(ScrollPane.ScrollPaneStyle::class.java).vScroll?.minWidth ?: 0f
            val bodyWidth = (contentWidth - scrollbar - 12f * unit).coerceAtLeast(1f)
            val body = Table().apply {
                padLeft(4f * unit).padRight(4f * unit)
                for (line in lines) add(line.toLabel(TextRole.Body, unit = unit).apply {
                    wrap = true; setAlignment(Align.left)
                }).width(bodyWidth).padTop(10f * unit).left().row()
            }
            val scroll = AutoScrollPane(body, BaseScreen.skin).apply {
                setScrollingDisabled(true, false); setOverscroll(false, false)
                fadeScrollBars = false
            }
            val actionHeight = if (action == null) 0f else 56f * unit
            add(scroll).width(contentWidth).maxHeight((safe.height * .6f - heading.prefHeight - actionHeight - 32f * unit)
                .coerceAtLeast(44f * unit)).fill().row()
            action?.let { (text, execute) ->
                val button = text.toTextButton().apply {
                    style = ui.button()
                    label.setFontSize(UiTypography.size(TextRole.Control, unit))
                    label.wrap = true
                    labelCell.minWidth(0f).growX()
                    onActivation { this@ResultCard.remove(); execute() }
                }
                add(button).width(contentWidth).minHeight(44f * unit).padTop(12f * unit).row()
            }
            pack()
            x = if (safe.width / unit <= 600f) safe.x + (safe.width - width) / 2 else safe.x + 16f * unit
            y = safe.y + 16f * unit
        }
    }
}
