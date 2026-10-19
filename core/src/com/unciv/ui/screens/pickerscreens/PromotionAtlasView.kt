package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Align
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.input.*
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.view.MapUnitView
import kotlin.math.abs

/** iOS presentation of the existing promotion model; no promotion rules live in this view. */
internal class PromotionAtlasView(
    private val screen: PromotionPickerScreen,
    private val unit: MapUnitView,
    private val tree: PromotionTree,
    private val canChange: Boolean,
    private val canPick: (PromotionTree.PromotionNode) -> Boolean,
    private val pick: (PromotionTree.PromotionNode) -> Unit,
    private val saveDefaults: (Boolean) -> Unit
) {
    private val style = TechnologyAtlasStyle(UiTypography.unitsPerPoint(screen.stage))
    private fun u(n: Float) = style.u(n)
    private val width = screen.stage.width / style.unit
    private val height = screen.stage.height / style.unit
    private val portrait = height > width && width <= 700f
    private val compact = height < 500f
    private val padding = if (compact) 12f else 16f
    private val gap = if (compact) 12f else 20f
    private val detailWidth = if (compact) 280f else 310f
    private val root = Table()
    private val body = Stack()
    private val main = Table()
    private val details = Table()
    private val summary = Table()
    private val graphNodes = linkedMapOf<PromotionTree.PromotionNode, Table>()
    private val positions = linkedMapOf<PromotionTree.PromotionNode, Pair<Int, Int>>()
    private var selected = tree.allNodes().firstOrNull { canPick(it) && it.level > 0 }
        ?: tree.allNodes().firstOrNull { canPick(it) } ?: tree.allNodes().firstOrNull()
    private var expanded = false
    private var saving = false
    private var statusText: String? = null
    private lateinit var canvas: WidgetGroup
    private lateinit var graphScroll: AutoScrollPane
    private var detailsScroll: AutoScrollPane? = null
    data class State(val selection: String?, val x: Float, val y: Float, val expanded: Boolean, val detailY: Float)
    fun snapshot() = State(selected?.promotion?.name, graphScroll.scrollX / style.unit,
        graphScroll.scrollY / style.unit, expanded, (detailsScroll?.scrollY ?: 0f) / style.unit)
    fun restore(state: State, save: Boolean) {
        selected = tree.allNodes().firstOrNull { it.promotion.name == state.selection } ?: selected
        expanded = state.expanded; saving = save
        refresh()
        root.validate()
        graphScroll.scrollX = u(state.x); graphScroll.scrollY = u(state.y)
        graphScroll.updateVisualScroll()
        detailsScroll?.let { it.scrollY = u(state.detailY); it.updateVisualScroll() }
    }
    private fun label(text: String, role: TextRole = TextRole.Body, muted: Boolean = false, wrap: Boolean = false) =
        style.label(text, role, if (muted) style.muted else style.text, wrap)
    private fun button(text: String, id: String, primary: Boolean = false, action: () -> Unit) =
        style.button(text, primary, outlined = !primary).apply {
            name = id; label.setEllipsis(false); label.wrap = true
            onActivation { action() }
        }
    private fun state(node: PromotionTree.PromotionNode) = when {
        node.isAdopted -> "Acquired"
        canPick(node) -> "Promotion available"
        else -> "Locked"
    }

    init {
        root.name = "promotion-atlas"
        root.setFillParent(true)
        root.pad(u(if (compact) 6f else 8f), u(padding), u(12f), u(padding))
        screen.stage.addActor(root)
        val header = Table()
        UiNavigation.styleBackButton(screen.closeButton, unit = style.unit)
        header.add(screen.closeButton).minSize(u(44f)).padRight(u(12f))
        header.add(label("Unit promotions", TextRole.PageTitle)).growX().minWidth(0f).left()
        if (!portrait) header.add(label("Promotion paths", TextRole.Supporting, true)).padRight(u(16f))
        header.add(button("Locate selection", "ui-promotion-locate") { locateSelection() }).minHeight(u(44f)).width(u(104f))
        root.add(header).growX().padBottom(u(12f)).row()
        buildGraph()
        root.add(body).grow().minSize(0f)
        refresh()
        root.validate()
        graphScroll.scrollY = 0f
        graphScroll.updateVisualScroll()
        // BACK closes the portrait reading layer first; the existing page back action is retained.
        screen.closeButton.keyShortcuts.clear()
        screen.globalShortcuts.add(KeyCharAndCode.BACK) {
            if (portrait && expanded) { expanded = false; refresh() }
            else screen.closeButton.activate()
        }
    }

    /** Keep numbered families together and use all candidates, including mod-defined and blocked nodes. */
    private fun arrange() {
        val groups = tree.allNodes().groupBy { it.baseName }.values
        val occupied = mutableSetOf<Pair<Int, Int>>()
        val ordered = groups.sortedWith(compareBy<List<PromotionTree.PromotionNode>> {
            when { it.any { n -> n.isRoot && n.level > 0 } -> 0; it.all { n -> n.isRoot } -> 2; else -> 1 }
        }.thenBy { if (it.any { n -> n.isRoot }) 0 else -it.minOf { n -> n.depth.coerceAtLeast(0) } })
        for (group in ordered) {
            val columns = group.associateWith { it.depth.coerceAtLeast(0) }
            var row = 0
            while (columns.values.any { it to row in occupied }) row++
            for (node in group) {
                var col = columns.getValue(node)
                while (col to row in occupied) col++
                positions[node] = col to row
                occupied += col to row
            }
        }
    }

    private fun buildGraph() {
        arrange()
        val cols = (positions.values.maxOfOrNull { it.first } ?: 0) + 1
        val rows = (positions.values.maxOfOrNull { it.second } ?: 0) + 1
        val cw = u(cols * 196f + 12f)
        val ch = u(rows * 72f + 44f)
        canvas = object : WidgetGroup() {
            override fun getPrefWidth() = cw
            override fun getPrefHeight() = ch
        }.apply { setSize(cw, ch); touchable = Touchable.childrenOnly }
        canvas.addActor(object : Actor() {
            init { touchable = Touchable.disabled }
            override fun draw(batch: Batch, parentAlpha: Float) {
                val old = batch.packedColor
                val path = selected?.let { tree.getPathTo(it.promotion).toSet() }.orEmpty()
                val dot = ImageGetter.getWhiteDotDrawable().region
                fun line(x: Float, y: Float, w: Float, h: Float) { batch.draw(dot, x, y, w.coerceAtLeast(0f), h.coerceAtLeast(0f)) }
                for ((node, target) in graphNodes) for (parent in node.parents) {
                    val source = graphNodes[parent] ?: continue
                    val active = node.promotion in path && (parent.isAdopted || parent.promotion in path)
                    batch.color = (if (active) style.gold else style.quietLine).cpy().apply { a = parentAlpha }
                    val stroke = u(if (active) 2.5f else 1.5f)
                    val x1 = source.x + source.width; val y1 = source.y + source.height / 2
                    val x2 = target.x; val y2 = target.y + target.height / 2
                    val mid = (x1 + x2) / 2
                    line(minOf(x1, mid), y1, abs(mid - x1), stroke)
                    line(mid, minOf(y1, y2), stroke, abs(y2 - y1) + stroke)
                    line(minOf(mid, x2), y2, abs(x2 - mid), stroke)
                }
                batch.packedColor = old
            }
        })
        for (col in 0 until cols) canvas.addActor(label(when (col) { 0 -> "Basic promotions"; 1 -> "Advanced promotions"; 2 -> "Specialist promotions"; else -> "Promotion tier [${col + 1}]" }, TextRole.Supporting, true).apply {
            setBounds(u(16f + col * 196f), ch - u(30f), u(176f), u(20f))
        })
        for ((node, pos) in positions) {
            val tile = Table().apply {
                name = "ui-promotion:${node.promotion.name}"
                touchable = Touchable.enabled
                pad(u(6f), u(10f), u(6f), u(10f))
                add(ImageGetter.getPromotionPortrait(node.promotion.name)).size(u(28f)).padRight(u(8f))
                val words = Table()
                words.add(label(node.promotion.name, TextRole.Control).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
                words.add(label(state(node), TextRole.Supporting, true)).growX().minWidth(0f).left()
                add(words).growX().minWidth(0f)
                setBounds(u(16f + pos.first * 196f), ch - u(40f + pos.second * 72f + 60f), u(176f), u(60f))
                onClick { select(node) }
                if (canPick(node)) onDoubleClick(UncivSound.Silent) { pick(node) }
            }
            graphNodes[node] = tile
            canvas.addActor(tile)
        }
        if (positions.isEmpty()) canvas.addActor(label("No promotions available", wrap = true).apply {
            setBounds(u(16f), ch - u(100f), u(176f), u(70f))
        })
        graphScroll = AutoScrollPane(canvas).apply {
            name = "ui-promotion-tree"
            style = ScrollPane.ScrollPaneStyle().apply { background = this@PromotionAtlasView.style.surface(this@PromotionAtlasView.style.deep, 10f) }
            setOverscroll(false, false); setFadeScrollBars(false)
        }
    }
    private fun select(node: PromotionTree.PromotionNode) {
        selected = node; statusText = null
        if (portrait) expanded = true
        refresh()
    }
    private fun locateSelection() {
        val target = graphNodes[selected] ?: return
        graphScroll.scrollTo(target.x, target.y, target.width, target.height, true, true)
        graphScroll.updateVisualScroll()
    }
    private fun unitContext(target: Table, withRename: Boolean) {
        val identity = Table()
        identity.add(label(unit.displayName(), TextRole.Body, wrap = true)).growX().minWidth(0f).left()
        if (withRename && canChange) identity.add(button("", "ui-promotion-rename") {
            UnitRenamePopup(screen, unit) { screen.game.replaceCurrentScreen { screen.recreate() } }
        }.apply { add(ImageGetter.getImage("OtherIcons/Pencil")).size(u(18f)) }).size(u(44f)).padLeft(u(8f))
        target.add(identity).growX().minWidth(0f).row()
        val available = (1..tree.possiblePromotions.size).takeWhile { unit.canAffordPromotions(it) }.size
        target.add(label("{XP} ${unit.xp}/${unit.xpForNextPromotion()} · {[$available] promotions available} · {Health} ${unit.unitHealth}/100",
            TextRole.Supporting, true, true)).growX().minWidth(0f).left().row()
    }
    private fun refresh() {
        val path = selected?.let { tree.getPathTo(it.promotion).toSet() }.orEmpty()
        for ((node, tile) in graphNodes) tile.background = style.outline(
            if (node == selected || node.promotion in path) style.raised else if (node.isAdopted) style.panel else style.background,
            if (node == selected) style.gold else if (node.isAdopted) style.good else style.line,
            radius = 8f, thickness = if (node == selected) 2f else 1f)
        main.clear(); body.clearChildren()
        main.add(graphScroll).grow().minSize(0f)
        if (!portrait) { buildDetails(); main.add(details).width(u(detailWidth)).growY().padLeft(u(gap)) }
        else if (!expanded) {
            main.row(); summary.clear(); summary.padTop(u(12f))
            val context = Table()
            context.add(label(unit.displayName(), TextRole.Body).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
            val caption = selected?.let { "{${state(it)}} · {${it.promotion.name}}" } ?: "No promotions available"
            context.add(label(caption, TextRole.Supporting, true).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            summary.add(context).growX().minWidth(0f).padRight(u(12f))
            summary.add(button("View details", "ui-promotion-details") { expanded = true; refresh() }).minHeight(u(44f)).width(u(108f))
            main.add(summary).growX().minWidth(0f)
        }
        body.add(main)
        if (portrait && expanded) { buildDetails(); body.add(details) }
        root.invalidateHierarchy()
    }
    private fun buildDetails() {
        details.clear()
        details.background = style.surface(style.panel)
        details.pad(u(14f))
        if (portrait) {
            val top = Table()
            top.add(label("Promotion details", TextRole.SectionTitle)).growX().minWidth(0f).left()
            top.add(UiNavigation.dismissButton(style.unit).apply {
                name = "ui-promotion-detail-close"
                onActivation { expanded = false; statusText = null; refresh() }
            }).size(u(44f))
            details.add(top).growX().padBottom(u(8f)).row()
        }
        unitContext(details, true)
        val node = selected
        if (node != null) {
            val heading = Table()
            heading.add(ImageGetter.getPromotionPortrait(node.promotion.name)).size(u(40f)).padRight(u(10f))
            val words = Table()
            words.add(label(node.promotion.name, TextRole.SectionTitle, wrap = true)).growX().minWidth(0f).left().row()
            words.add(label(state(node), TextRole.Supporting, true)).left()
            heading.add(words).growX().minWidth(0f)
            details.add(heading).growX().minWidth(0f).padTop(u(14f)).padBottom(u(12f)).row()
        }
        val reading = Table().apply { top().left(); defaults().minWidth(0f).growX().left() }
        if (node != null) {
            reading.add(label(statusText ?: node.promotion.getDescription(tree.possiblePromotions), wrap = true)).padBottom(u(16f)).row()
            fun links(caption: String, nodes: Collection<PromotionTree.PromotionNode>) {
                if (nodes.isEmpty()) return
                reading.add(label(caption, TextRole.Supporting, true)).padTop(u(8f)).padBottom(u(6f)).row()
                for (related in nodes) reading.add(button(related.promotion.name, "ui-promotion-link:${related.promotion.name}") { select(related) })
                    .minHeight(u(44f)).padBottom(u(6f)).row()
            }
            links("Prerequisites", node.parents)
            links("Unlocks", node.children)
            reading.add(button("Civilopedia", "ui-promotion-civilopedia") { screen.openCivilopedia(node.promotion.makeLink()) }.apply {
                keyShortcuts.add(KeyboardBinding.Civilopedia)
            }).minHeight(u(44f)).padTop(u(8f)).row()
        }
        for (status in unit.getStatusMap().values.sortedBy { it.turnsLeft }) {
            reading.add(button("{${status.name}}: [${status.turnsLeft}] turns", "ui-promotion-status:${status.name}") {
                statusText = "{${status.name}}: [${status.turnsLeft}] turns".tr() + "\n" +
                    unit.civ().ruleset.unitPromotions[status.name]?.getDescription(emptySet()).orEmpty()
                refresh()
            }).minHeight(u(44f)).padTop(u(8f)).row()
        }
        if (node == null && statusText != null) reading.add(label(statusText!!, wrap = true)).row()
        if (unit.canSaveDefaultPromotions()) reading.add("Default promotions for [${unit.getBaseUnit().name}]".toCheckBox(saving) {
            saving = it; saveDefaults(it)
        }.apply { label.wrap = true; label.setFontSize(this@PromotionAtlasView.style.font(TextRole.Control)) }).minHeight(u(44f)).padTop(u(12f)).row()
        detailsScroll = AutoScrollPane(reading).apply { setScrollingDisabled(true, false); setOverscroll(false, false) }
        details.add(detailsScroll).grow().minSize(0f).row()
        if (node != null) {
            val path = tree.getPathTo(node.promotion)
            val reason = when {
                node.isAdopted -> "Acquired"
                node.pathIsAmbiguous && node.distanceToAdopted > 1 && tree.canBuyUpTo(node.promotion) -> "Path to [${node.promotion.name}] is ambiguous"
                !canChange -> "View only"
                !unit.hasMovement() || unit.attacksThisTurn > 0 -> "Unit must be able to move and must not have attacked"
                !canPick(node) -> "Promotion requirements not met"
                else -> "Consumes [${path.count { !it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FreePromotion) }}] promotion opportunities"
            }
            details.add(label(reason, TextRole.Supporting, true, true)).growX().minWidth(0f).padTop(u(10f)).padBottom(u(8f)).row()
            details.add(button("Choose [${node.promotion.name}]", "ui-promotion-confirm", true) { pick(node) }.apply {
                isEnabled = canPick(node)
            }).growX().minWidth(0f).minHeight(u(44f))
        }
    }
}
