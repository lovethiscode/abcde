package com.unciv.ui.screens.overviewscreen

import com.unciv.ui.screens.overviewscreen.OverviewUi.pt
import com.unciv.ui.screens.overviewscreen.OverviewUi.font
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffersList
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.view.CivView

class TradesOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    val game = overviewScreen.game
    private val fixedContent = Table().apply {
        add().height(pt(8f))
    }
    override fun getFixedContent() = fixedContent

    init {
        top()
        defaults().padBottom(pt(1f))
        val diplomaciesWithPendingTrade = viewingPlayer.getCiv().diplomacy.values.filter { it.otherCiv.tradeRequests.any { it.requestingCiv == viewingPlayer.getCiv().civID } }
        if (diplomaciesWithPendingTrade.isNotEmpty())
            add("Pending trades".toLabel(fontSize = font(13))).left().padTop(pt(6f)).row()
        for (diplomacy in diplomaciesWithPendingTrade) {
            for (tradeRequest in diplomacy.otherCiv.tradeRequests.filter { it.requestingCiv == viewingPlayer.getCiv().civID })
                add(createTradeTable(tradeRequest.trade.reverse(), diplomacy.otherCiv)).width(overviewScreen.contentWidth).minHeight(pt(66f)).row()
        }

        val diplomaciesWithExistingTrade = viewingPlayer.getCiv().diplomacy.values.filter { it.trades.isNotEmpty() }
            .sortedWith { diplomacyManager1, diplomacyManager2 ->
                val d1OffersFromFirstTrade = diplomacyManager1.trades.first().ourOffers
                val d2OffersFromFirstTrade = diplomacyManager2.trades.first().ourOffers
                val d1MaxDuration = if (d1OffersFromFirstTrade.isEmpty()) 0 else d1OffersFromFirstTrade.maxByOrNull { it.duration }!!.duration
                val d2MaxDuration = if (d2OffersFromFirstTrade.isEmpty()) 0 else d2OffersFromFirstTrade.maxByOrNull { it.duration }!!.duration
                when {
                    d1MaxDuration > d2MaxDuration -> 1
                    d1MaxDuration == d2MaxDuration -> 0
                    else -> -1
                }
            }
        for (diplomacy in diplomaciesWithExistingTrade) {
            for (trade in diplomacy.trades)
                add(createTradeTable(trade, diplomacy.otherCiv)).width(overviewScreen.contentWidth).minHeight(pt(66f)).row()
        }
    }

    private fun createTradeTable(trade: Trade, otherCiv: Civilization) = Table().apply {
        background = OverviewUi.surface(OverviewUi.panel)
        pad(pt(10f), pt(16f), pt(10f), pt(16f))
        add(ImageGetter.getNationPortrait(otherCiv.nation, pt(28f))).size(pt(28f)).padRight(pt(12f))
        val details = Table().apply {
            add(otherCiv.civName.toLabel(fontSize = font(16), hideIcons = true)).colspan(2).left().padBottom(pt(5f)).row()
            add(createOffersTable("Our offers", trade.ourOffers)).growX().minWidth(0f).top().left().padRight(pt(24f))
            add(createOffersTable("Their offers", trade.theirOffers)).growX().minWidth(0f).top().left()
        }
        add(details).growX().minWidth(0f)
        width = overviewScreen.contentWidth
    }

    private fun createOffersTable(title: String, offers: TradeOffersList) = Table().apply {
        add(title.toLabel(fontColor = OverviewUi.muted, fontSize = font(13), hideIcons = true))
            .left().top().padRight(pt(12f)).minHeight(pt(22f))
        val entries = Table().apply {
            top().left()
            for (offer in offers) {
                add(OverviewUi.plainLabel(offer.copy(duration = -1).getOfferText(), 13).apply { wrap = true })
                    .growX().minWidth(0f).left().minHeight(pt(22f)).padBottom(pt(4f))
                if (offer.duration > 0)
                    add(OverviewUi.plainLabel("[${offer.duration}] turns", 12, OverviewUi.muted))
                        .right().top().padLeft(pt(12f)).minHeight(pt(22f)).padBottom(pt(4f))
                else add()
                row()
            }
        }
        add(entries).growX().minWidth(0f).left()
    }
}
