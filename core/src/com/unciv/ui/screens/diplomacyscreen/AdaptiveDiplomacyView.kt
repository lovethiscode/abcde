package com.unciv.ui.screens.diplomacyscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.CityStateFunctions
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup

/** iOS realization of approved A. All actions are supplied by the existing rule-aware factories. */
internal class AdaptiveDiplomacyView(private val screen: DiplomacyScreen, private val back: TextButton) {
    private val ui = DiplomacyScreenStyle.forScreen(screen)
    private val layout = DiplomacyScreenLayout(screen.stage.width/ui.unit, screen.stage.height/ui.unit)
    private val root = Table()
    var selected: Civilization? = null
    var filter = "All"
    val detailWidth get() = ui.px(layout.detailWidth)
    private val player get() = screen.viewingCiv
    private val detail get() = screen.rightSideTable

    fun mount() {
        root.name = "diplomacy-a-root"; root.setFillParent(true)
        root.background = ui.surface(ui.background, 0f)
        screen.stage.addActor(root)
        shell()
        if (player.diplomacyFunctions.getKnownCivsSorted().none())
            detail.add(ui.label("No known civilizations",16,ui.muted,true)).pad(ui.px(24f)).grow()
    }

    private fun shell() {
        root.clearChildren(); root.pad(ui.px(layout.padding))
        val header = Table()
        UiNavigation.styleBackButton(back)
        header.add(back).height(ui.px(44f)).padRight(ui.px(12f))
        header.add(ui.label("Diplomacy", 21)).left().growX()
        root.add(header).growX().height(ui.px(44f)).padBottom(ui.px(12f)).row()
        val body = Table()
        if (layout.narrow) {
            body.add(roster()).growX().height(ui.px(72f)).padBottom(ui.px(12f)).row()
            body.add(detail).grow().minSize(0f)
        } else {
            body.add(roster()).width(ui.px(layout.rosterWidth)).growY().padRight(ui.px(layout.gap))
            body.add(detail).grow().minSize(0f)
        }
        root.add(body).grow().minSize(0f)
        detail.background = ui.surface(ui.panel)
        root.invalidateHierarchy(); root.validate()
        selected?.let { civ ->
            val scroll=root.findActor<AutoScrollPane>("ui-diplomacy-roster-scroll")
            val row=root.findActor<Table>("ui-diplomacy-civ:${civ.civID}")
            if(scroll!=null&&row!=null){scroll.scrollTo(row.x,row.y,row.width,row.height);scroll.updateVisualScroll()}
        }
    }

    private fun roster(): Table {
        val roster = Table().apply { background = if (layout.narrow) null else ui.surface(ui.panel); top() }
        val known = player.diplomacyFunctions.getKnownCivsSorted().toList()
        if (!layout.narrow) {
            val title = Table()
            title.add(ui.label("Known civilizations", 14)).left().growX()
            title.add(ui.label(known.size.toString(), 12, ui.muted))
            roster.add(title).growX().pad(ui.px(12f)).row()
            val filters = Table()
            for (key in listOf("All", "Civilizations", "City-States")) {
                val button = ui.decorate(key.toTextButton(), ghost = filter != key)
                button.name = "ui-diplomacy-filter:$key"
                button.onActivation { filter = key; shell() }
                filters.add(button).growX().minWidth(0f).height(ui.px(44f)).padRight(ui.px(4f))
            }
            roster.add(filters).growX().pad(0f, ui.px(8f), ui.px(8f), ui.px(4f)).row()
        }
        val entries = Table().apply { top().left() }
        for (civ in known.filter { layout.narrow || filter == "All" || it.isCityState == (filter == "City-States") }) {
            val row = Table().apply {
                name = "ui-diplomacy-civ:${civ.civID}"
                if (selected == civ) background = ui.surface(ui.raised, 8f)
                pad(ui.px(8f))
                val icon = ImageGetter.getNationPortrait(civ.nation, ui.px(32f))
                add(icon).size(ui.px(38f)).padRight(ui.px(10f))
                val copy = Table()
                val nameLabel = ui.label(civ.civName, 14, hideIcons = true).apply { setEllipsis(true) }
                copy.add(nameLabel).growX().minWidth(0f).left().row()
                val dm=civ.getDiplomacyManager(player)!!
                val relationship = if(civ.isHuman()&&player.isHuman())screen.getHumanRelationshipText(dm) else dm.relationshipLevel().name
                copy.add(ui.label("{${if (civ.isCityState) "City-State" else "Civilization"}} · {$relationship}", 12, ui.muted).apply { setEllipsis(true) })
                    .left().growX().minWidth(0f).padTop(ui.px(3f))
                add(copy).growX().minWidth(0f)
                if (!layout.narrow && selected == civ) add(ImageGetter.getImage("OtherIcons/Checkmark").apply { color = ui.gold })
                    .size(ui.px(14f)).padLeft(ui.px(4f))
                onClick { screen.updateRightSide(civ) }
            }
            if (layout.narrow) entries.add(row).width(ui.px(166f)).height(ui.px(66f)).padRight(ui.px(8f))
            else entries.add(row).growX().minHeight(ui.px(if (layout.wide) 80f else 66f)).padBottom(ui.px(4f)).row()
        }
        val scroll = AutoScrollPane(entries).apply {
            name = "ui-diplomacy-roster-scroll"
            setScrollingDisabled(!layout.narrow, layout.narrow)
        }
        roster.add(scroll).grow().minSize(0f).pad(if (layout.narrow) 0f else ui.px(8f)).row()
        return roster
    }

