package com.unciv.ui.screens.diplomacyscreen

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.ConfirmPopup

/** Native A subpage. The rule engine remains the authority for both tribute choices. */
internal class TributeDemandTable(
    private val screen: DiplomacyScreen,
    private val cityState: Civilization
) : Table() {
    private val ui = DiplomacyScreenStyle.forScreen(screen)
    private val player = screen.viewingCiv
    private val functions = cityState.cityStateFunctions
    private val modifiers = functions.getTributeModifiers(player, requireWholeList = true)
    private val willingness = modifiers.values.sum()
    private val population = cityState.getCapital()?.population?.population ?: 0
    private val influence = cityState.getDiplomacyManager(player)!!.getInfluence().toInt()
    private val layout = DiplomacyScreenLayout(screen.stage.width / ui.unit, screen.stage.height / ui.unit)
    private val contentWidth = ui.px(layout.detailWidth - 36f)
    private val shortLandscape = !layout.narrow && layout.height < 500f

    init {
        name = "ui-tribute-content"
        top().left()
        val summary = Table()
        summary.add(ui.label("Tribute Willingness", TextRole.Control)).growX().left()
        summary.add(ui.label(signed(willingness), TextRole.SectionTitle, if (willingness < 0) ui.danger else ui.positive)).padLeft(ui.px(12f))
        add(summary).width(contentWidth).left().padBottom(ui.px(if (shortLandscape) 8f else 10f)).row()
        if (!shortLandscape) add(ui.label("Tribute lowers your Influence and may anger its protectors.", TextRole.Supporting, ui.muted, true))
            .width(contentWidth).left().padBottom(ui.px(20f)).row()
        val split = contentWidth / ui.unit >= 540f
        val column = if (split) (contentWidth - ui.px(28f)) / 2 else contentWidth
        val choices = choices(if (shortLandscape) contentWidth else column)
        val breakdown = breakdown(if (shortLandscape) contentWidth else column)
        val body = Table().apply { top().left() }
        // The short viewport puts decisions before detail; wide layouts keep both in view.
        body.add(choices).width(if (shortLandscape) contentWidth else column).top()
        if (split && !shortLandscape) body.add(breakdown).width(column).top().padLeft(ui.px(28f))
        else {
            body.row()
            body.add(breakdown)
                .width(if (shortLandscape) contentWidth else column).top().padTop(ui.px(24f))
        }
        add(body).width(contentWidth).top().left()
    }

    private fun separator(table: Table, width: Float) {
        table.add(Image(ui.surface(ExpeditionBlueTheme.border, 0f)))
            .width(width).height(ui.px(1f)).padBottom(ui.px(if (shortLandscape) 8f else 14f)).row()
    }

    private fun choices(width: Float) = Table().apply {
        top().left()
        if (shortLandscape) {
            val half = (width - ui.px(20f)) / 2
            add(choice(false, half)).width(half).top().left().padRight(ui.px(20f))
            add(choice(true, half)).width(half).top().left()
        } else {
            add(choice(false, width)).width(width).left().row()
            add(choice(true, width)).width(width).left().padTop(ui.px(22f)).row()
        }
    }

    private fun choice(worker: Boolean, width: Float) = Table().apply {
        top().left()
        separator(this, width)
        val cost = if (worker) 50 else 15
        val title = if (worker) "Worker tribute" else "Gold tribute"
        val gap = ui.px(if (shortLandscape) 4f else 8f)
        add(ui.label(title, TextRole.Control)).width(width).left().padBottom(gap).row()
        val requirement = if (worker) "Requires [30] willingness and a capital of size [4]" else "Requires [0] willingness"
        add(ui.label(requirement, TextRole.Supporting, ui.muted, true)).width(width).left().padBottom(gap).row()
        add(ui.label("Influence: [$influence] → [${influence - cost}]", TextRole.Supporting, ui.danger, true))
            .width(width).left().padBottom(gap).row()
        val reasons = ArrayList<String>()
        if (screen.isNotPlayersTurn()) reasons.add("Not your turn")
        if (player.isAtWarWith(cityState)) reasons.add("Cannot demand tribute while at war.")
        val threshold = if (worker) 30 else 0
        if (willingness < threshold) reasons.add("Need [${threshold - willingness}] more willingness")
        if (worker && population < 4) reasons.add("Capital population: [$population]/[4]")
        if (cityState.cities.isEmpty()) reasons.add("No Cities")
        val enabled = canDemand(worker)
        // A rule/mod may add a restriction not represented by the standard threshold copy.
        if (!enabled && reasons.isEmpty()) reasons.add("Unavailable")
        for (reason in reasons) add(ui.label(reason, TextRole.Supporting, ui.muted, true))
            .width(width).left().padBottom(gap).row()
        val action = if (worker) "Take worker (-50 Influence)" else "Take [${functions.goldGainedByTribute()}] gold (-15 Influence)"
        val button = ui.decorate(action.toTextButton(), primary = enabled).apply {
            name = if (worker) "ui-tribute-worker" else "ui-tribute-gold"
            isDisabled = !enabled
            label.setEllipsis(false)
            label.wrap = true
            label.setAlignment(Align.center)
            getCell(label).width((width - ui.px(24f)).coerceAtLeast(ui.px(40f)))
            onActivation { confirm(worker, action) }
        }
        add(button).width(width).minHeight(ui.px(44f)).padTop(ui.px(5f)).left().row()
    }

    private fun breakdown(width: Float) = Table().apply {
        top().left()
        separator(this, width)
        add(ui.label("Willingness breakdown", TextRole.Control)).width(width).left().padBottom(ui.px(14f)).row()
        val values = Table().apply { top().left() }
        for ((factor, value) in modifiers) {
            val number = ui.label(signed(value), TextRole.Supporting, if (value < 0) ui.danger else ui.positive)
            val valueWidth = maxOf(ui.px(46f), number.prefWidth)
            values.add(ui.label(factor, TextRole.Supporting, ui.muted, true)).width(width - valueWidth - ui.px(12f))
                .left().padBottom(ui.px(12f))
            values.add(number).width(valueWidth).right().top().padLeft(ui.px(12f)).padBottom(ui.px(12f)).row()
        }
        add(values).width(width).left().row()
    }

    private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

    private fun canDemand(worker: Boolean) = !screen.isNotPlayersTurn() &&
        !player.isAtWarWith(cityState) && cityState.cities.isNotEmpty() &&
        functions.getTributeWillingness(player, demandingWorker = worker) >= 0

    private fun confirm(worker: Boolean, action: String) {
        if (!canDemand(worker)) { refresh(); return }
        ConfirmPopup(screen, "{${cityState.civName}}\n{$action}\n{Tribute lowers your Influence and may anger its protectors.}", "Demand Tribute") {
            // Recheck after confirmation; a stale view must never bypass the rules.
            if (canDemand(worker)) {
                if (worker) functions.tributeWorker(player) else functions.tributeGold(player)
                screen.updateRightSide(cityState)
            } else refresh()
        }.open()
    }

    private fun refresh() = screen.showSubPage(cityState, TributeDemandTable(screen, cityState), "tribute")
}
