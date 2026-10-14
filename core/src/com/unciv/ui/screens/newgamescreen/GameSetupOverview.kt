package com.unciv.ui.screens.newgamescreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.civilization.PlayerType
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen

enum class GameSetupSection(val caption: String) {
    Overview("Overview"), World("World"), Civilizations("Civilizations"), Rules("Game rules")
}

/** Shared navigation and summaries; the existing editors remain the source of configuration. */
object GameSetupOverview {
    private val accent get() = if (ExpeditionBlueTheme.enabled) ExpeditionBlueTheme.gold else Color.GOLD
    private val muted get() = if (ExpeditionBlueTheme.enabled) ExpeditionBlueTheme.muted else Color.LIGHT_GRAY

    fun contentWidth(width: Float, portrait: Boolean) = if (portrait) width else width - 160f

    fun navigation(width: Float, portrait: Boolean, selected: GameSetupSection,
                   multiplayer: Boolean = false, select: (GameSetupSection) -> Unit) = Table().apply {
        top()
        for (section in GameSetupSection.entries) {
            val caption = if (multiplayer && section == GameSetupSection.Overview) "Room" else section.caption
            val button = caption.toTextButton().apply {
                name = "setup-section-${section.name}"
                label.setWrap(true)
                if (ExpeditionBlueTheme.enabled) style = TextButton.TextButtonStyle(style).apply {
                    up = BaseScreen.skinStrings.getUiBackground("GameSetup/Navigation",
                        BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                        if (section == selected) ExpeditionBlueTheme.raised else ExpeditionBlueTheme.background)
                    fontColor = if (section == selected) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.muted
                }
                onClick { select(section) }
            }
            add(button).width(if (portrait) width / 4f else 144f)
                .minHeight(UiNavigation.minimumTouchSize()).padBottom(if (portrait) 0f else 8f)
            if (!portrait) row()
        }
    }

    fun layout(width: Float, portrait: Boolean, selected: GameSetupSection, content: Actor,
               multiplayer: Boolean = false, select: (GameSetupSection) -> Unit): Table {
        val navigation = navigation(width, portrait, selected, multiplayer, select)
        return Table().apply {
            top()
            add(navigation).top()
            if (portrait) row()
            add(content).width(contentWidth(width, portrait)).growY().top()
                .padLeft(if (portrait) 0f else 16f).padTop(if (portrait) 12f else 0f)
        }
    }

    fun card() = Table().apply {
        background = BaseScreen.skinStrings.getUiBackground("GameSetup/Card",
            BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
            if (ExpeditionBlueTheme.enabled) ExpeditionBlueTheme.panel else BaseScreen.skinStrings.skinConfig.baseColor)
        pad(18f)
        top()
    }

    fun choice(caption: String, selected: Boolean) = caption.toTextButton().apply {
        label.setWrap(true)
        style = TextButton.TextButtonStyle(style).apply {
            up = BaseScreen.skinStrings.getUiBackground("GameSetup/Choice",
                BaseScreen.skinStrings.roundedEdgeRectangleSmallShape,
                if (selected) ExpeditionBlueTheme.raised else ExpeditionBlueTheme.panel)
            fontColor = if (selected) accent else Color.WHITE
        }
    }

    fun field(title: String, control: Actor, width: Float) = Table().apply {
        add(title.toLabel(muted, 16).apply { setWrap(true) }).width(width).left().padBottom(8f).row()
        add(control).width(width).minHeight(UiNavigation.minimumTouchSize()).fillX()
    }

    fun heading(title: String, subtitle: String) = Table().apply {
        add(title.toLabel(accent, 28).apply { setWrap(true) }).minWidth(0f).growX().left().row()
        add(subtitle.toLabel(muted, 18).apply { setWrap(true) }).minWidth(0f).growX().left().padTop(6f)
    }

    fun summaryRow(title: String, value: String, compact: Boolean = false, action: (() -> Unit)? = null) = Table().apply {
        val labels = Table().apply {
            add(title.toLabel(muted, if (compact) 14 else 16).apply { setWrap(true) }).minWidth(0f).growX().left().row()
            add(value.toLabel(fontSize = if (compact) 18 else 20, hideIcons = true).apply { setWrap(true) })
                .minWidth(0f).growX().left().padTop(4f)
        }
        add(labels).minWidth(0f).growX().left()
        if (action != null) {
            add("›".toLabel(accent, 26)).padLeft(12f)
            onClick { action() }
        }
        pad(if (compact) 6f else 10f, 0f, if (compact) 6f else 10f, 0f)
    }

    fun rulesSummary(setup: GameSetupInfo, width: Float, edit: ((GameSetupSection) -> Unit)? = null) = card().apply {
        val parameters = setup.gameParameters
        defaults().width(width - 36f).growX().left()
        add(summaryRow("Map Options", mapDescription(setup), action = edit?.let { { it(GameSetupSection.World) } })).row()
        add(summaryRow("Game pace", "${parameters.difficulty.tr()} · ${parameters.speed.tr()}",
            action = edit?.let { { it(GameSetupSection.Rules) } })).row()
        add(summaryRow("Victory Conditions", victoryDescription(setup), action = edit?.let { { it(GameSetupSection.Rules) } })).row()
        add(summaryRow("Base Ruleset", parameters.baseRuleset.tr(), action = edit?.let { { it(GameSetupSection.Rules) } }))
    }

    fun mapDescription(setup: GameSetupInfo): String {
        val map = setup.mapParameters
        return "${map.name.ifEmpty { map.type }.tr()} · ${map.mapSize.name.tr()}"
    }

    fun victoryDescription(setup: GameSetupInfo) = setup.gameParameters.victoryTypes
        .joinToString(" / ") { it.tr() }.ifEmpty { "No victory conditions were selected!".tr() }

    fun playerDescription(setup: GameSetupInfo): String {
        val parameters = setup.gameParameters
        val civilization = parameters.players.firstOrNull { it.playerType == PlayerType.Human }?.chosenCiv.orEmpty().tr()
        val opponents = if (parameters.randomNumberOfPlayers)
            "${parameters.minNumberOfPlayers}–${parameters.maxNumberOfPlayers} ${"Civilizations".tr()}"
        else "[${parameters.players.count { it.playerType == PlayerType.AI }}] AI opponents".tr()
        return "$civilization · $opponents"
    }
}