    fun show(civ: Civilization) {
        selected = civ; detail.clearChildren(); shell()
        if (civ.isCityState) civ.cityStateFunctions.updateAllyCivForCityState()
        identity(civ)
        val column = ui.px(layout.columnWidth.coerceAtLeast(80f))
        val contents = Table().apply { top(); defaults().top() }
        val left = Table().apply { top() }
        left.add(relationship(civ, column)).width(column).top().row()
        val benefits = if (civ.isCityState) benefits(civ, column) else agreements(civ, column)
        if (layout.narrow) {
            left.add(benefits).width(column).padTop(ui.px(18f)).row()
            if (civ.isCityState) left.add(quests(civ, column)).width(column).padTop(ui.px(18f)).row()
            contents.add(left).width(column).top()
        } else {
            if (civ.isCityState) left.add(quests(civ, column)).width(column).padTop(ui.px(18f)).row()
            contents.add(left).width(column).padRight(ui.px(20f)).top()
            contents.add(benefits).width(column).top()
        }
        val scroll = AutoScrollPane(contents).apply { name = "ui-diplomacy-detail-scroll"; setScrollingDisabled(true, false) }
        detail.add(scroll).grow().minSize(0f).pad(0f, ui.px(layout.contentPadding), ui.px(12f), ui.px(layout.contentPadding)).row()
        actionBar(civ)
        detail.invalidateHierarchy(); root.validate()
    }

    private fun identity(civ: Civilization) {
        val header = Table()
        header.add(ImageGetter.getNationPortrait(civ.nation, ui.px(if (layout.wide) 52f else 42f)))
            .size(ui.px(if (layout.wide) 60f else 50f)).padRight(ui.px(14f))
        val text = Table()
        text.add(ui.label(civ.civName, if (layout.wide) 30 else 25, wrap = true, hideIcons = true)).growX().minWidth(0f).left().row()
        val subtitle = if (civ.isCityState) "{City-State} · {${civ.cityStateType.name}} · {${civ.cityStatePersonality}}"
            else "{Civilization} · {${civ.nation.leaderName}}"
        text.add(ui.label(subtitle, 12, ui.muted, true)).growX().left().padTop(ui.px(5f))
        header.add(text).growX().minWidth(0f)
        header.add(ui.label(if (civ.isAtWarWith(player)) "War" else "Peace", 12,
            if (civ.isAtWarWith(player)) ui.danger else ui.positive)).padLeft(ui.px(8f))
        detail.add(header).growX().pad(ui.px(18f), ui.px(layout.contentPadding), ui.px(14f), ui.px(layout.contentPadding)).row()
    }

