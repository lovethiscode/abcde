package com.unciv.ui.screens.cityscreen

import com.unciv.logic.city.GreatPersonPointsBreakdown
import com.unciv.ui.components.extensions.toStringSigned
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

class GreatPersonPointsBreakdownPopup(cityScreen: CityScreen, gppBreakdown: GreatPersonPointsBreakdown, greatPerson: String?) : Popup(cityScreen) {
    init {
        val lines = ArrayList<FormattedLine>()
        val headerText = "«GOLD»{${greatPerson ?: "Great person points"}}«» ({${cityScreen.cityView.name}})"
        val title = FormattedLine(headerText, header = 2, centered = true)
        if (ordinaryPresentation) {
            addDialogTitle(greatPerson ?: "Great person points", "{${cityScreen.cityView.name}} · {Great person points}")
            if (greatPerson != null) {
                val civ = cityScreen.cityView.viewingCiv()
                addFactRow("Current progress", "${civ.getGreatPersonPoints(greatPerson)} / ${civ.getPointsRequiredForGreatPerson(greatPerson)}")
                addFactRow("Per turn", gppBreakdown.sum()[greatPerson].toStringSigned())
            }
            addGoodSizedLabel("Point sources", com.unciv.ui.components.fonts.UiTypography.size(com.unciv.ui.components.fonts.TextRole.SectionTitle, popupUnit))
                .padTop(16f * popupUnit).row()
        }
        else if (UiNavigation.isIos) addTitleWithCloseButton(MarkupRenderer.render(listOf(title)))
        else lines += title
        if (!ordinaryPresentation) lines += FormattedLine(separator = true)

        fun addFormattedEntry(entry: GreatPersonPointsBreakdown.Entry, isPercentage: Boolean) {
            if (ordinaryPresentation) {
                val value = if (greatPerson == null) {
                    if (entry.isAllGP) (entry.counter.values.firstOrNull() ?: 0).toStringSigned() + if (isPercentage) "%" else ""
                    else entry.counter.entries.joinToString { it.value.toStringSigned() + (if (isPercentage) "%" else "") + " {${it.key}}" }
                } else {
                    val amount = entry.counter[greatPerson]
                    if (amount == 0) return
                    amount.toStringSigned() + if (isPercentage) "%" else ""
                }
                addFactRow("{${entry.source}}", value,
                    entry.pediaLink?.takeIf { it.isNotBlank() }?.let { link -> { cityScreen.openCivilopedia(link) } })
                return
            }
            val text = if (greatPerson == null) {
                // Popup shows all GP for a city - this will resolve the counters if necessary and dhow GP names from the keys
                entry.toString(isPercentage)
            } else {
                // Popup shows only a specific GP - check counters directly
                val amount = entry.counter[greatPerson]
                if (amount == 0) return
                // Formatter does not need the GP name as in all cases the one in the header is clear enough
                entry.toString(isPercentage, amount)
            }
            lines += FormattedLine(text, entry.pediaLink ?: "")
        }

        for (entry in gppBreakdown.basePoints)
            addFormattedEntry(entry, false)

        for (entry in gppBreakdown.percentBonuses)
            addFormattedEntry(entry, true)

        if (!ordinaryPresentation) add(MarkupRenderer.render(lines) {
            cityScreen.openCivilopedia(it)
        })

        if (ordinaryPresentation) styleOrdinaryButton(addButton("Return to city") { close() }.actor, primary = true)
        else if (!UiNavigation.isIos) addCloseButton()
        open(true)
    }

    private fun GreatPersonPointsBreakdown.Entry.toString(isPercentage: Boolean) =
        "{$source}: " +
        when {
            isAllGP -> (counter.values.firstOrNull() ?: 0).toStringSigned() + (if (isPercentage) "%" else "")
            isPercentage -> counter.entries.joinToString { it.value.toStringSigned() + "% {${it.key}}" }
            else -> counter.entries.joinToString { it.value.toStringSigned() + " {${it.key}}" }
        }

    private fun GreatPersonPointsBreakdown.Entry.toString(isPercentage: Boolean, amount: Int) =
        "{$source}: " +
            amount.toStringSigned() +
            (if (isPercentage) "%" else "")
}
