package com.unciv.ui.screens.worldscreen

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.pad
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.diplomacyscreen.LeaderIntroTable
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import com.unciv.ui.components.widgets.AutoScrollPane as ScrollPane

/* TODO:
    different Notification wording for peace treaties?
    Let Notification jump to Diplomacy.trade with empty offers (accepted)
        or a clone of the rejected offer (denied) ...?
*/

/**
 * [Popup] communicating trade offers of others to the player.
 *
 * Called in [WorldScreen].update, which checks if there are any in viewingCiv.tradeRequests.
 *
 * @param worldScreen The parent screen
 */
class TradePopup(worldScreen: WorldScreen) : Popup(worldScreen) {
    val viewingCiv = worldScreen.selectedGameView.civView.getCiv()
    val tradeRequest = viewingCiv.tradeRequests.first()

    init {
        val requestingCiv = worldScreen.gameInfo.getCivilization(tradeRequest.requestingCiv)
        val nation = requestingCiv.nation
        val trade = tradeRequest.trade
        if (ordinaryPresentation) {
            ordinaryPreferredWidth = 820f
            addDialogTitle("[${nation.name}]'s trade offer", nation.leaderName, dismissible = false)
            addGoodSizedLabel(nation.tradeRequest).row()
        }


        val ourResources = viewingCiv.getCivResourcesByName()

        val leaderIntroTable = LeaderIntroTable(requestingCiv, popupStage = if (ordinaryPresentation) stageToShowOn else null)
        if (!ordinaryPresentation) {
            add(leaderIntroTable)
            addSeparator()
        }

        val tradeOffersTable = Table().apply { defaults().pad(10f) }
        tradeOffersTable.add("[${nation.name}]'s trade offer".toLabel(TextRole.Body))
        // empty column to separate offers columns better
        tradeOffersTable.add().pad(0f, 15f)
        tradeOffersTable.add("Our trade offer".toLabel(TextRole.Body))
        tradeOffersTable.row()

        
        @Readonly
        fun getOfferText(offer:TradeOffer): String {
            var tradeText = offer.getOfferText()
            if (offer.type == TradeOfferType.Luxury_Resource || offer.type == TradeOfferType.Strategic_Resource)
                tradeText += "\n" + "Owned by you: [${ourResources[offer.name]}]".tr()
            return tradeText
        }

        for (i in 0..max(trade.theirOffers.lastIndex, trade.ourOffers.lastIndex)) {
            if (trade.theirOffers.lastIndex < i) tradeOffersTable.add()
            else tradeOffersTable.add(getOfferText(trade.theirOffers[i]).toLabel(TextRole.Body))
            tradeOffersTable.add()
            if (trade.ourOffers.lastIndex < i) tradeOffersTable.add()
            else tradeOffersTable.add(getOfferText(trade.ourOffers[i]).toLabel(TextRole.Body))
            tradeOffersTable.row()
        }
        tradeOffersTable.pack()

        val scrollHeight = min(tradeOffersTable.height, worldScreen.stage.height/2)
        if (ordinaryPresentation) {
            val sections = listOf("[${nation.name}]'s trade offer" to trade.theirOffers, "Our trade offer" to trade.ourOffers).map { (heading, list) ->
                Table().apply {
                    defaults().left().minWidth(0f)
                    add(heading.toLabel(TextRole.SectionTitle, unit = popupUnit).apply { wrap = true }).growX().row()
                    for (offer in list) add(getOfferText(offer).toLabel(TextRole.Body, unit = popupUnit).apply { wrap = true })
                        .growX().padTop(12f * popupUnit).row()
                }
            }
            val columns = object : Table() {
                private var previousWidth = -1f
                override fun layout() {
                    if (previousWidth != width) {
                        previousWidth = width
                        clearChildren()
                        val sideBySide = width / popupUnit > 620f
                        val columnWidth = if (sideBySide) (width - 24f * popupUnit) / 2 else width
                        for ((index, section) in sections.withIndex()) {
                            for (cell in section.cells) cell.width(columnWidth)
                            if (!sideBySide && index > 0) row()
                            add(section).width(columnWidth).top().padLeft(if (sideBySide && index > 0) 24f * popupUnit else 0f)
                                .padTop(if (!sideBySide && index > 0) 20f * popupUnit else 0f)
                        }
                    }
                    super.layout()
                }
                override fun getPrefWidth() = popupContentWidth
            }
            // A concrete width makes the first wrapped measurement deterministic.
            columns.setWidth(popupContentWidth)
            columns.validate()
            add(columns).width(popupContentWidth).row()
        } else add(ScrollPane(tradeOffersTable)).height(scrollHeight).row()

        if (!ordinaryPresentation) addSeparator(Color.DARK_GRAY, height = 1f)

        // Starting playback here assumes the TradePopup is shown immediately
        UncivGame.Current.musicController.playVoice("${requestingCiv.civName}.tradeRequest")
        if (!ordinaryPresentation) addGoodSizedLabel(nation.tradeRequest).pad(15f).row()

        val accept = addButton(if (ordinaryPresentation) "Accept offer" else "Sounds good!", 'y') {
            val tradeLogic = TradeLogic(viewingCiv, requestingCiv)
            tradeLogic.currentTrade.set(trade)
            tradeLogic.acceptTrade()
            close()
            TradeThanksPopup(leaderIntroTable, worldScreen, requestingCiv)
            requestingCiv.addNotification("[${viewingCiv.civName}] has accepted your trade request", NotificationCategory.Trade, viewingCiv.civName, NotificationIcon.Trade)
        }.apply { actor.name = "ui-trade-accept"; row() }

        val reject = addButton(if (ordinaryPresentation) "Reject offer" else "Not this time.", 'n') {
            tradeRequest.decline(viewingCiv)
            close()
            requestingCiv.addNotification("[${viewingCiv.civName}] has denied your trade request", NotificationCategory.Trade, viewingCiv.civName, NotificationIcon.Trade)
            worldScreen.shouldUpdate = true
        }.apply { actor.name = "ui-trade-reject"; row() }

        val counter = addButton(if (ordinaryPresentation) "Continue negotiating" else "How about something else...", 'e') {
            close()
            worldScreen.game.pushScreen {
                DiplomacyScreen(
                    worldScreen.selectedGameView.civView,
                    worldScreen.selectedGameView.getForeignCivView(requestingCiv),
                    trade
                )
            }
            worldScreen.shouldUpdate = true
        }.apply { actor.name = "ui-trade-counter"; row() }
        if (ordinaryPresentation) {
            val actions = listOf(reject.actor, counter.actor, accept.actor)
            bottomTable.clearChildren()
            actions.forEach { bottomTable.add(it) }
            // Clearing a Table returns its Cells to the pool; keep actor references.
            styleOrdinaryButton(actions.last(), primary = true)
        }
    }

    override fun close() {
        viewingCiv.tradeRequests.remove(tradeRequest)
        super.close()
    }

    class TradeThanksPopup(leaderIntroTable: LeaderIntroTable, worldScreen: WorldScreen,
                          counterparty: com.unciv.logic.civilization.Civilization) : Popup(worldScreen) {
        init {
            if (ordinaryPresentation) addDialogTitle("Trade agreed", "{${counterparty.civName}} · {${counterparty.nation.leaderName}}",
                dismissible = false)
            else { add(leaderIntroTable); addSeparator().padBottom(15f) }
            addGoodSizedLabel("Excellent!").row()
            val farewell = addCloseButton("Farewell.", KeyCharAndCode.SPACE) {
                worldScreen.shouldUpdate = true
                // in all cases, worldScreen.shouldUpdate should be set to true when we remove the last of the popups
                // in order for the next trade to appear immediately
            }.actor.apply { name = "ui-trade-thanks" }
            if (ordinaryPresentation) styleOrdinaryButton(farewell, primary = true)
            open()
        }
    }
}