    private fun section(title: String, width: Float): Table = Table().apply {
        top().left()
        add(ImageGetter.getWhiteDotDrawable().tint(com.unciv.models.skins.ExpeditionBlueTheme.border).let { com.badlogic.gdx.scenes.scene2d.ui.Image(it) })
            .width(width).height(ui.px(1f)).padBottom(ui.px(14f)).row()
        add(ui.label(title, 16)).width(width).left().padBottom(ui.px(10f)).row()
    }
    private fun Table.line(text: String, width: Float, color: Color = ui.muted, size: Int = 13) {
        add(ui.label(text, size, color, true)).width(width).left().padBottom(ui.px(8f)).row()
    }

    private fun relationship(civ: Civilization, width: Float): Table {
        val dm = civ.getDiplomacyManager(player)!!
        val level = dm.relationshipLevel()
        val value = if (civ.isCityState) dm.getInfluence().toInt() else dm.opinionOfOtherCiv().toInt()
        val table = section("Our relationship", width)
        val summary = Table()
        summary.add(ui.label(if(civ.isHuman()&&player.isHuman())screen.getHumanRelationshipText(dm) else level.name, 16)).left().growX()
        if(!civ.isHuman())summary.add(ui.label((if (value > 0) "+" else "")+value, 22, if (value < 0) ui.danger else ui.positive))
        table.add(summary).width(width).padBottom(ui.px(12f)).row()
        if (civ.isCityState) {
            val fraction = DiplomacyScreenLayout.influencePosition(dm.getInfluence())
            val bar = object : Actor() {
                override fun draw(batch: com.badlogic.gdx.graphics.g2d.Batch, parentAlpha: Float) {
                    ui.surface(ui.background, 2f).draw(batch,x,y,this.width,height)
                    ui.surface(ui.positive,2f).draw(batch,x,y,this.width*fraction,height)
                    ui.surface(ui.text,0f).draw(batch,x+(this.width-ui.px(2f))*fraction,y-ui.px(2f),ui.px(2f),height+ui.px(4f))
                }
            }.apply { name = "ui-diplomacy-influence:$value" }
            table.add(bar).width(width).height(ui.px(7f)).padBottom(ui.px(8f)).row()
            val ticks = object:com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup(){
                override fun layout(){
                    for((index,actor) in children.withIndex()){
                        val label=actor as Label;label.pack()
                        label.setPosition((width*index/3f-label.width/2f).coerceIn(0f,(width-label.width).coerceAtLeast(0f)),0f)
                    }
                }
            }
            for (tick in listOf("−30", "{Neutral} 0", "{Friend} 30", "{Ally} 60+")) ticks.addActor(ui.label(tick,11,ui.muted))
            table.add(ticks).width(width).height(ui.px(16f)).padBottom(ui.px(12f)).row()
            if (value < 30 && !civ.isAtWarWith(player)) table.line("Reach 30 for friendship.",width)
            else if (civ.allyCiv != player && !civ.isAtWarWith(player)) table.line("Reach highest influence above 60 for alliance.",width)
            if (dm.relationshipIgnoreAfraid() >= RelationshipLevel.Friend) {
                val turns = dm.getTurnsToRelationshipChange()
                if (turns != 0) table.line("Relationship changes in another [$turns] turns", width)
            }
            civ.allyCiv?.let { ally ->
                val name = if (player.knows(ally) || ally == player) ally.civName else "Unknown civilization"
                table.line("Ally: [$name] with [${civ.getDiplomacyManager(ally)!!.getInfluence().toInt()}] Influence",width)
            }
            val protectors = civ.cityStateFunctions.getProtectorCivs().map {
                if (player.knows(it) || it == player) it.civName.tr() else "Unknown civilization".tr()
            }
            if (protectors.isNotEmpty()) table.line("{Protected by}: "+protectors.joinToString(", "), width)
        } else {
            val major = MajorCivDiplomacyTable(screen)
            val modifiers = if (civ.isHuman()) screen.getHumanRelationshipTable(dm) else major.getDiplomacyModifiersTable(dm)
            fitContent(modifiers,width)
            table.add(modifiers).width(width).left().row()
            major.getPromisesTable(player.getDiplomacyManager(civ)!!,dm)?.let {
                fitContent(it,width);table.add(it).width(width).left().padTop(ui.px(12f)).row()
            }
        }
        return table
    }

    private fun benefits(civ: Civilization, width: Float): Table {
        val table = section("City-State bonuses", width)
        val relation = civ.getDiplomacyManager(player)!!.relationshipIgnoreAfraid()
        for ((level,title) in listOf(RelationshipLevel.Friend to "When Friends:", RelationshipLevel.Ally to "When Allies:")) {
            table.line(title,width,ui.muted,12)
            for (bonus in CityStateFunctions.getCityStateBonuses(civ.cityStateType,level).filterNot { it.isHiddenToUsers() }) {
                val active = relation >= level && bonus.conditionalsApply(GameContext(player))
                table.line(bonus.getDisplayText(),width,if(active) ui.positive else ui.text,14)
            }
            table.add().height(ui.px(6f)).row()
        }
        for (resource in civ.cityStateFunctions.getCityStateResourcesForAlly().filter { it.resource.resourceType != ResourceType.Bonus }) {
            val row = Table()
            row.add(ImageGetter.getResourcePortrait(resource.resource.name,ui.px(18f))).size(ui.px(18f)).padRight(ui.px(8f))
            row.add(ui.label("{${resource.resource.name}} ×${resource.amount}",13,ui.muted)).growX().left()
            row.onClick { screen.openCivilopedia(resource.resource.makeLink()) }
            table.add(row).width(width).minHeight(ui.px(44f)).row()
        }
        civ.cityStateUniqueUnit?.let { unit ->
            val tech = player.gameInfo.ruleset.units[unit]!!.requiredTechs().joinToString(" and ")
            table.line("[${civ.civName}] can provide [$unit] after researching [$tech].",width)
        }
        return table
    }

    private fun agreements(civ: Civilization, width: Float): Table {
        val table = section("Agreements and trade",width)
        val dm = player.getDiplomacyManager(civ)!!
        for ((flag,caption) in listOf(com.unciv.logic.civilization.diplomacy.DiplomacyFlags.DeclarationOfFriendship to "Declaration of Friendship",
            com.unciv.logic.civilization.diplomacy.DiplomacyFlags.Denunciation to "Denounce")) {
            if (dm.hasFlag(flag)) table.line("{$caption} · ${dm.getFlag(flag)} {turns}",width,ui.text,14)
        }
        if (dm.trades.isEmpty()) table.line("No active trades",width)
        for (trade in dm.trades) {
            for (offer in trade.ourOffers) table.line("{Our offer}: ${offer.getOfferText()}",width)
            for (offer in trade.theirOffers) table.line("{Their offer}: ${offer.getOfferText()}",width)
        }
        return table
    }

    private fun quests(civ: Civilization, width: Float): Table {
        val table = section("City-State quests",width)
        val quests = CityStateDiplomacyTable(screen).getQuests(civ)
        if (quests.isEmpty()) table.line("No active quests",width)
        for (quest in quests) { fitContent(quest,width);table.add(quest).width(width).left().padBottom(ui.px(12f)).row() }
        return table
    }

    private fun actionBar(civ: Civilization) {
        val actions = if(civ.isCityState) CityStateDiplomacyTable(screen).getDiplomacyActions(civ)
            else MajorCivDiplomacyTable(screen).getDiplomacyActions(civ)
        if (actions.isEmpty()) return
        val primary = if (civ.isAtWarWith(player))
            actions.firstOrNull { it.name?.startsWith("ui-negotiate-peace:") == true } ?: actions.first()
        else actions.first()
        val secondary = actions.drop(1).firstOrNull { button ->
            listOf("Pledge to protect","Revoke Protection","Demands").any { button.text.toString() == it.tr() }
        }
        val map = actions.firstOrNull { it.name == "ui-diplomacy-map" }
        val bar = Table()
        val isGift = civ.isCityState && !civ.isAtWarWith(player)
        val primaryButton = ui.decorate(primary,true,icon=if(isGift)DiplomacyIcon.Gift else DiplomacyIcon.Trade)
        if (isGift) primaryButton.style.apply {
            checked = up
            disabled = up
            checkedFontColor = fontColor
            disabledFontColor = fontColor
        }
        val secondaryButton = secondary?.let { ui.decorate(it) }
        val more = ui.decorate("More".toTextButton(),icon=if(layout.narrow)null else DiplomacyIcon.Down)
        val moreWidth = if (layout.narrow) maxOf(68f,more.prefWidth/ui.unit) else 84f
        val primaryWidth = if (layout.narrow) maxOf(116f,primaryButton.prefWidth/ui.unit) else if(layout.wide)160f else 116f
        val secondaryWidth = secondaryButton?.let { maxOf(44f,it.prefWidth/ui.unit) } ?: 0f
        val visibleSecondary = secondaryButton?.takeIf {
            !layout.narrow || primaryWidth+secondaryWidth+moreWidth+16f <= layout.detailWidth-32f
        }
        val primaryCell = bar.add(primaryButton).height(ui.px(44f)).padRight(ui.px(8f))
        if (layout.narrow) primaryCell.growX().minWidth(ui.px(primaryWidth)) else primaryCell.width(ui.px(primaryWidth))
        if (visibleSecondary != null) {
            val secondaryCell = bar.add(visibleSecondary).height(ui.px(44f)).padRight(ui.px(8f))
            if (layout.narrow) secondaryCell.width(ui.px(secondaryWidth)) else secondaryCell.minWidth(0f).maxWidth(ui.px(130f))
        }
        if (!layout.narrow && layout.detailWidth > 550f && map != null)
            bar.add(ui.decorate(map,ghost=true,icon=DiplomacyIcon.Map)).height(ui.px(44f)).padRight(ui.px(8f))
        if (!layout.narrow) bar.add().growX()
        more.name = "ui-diplomacy-more"
        more.onActivation {
            val popup = Popup(screen)
            popup.add(ui.label("Diplomatic actions",18)).padBottom(ui.px(12f)).row()
            for (button in actions.filter { it != primary && it != visibleSecondary }.sortedBy { it.name?.startsWith("ui-declare-war:")==true || it.name=="ui-diplomacy-denounce" }) {
                popup.add(ui.decorate(button)).width(ui.px(minOf(320f,layout.detailWidth-24f))).minHeight(ui.px(44f)).padBottom(ui.px(8f)).row()
                button.onActivation { popup.close() }
            }
            popup.addCloseButton(); popup.open()
        }
        bar.add(more).width(ui.px(moreWidth)).height(ui.px(44f))
        detail.add(com.badlogic.gdx.scenes.scene2d.ui.Image(ui.surface(com.unciv.models.skins.ExpeditionBlueTheme.border,0f)))
            .growX().height(ui.px(1f)).row()
        detail.add(bar).growX().pad(ui.px(10f),ui.px(16f),ui.px(12f),ui.px(16f)).row()
    }

    fun showSubPage(civ: Civilization, content: Actor, route: String, scroll: Boolean = true) {
        selected=civ; detail.clearChildren(); shell(); identity(civ)
        val title=when(route){"gift"->"Give a Gift";"tribute"->"Demand Tribute";"improvement"->"Gift Improvement";"demands"->"Demands";"trade"->"Trade";else->"Diplomacy"}
        if (route != "trade") detail.add(ui.label(title,18)).left().pad(ui.px(8f),ui.px(18f),ui.px(12f),ui.px(18f)).row()
        if(route!="trade" && route!="tribute")fitContent(content,detailWidth-ui.px(40f))
        val actor = if(scroll) AutoScrollPane(content).apply { setScrollingDisabled(true,false) } else content
        detail.add(actor).grow().minSize(0f).pad(ui.px(8f),ui.px(18f),ui.px(18f),ui.px(18f)).row()
    }

    private fun fitContent(actor: Actor, width: Float) {
        if(actor is Table) for(cell in actor.cells) {
            val child=cell.actor
            if(child is Label) child.setFontSize(ui.font(14))
            if(child is Label) { child.wrap=true; child.setAlignment(Align.left);cell.width((width-ui.px(24f)).coerceAtLeast(ui.px(40f))) }
            if(child is TextButton) {ui.decorate(child);cell.minHeight(ui.px(44f));cell.maxWidth(width)}
        }
        if(actor is Group) for(child in actor.children.toList()) fitContent(child,width-ui.px(8f))
    }
}
