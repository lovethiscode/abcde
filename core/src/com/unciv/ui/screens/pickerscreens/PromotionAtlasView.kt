package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Scaling
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.input.*
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.screens.basescreen.SafeAreaViewport
import com.unciv.view.MapUnitView
import kotlin.math.atan2
import kotlin.math.sqrt

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
    private val compact = height <= 600f && height < width
    private val padding = if (compact) 12f else 16f
    private val gap = if (compact) 12f else 20f
    private val detailWidth = if (compact) 280f else 310f
    private val root = Table()
    private val body = Stack()
    private val main = Table()
    private val details = Table()
    private val summary = Table()
    private val graphNodes = linkedMapOf<PromotionTree.PromotionNode, Table>()
    private val graphGates = linkedMapOf<PromotionTree.PromotionNode, Table>()
    private val orderedNodes = tree.allNodesInSourceOrder().toList()
    private val browse = Table()
    private val displayOptions: TextButton
    private var prerequisitesOnly = false
    private var ancestors = emptySet<PromotionTree.PromotionNode>()
    private val selectionColor = Color.valueOf("f6fafb")
    private val lockedColor = Color.valueOf("8299a3")
    private val wireColor = Color.valueOf("587580")
    private val routeColor = Color.valueOf("203e4a")
    private var selected = orderedNodes.firstOrNull { canPick(it) } ?: orderedNodes.firstOrNull()
    private var expanded = false
    private var saving = false
    private var statusText: String? = null
    private lateinit var canvas: WidgetGroup
    private lateinit var graphScroll: ZoomableScrollPane
    private var detailsScroll: AutoScrollPane? = null
    private var graphZoom = 1f
    class State(val selection: String?, val x: Float, val y: Float, val zoom: Float,
                val expanded: Boolean, val detailY: Float, val prerequisitesOnly: Boolean)
    fun snapshot() = State(selected?.promotion?.name, graphScroll.scrollX / style.unit,
        graphScroll.scrollY / style.unit, graphScroll.scaleX, expanded,
        (detailsScroll?.scrollY ?: 0f) / style.unit, prerequisitesOnly)
    fun restore(state: State, save: Boolean) {
        selected = tree.allNodes().firstOrNull { it.promotion.name == state.selection } ?: selected
        expanded = state.expanded; saving = save; prerequisitesOnly = state.prerequisitesOnly
        graphZoom = state.zoom
        buildGraph()
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
            labelCell.growX().minWidth(0f)
            onActivation { action() }
        }
    private fun promotionIcon(node: PromotionTree.PromotionNode): Table {
        val parts = com.unciv.models.ruleset.unit.Promotion.getBaseNameAndLevel(node.promotion.name)
        val path = listOf("UnitPromotionIcons/${parts.nameWithoutBrackets}",
            "UnitPromotionIcons/${parts.basePromotionName}", "UnitIcons/${parts.basePromotionName.removeSuffix(" ability")}")
            .firstOrNull { ImageGetter.imageExists(it) } ?: "OtherIcons/Star"
        return Table().apply {
            background = style.outline(style.deep, style.line, radius = 20f)
            pad(u(5f))
            add(ImageGetter.getImage(path).apply { color = style.text }).grow()
        }
    }
    private fun state(node: PromotionTree.PromotionNode) = when {
        node.isAdopted -> "Acquired"
        canPick(node) -> "Promotion available"
        else -> "Locked"
    }

    init {
        root.name = "promotion-atlas"
        root.setFillParent(true)
        root.pad(u(4f), u(padding), u(8f), u(padding))
        screen.stage.addActor(root)
        val header = Table()
        UiNavigation.styleBackButton(screen.closeButton, unit = style.unit)
        header.add(screen.closeButton).minSize(u(44f)).padRight(u(12f))
        header.add().growX()
        displayOptions = button("", "ui-promotion-display-options") { showDisplayOptions() }.apply {
            clearChildren()
            add(ImageGetter.getImage("OtherIcons/Options")).size(u(22f))
            addTooltip("Display options", size = this@PromotionAtlasView.style.font(TextRole.Supporting).toFloat(),
                dynamicTextProvider = {
                    "{Display options} · {${if (prerequisitesOnly) "Prerequisites only" else "All promotions"}}".tr()
                })
        }
        header.add(displayOptions).size(u(44f))
        root.add(header).growX().padBottom(u(4f)).row()
        buildGraph()
        root.add(body).grow().minSize(0f)
        refresh()
        root.validate()
        alignGraphToOrigin()
        // BACK closes the portrait reading layer first; the existing page back action is retained.
        screen.closeButton.keyShortcuts.clear()
        screen.globalShortcuts.add(KeyCharAndCode.BACK) {
            if (portrait && expanded) { expanded = false; refresh() }
            else screen.closeButton.activate()
        }
    }

    /** Highlight all alternative prerequisites, never just the preferred purchasing path. */
    private fun collectAncestors(): Set<PromotionTree.PromotionNode> {
        val result = linkedSetOf<PromotionTree.PromotionNode>()
        val pending = java.util.ArrayDeque<PromotionTree.PromotionNode>()
        selected?.let { pending.add(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (result.add(node)) pending.addAll(node.parents)
        }
        return result
    }

    /** Longest dependency depth is presentation only; purchasing still uses PromotionTree. */
    private fun depths(): Map<PromotionTree.PromotionNode, Int> {
        val result = mutableMapOf<PromotionTree.PromotionNode, Int>()
        val visiting = mutableSetOf<PromotionTree.PromotionNode>()
        fun visit(node: PromotionTree.PromotionNode): Int {
            result[node]?.let { return it }
            if (!visiting.add(node)) return 0 // Defensive against malformed mod cycles.
            val depth = node.parents.maxOfOrNull { visit(it) + 1 }
                ?: if (node.promotion.prerequisites.isEmpty()) 0 else 1
            visiting.remove(node)
            result[node] = depth
            return depth
        }
        orderedNodes.forEach { visit(it) }
        return result
    }

    /** Border patterns carry acquisition state; neutral corner marks carry selection separately. */
    private fun nodeSurface(acquired: Boolean, available: Boolean, isSelected: () -> Boolean,
                            inRoute: () -> Boolean = { false }): Drawable = object : BaseDrawable() {
        private val fills = listOf(style.background, routeColor, style.raised)
        private val surfaces = fills.map { fill ->
            if (acquired) style.outline(fill, style.good, 6f)
            else if (available) style.outline(fill, style.gold, 6f, 1.5f)
            else style.surface(fill, 6f)
        }
        private val innerBorders = if (acquired) fills.map { style.outline(it, style.good, 3f) } else emptyList()
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            val old = batch.packedColor
            val alpha = batch.color.a
            val variant = if (isSelected()) 2 else if (inRoute()) 1 else 0
            surfaces[variant].draw(batch, x, y, width, height)
            fun rect(px: Float, py: Float, w: Float, h: Float, color: Color) {
                batch.setColor(color.r, color.g, color.b, color.a * alpha)
                batch.draw(ImageGetter.getWhiteDotDrawable().region, px, py, w, h)
            }
            when {
                acquired -> {
                    batch.packedColor = old
                    innerBorders[variant].draw(batch, x+u(3f), y+u(3f), width-u(6f), height-u(6f))
                }
                available -> Unit
                else -> {
                    val t = u(1.5f); val dash = u(3f); val step = u(5f)
                    var offset = 0f
                    while (offset < width) {
                        val length = minOf(dash, width-offset)
                        rect(x+offset, y, length, t, lockedColor)
                        rect(x+offset, y+height-t, length, t, lockedColor)
                        offset += step
                    }
                    offset = 0f
                    while (offset < height) {
                        val length = minOf(dash, height-offset)
                        rect(x, y+offset, t, length, lockedColor)
                        rect(x+width-t, y+offset, t, length, lockedColor)
                        offset += step
                    }
                }
            }
            if (isSelected()) {
                val t = u(2f); val arm = u(9f); val inset = u(2f)
                for (right in listOf(false, true)) for (top in listOf(false, true)) {
                    val px = if (right) x+width+inset else x-inset-t
                    val py = if (top) y+height+inset else y-inset-t
                    rect(if (right) px-arm+t else px, py, arm, t, selectionColor)
                    rect(px, if (top) py-arm+t else py, t, arm, selectionColor)
                }
            }
            batch.packedColor = old
        }
    }

    private fun buildGraph() {
        ancestors = collectAncestors()
        graphNodes.clear()
        graphGates.clear()
        val depth = depths()
        val visible = orderedNodes.filter { !prerequisitesOnly || it in ancestors }
        val labels = orderedNodes.associateWith { label(it.promotion.name, TextRole.CompactData) }
        val nodeWidth = ((labels.values.maxOfOrNull { it.prefWidth } ?: 0f) + u(56f)).coerceIn(u(156f), u(224f))
        for (word in labels.values) { word.wrap = true; word.setSize(nodeWidth-u(56f), 0f) }
        val junction = label("OR", TextRole.Supporting)
        val gateWidth = maxOf(u(24f), junction.prefWidth+u(8f))
        val gateHeight = maxOf(u(20f), junction.prefHeight+u(4f))
        val columnGap = maxOf(u(44f), gateWidth+u(20f))
        val step = nodeWidth + columnGap
        // Preserve candidate sequence. Only adjacent increasing ranks can share a row.
        val groups = mutableListOf<MutableList<PromotionTree.PromotionNode>>()
        for (node in visible) {
            val previous = groups.lastOrNull()?.lastOrNull()
            if (previous == null || previous.baseName != node.baseName ||
                node.level <= previous.level || depth.getValue(node) <= depth.getValue(previous))
                groups.add(mutableListOf())
            groups.last().add(node)
        }
        val columns = (visible.maxOfOrNull { depth.getValue(it) } ?: 0) + 1
        val top = u(12f)
        val rowHeights = groups.map { group -> maxOf(u(44f), gateHeight+u(8f), group.maxOf { labels.getValue(it).prefHeight+u(12f) }) }
        val cw = maxOf(u(390f), (columns-1)*step+nodeWidth+u(36f))
        val ch = maxOf(u(220f), top+rowHeights.sum()+u(groups.size*4f+8f))
        canvas = object : WidgetGroup() {
            private var positionedHeight = ch
            override fun getPrefWidth() = cw
            override fun getPrefHeight() = ch
            override fun layout() {
                val shift = height-positionedHeight
                for (child in children) child.moveBy(0f, shift)
                positionedHeight = height
            }
        }.apply { setSize(cw, ch); touchable = Touchable.childrenOnly }
        var topY = top
        for ((index, group) in groups.withIndex()) {
            val rowHeight = rowHeights[index]
            for (node in group) {
                val tile = Table().apply {
                    name = "ui-promotion:${node.promotion.name}"
                    touchable = Touchable.enabled
                    pad(u(6f), u(8f), u(6f), u(8f))
                    add(promotionIcon(node)).size(u(26f)).padRight(u(8f))
                    add(labels.getValue(node)).growX().minWidth(0f).left()
                    background = nodeSurface(node.isAdopted, canPick(node), { node == selected }, { node in ancestors })
                    setBounds(u(18f)+depth.getValue(node)*step, ch-topY-rowHeight, nodeWidth, rowHeight)
                    addTooltip("{${node.promotion.name}} · {${state(node)}}")
                    onClick { select(node) }
                    if (canPick(node)) onDoubleClick(UncivSound.Silent) { pick(node) }
                }
                graphNodes[node] = tile
                canvas.addActor(tile)
            }
            topY += rowHeight+u(4f)
        }
        addWires(columnGap)
        for ((node, tile) in graphNodes) if (node.promotion.prerequisites.size > 1) {
            val gate = Table().apply {
                touchable = Touchable.disabled
                background = style.outline(style.deep, wireColor, 3f)
                add(label("OR", TextRole.Supporting, true))
                setBounds(tile.x-gateWidth-u(7f), tile.y+(tile.height-gateHeight)/2, gateWidth, gateHeight)
            }
            graphGates[node] = gate
            canvas.addActor(gate)
        }
        if (visible.isEmpty()) canvas.addActor(label("No promotions available", wrap = true).apply {
            setBounds(u(18f), ch-u(110f), nodeWidth, u(70f))
        })
        graphScroll = ZoomableScrollPane(
            minZoom = TechnologyAtlasGraph.minZoom,
            maxZoom = TechnologyAtlasGraph.maxZoom,
            // Promotion nodes own tap and double-tap gestures, so capture both pointers before
            // a node can consume a pinch that starts on the graph content.
            captureZoomGestures = true,
        ).apply {
            name = "ui-promotion-tree"
            actor = canvas
            style = ScrollPane.ScrollPaneStyle().apply { background = this@PromotionAtlasView.style.surface(this@PromotionAtlasView.style.deep, 10f) }
            setOverscroll(false, false); setFadeScrollBars(false); setScrollingDisabled(false, false)
            zoom(graphZoom)
            onZoomStopListener = { graphZoom = scaleX }
        }
    }

    private data class Segment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private data class Wire(val node: PromotionTree.PromotionNode, val parent: PromotionTree.PromotionNode, val segments: List<Segment>)

    private fun addWires(columnGap: Float) {
        val crossings = graphNodes.flatMap { (node, target) ->
            if (node.parents.size != 1) emptyList() else node.parents.mapNotNull { parent ->
                val source = graphNodes[parent] ?: return@mapNotNull null
                if (source.y != target.y) null else Triple(parent, source, target)
            }
        }
        val wires = graphNodes.flatMap { (node, target) -> node.parents.mapNotNull { parent ->
            val source = graphNodes[parent] ?: return@mapNotNull null
            val x1 = source.x+source.width; val y1 = source.y+source.height/2
            val x2 = target.x-u(5f); val y2 = target.y+target.height/2
            val mid = target.x-columnGap+u(8f)
            val segments = mutableListOf(Segment(x1,y1,mid,y1))
            val cuts = crossings.filter { (p, start, end) -> p !in node.parents && start.x+start.width < mid && end.x > mid }
                .map { (_, start, _) -> start.y+start.height/2 }
                .filter { it > minOf(y1,y2)+u(4f) && it < maxOf(y1,y2)-u(4f) }.distinct().sorted()
            var from = minOf(y1,y2)
            for (cut in cuts) { segments.add(Segment(mid,from,mid,cut-u(4f))); from = cut+u(4f) }
            segments.add(Segment(mid,from,mid,maxOf(y1,y2)))
            segments.add(Segment(mid,y2,x2,y2))
            segments.add(Segment(x2-u(5f),y2-u(3f),x2,y2))
            segments.add(Segment(x2-u(5f),y2+u(3f),x2,y2))
            Wire(node,parent,segments)
        } }
        canvas.addActorAt(0, object : Actor() {
            init {
                touchable = Touchable.disabled
                // ScrollPane culls zero-sized children even when their draw() paints the full graph.
                setSize(canvas.prefWidth, canvas.prefHeight)
            }
            override fun draw(batch: Batch, parentAlpha: Float) {
                val old = batch.packedColor
                val dot = ImageGetter.getWhiteDotDrawable().region
                // Quiet edges first so selected alternatives remain legible at crossings.
                for (active in listOf(false,true)) for (wire in wires) {
                    if ((wire.node in ancestors && wire.parent in ancestors) != active) continue
                    if (prerequisitesOnly && !active) continue
                    batch.color = (if (active) selectionColor else wireColor).cpy().apply { a *= parentAlpha }
                    val stroke = u(if (active) 2.2f else 1.3f)
                    for (s in wire.segments) {
                        val dx=s.x2-s.x1; val dy=s.y2-s.y1
                        val length=sqrt(dx*dx+dy*dy)
                        if (length <= 0f) continue
                        batch.draw(dot,x+s.x1,y+s.y1-stroke/2,0f,stroke/2,length,stroke,1f,1f,
                            atan2(dy,dx)*180f/Math.PI.toFloat())
                    }
                }
                batch.packedColor = old
            }
        })
    }

    private fun buildBrowse() {
        browse.clear()
        browse.background = style.surface(style.deep)
        val graphHost = object : WidgetGroup() {
            init { addActor(graphScroll) }
            override fun layout() { graphScroll.resizeViewport(Rectangle(0f, 0f, width, height)) }
        }
        browse.add(graphHost).grow().minSize(0f)
    }
    /** Low-frequency controls live off the canvas so every orientation has room for more nodes. */
    private fun showDisplayOptions() {
        displayOptions.isChecked = prerequisitesOnly
        object : AnimatedMenuPopup(screen.stage, displayOptions, Align.bottomRight) {
            override fun createContentTable(): Table {
                val bounds = (screen.stage.viewport as? SafeAreaViewport)?.safeAreaBoundsInWorld
                    ?: Rectangle(0f, 0f, screen.stage.width, screen.stage.height)
                val contentWidth = (minOf(u(320f), bounds.width-u(24f))-u(24f)).coerceAtLeast(u(44f))
                val content = Table()
                for ((key, focused) in listOf("All promotions" to false, "Prerequisites only" to true)) {
                    content.add(button(key, "ui-promotion-filter:$focused") {
                        close()
                        if (prerequisitesOnly != focused) {
                            prerequisitesOnly = focused
                            buildGraph()
                            refresh()
                        }
                    }.apply { isChecked = prerequisitesOnly == focused })
                        .width(contentWidth).minHeight(u(44f)).padBottom(u(4f)).row()
                }
                content.add(button("Locate selection", "ui-promotion-locate") {
                    close()
                    locateSelection()
                }.apply { isEnabled = selected != null })
                    .width(contentWidth).minHeight(u(44f)).padTop(u(4f)).padBottom(u(12f)).row()
                for ((index, text) in listOf("Selected", "Acquired", "Promotion available", "Locked").withIndex()) {
                    val entry = Table()
                    entry.add(Image(nodeSurface(index == 1, index == 2, { index == 0 })))
                        .size(u(18f), u(12f)).pad(u(4f), u(4f), u(4f), u(10f))
                    entry.add(label(text, TextRole.Supporting, muted = true, wrap = true))
                        .growX().minWidth(0f).left()
                    content.add(entry).width(contentWidth).padBottom(u(6f)).row()
                }
                return Table().apply {
                    name = "ui-promotion-display-menu"
                    background = style.outline(style.panel, style.line)
                    pad(u(12f))
                    add(AutoScrollPane(content).apply { setScrollingDisabled(true, false) })
                        .width(contentWidth)
                        .height(minOf(content.prefHeight,
                            (bounds.height-u(48f)).coerceAtLeast(u(44f))))
                }
            }
        }
    }

    private fun select(node: PromotionTree.PromotionNode) {
        selected = node; statusText = null
        ancestors = collectAncestors()
        // Keep the tapped actor, its gesture counter and position stable for double-tap promotion.
        // A link outside this subgraph needs a new canvas; otherwise hide unrelated nodes in place.
        if (prerequisitesOnly && node !in graphNodes) buildGraph()
        refresh()
    }
    private fun locateSelection() {
        val target = graphNodes[selected] ?: return
        graphScroll.validate()
        graphScroll.scrollX = target.x + target.width / 2f
        graphScroll.scrollY = graphScroll.maxY - target.y - target.height / 2f
        graphScroll.updateVisualScroll()
        graphScroll.updateCulling()
    }

    /** Open at the graph's top-left instead of the padding that enables edge-centered panning. */
    private fun alignGraphToOrigin() {
        graphScroll.validate()
        graphScroll.scrollX = graphScroll.width / 2f
        graphScroll.scrollY = graphScroll.height / 2f
        graphScroll.updateVisualScroll()
        graphScroll.updateCulling()
    }
    private fun availableOpportunities() = (1..tree.possiblePromotions.size).takeWhile { unit.canAffordPromotions(it) }.size

    private fun unitContext(target: Table, withRename: Boolean) {
        val identity = Table()
        identity.add(label(unit.displayName(), TextRole.Body, wrap = true)).growX().minWidth(0f).left()
        if (withRename && canChange) identity.add(button("", "ui-promotion-rename") {
            UnitRenamePopup(screen, unit) { screen.game.replaceCurrentScreen { screen.recreate() } }
        }.apply { add(ImageGetter.getImage("OtherIcons/Pencil")).size(u(18f)); addTooltip("Rename") }).size(u(44f)).padLeft(u(8f))
        target.add(identity).growX().minWidth(0f).row()
        val available = availableOpportunities()
        target.add(label("{XP} ${unit.xp}/${unit.xpForNextPromotion()} · {[$available] promotions available} · {Health} ${unit.unitHealth}/100",
            if (compact) TextRole.Metadata else TextRole.Supporting, true, true)).growX().minWidth(0f).left().row()
    }
    private fun refresh() {
        displayOptions.isChecked = prerequisitesOnly
        ancestors = collectAncestors()
        for ((node, tile) in graphNodes) tile.isVisible = !prerequisitesOnly || node in ancestors
        for ((node, gate) in graphGates) gate.isVisible = !prerequisitesOnly || node in ancestors
        main.clear(); body.clearChildren()
        buildBrowse()
        main.add(browse).grow().minSize(0f)
        if (!portrait) { buildDetails(); main.add(details).width(u(detailWidth)).growY().minHeight(0f).padLeft(u(gap)) }
        else if (!expanded) {
            main.row(); summary.clear(); summary.padTop(u(12f))
            val context = Table()
            context.add(label(unit.displayName(), TextRole.Body, wrap = true)).growX().minWidth(0f).left().row()
            val caption = selected?.let { "{[${availableOpportunities()}] promotions available} · {${it.promotion.name}}" } ?: "No promotions available"
            context.add(label(caption, TextRole.Supporting, true, true)).growX().minWidth(0f).left()
            summary.add(context).growX().minWidth(0f).padRight(u(12f))
            summary.add(button("View details", "ui-promotion-details", true) { expanded = true; refresh() }).minHeight(u(44f)).width(u(108f))
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
        details.touchable = Touchable.enabled
        val detailTitleRole = if (compact) TextRole.Body else TextRole.SectionTitle
        val detailBodyRole = if (compact) TextRole.Supporting else TextRole.Body
        val detailMetaRole = if (compact) TextRole.Metadata else TextRole.Supporting
        if (portrait) {
            val top = Table()
            top.add().growX()
            top.add(UiNavigation.dismissButton(style.unit).apply {
                name = "ui-promotion-detail-close"
                onActivation { expanded = false; statusText = null; refresh() }
            }).size(u(44f))
            details.add(top).growX().padBottom(u(8f)).row()
        }
        val reading = Table().apply { top().left(); defaults().minWidth(0f).growX().left() }
        val identityArea = if (compact) reading else details
        unitContext(identityArea, true)
        val node = selected
        if (node != null) {
            val heading = Table()
            heading.add(promotionIcon(node)).size(u(40f)).padRight(u(10f))
            val words = Table()
            words.add(label(node.promotion.name, detailTitleRole, wrap = true)).growX().minWidth(0f).left().row()
            words.add(label(state(node), detailMetaRole, true, true)).growX().minWidth(0f).left()
            heading.add(words).growX().minWidth(0f)
            identityArea.add(heading).growX().minWidth(0f).padTop(u(14f)).padBottom(u(12f)).row()
        }
        if (node != null) {
            reading.add(label("Direct prerequisites", TextRole.Supporting, true, true)).padBottom(u(6f)).row()
            val prerequisites = node.promotion.prerequisites
            if (prerequisites.isEmpty()) reading.add(label("No prerequisite", detailBodyRole, wrap = true)).padBottom(u(12f)).row()
            else {
                if (prerequisites.size > 1)
                    reading.add(label("Any one prerequisite", TextRole.Supporting, true, true)).padBottom(u(6f)).row()
                val readingWidth = u((if (portrait) width-padding*2 else detailWidth)-28f)
                val orWord = label("OR", TextRole.Supporting, true)
                val horizontal = prerequisites.size == 2 && node.parents.size == 2 &&
                    prerequisites.sumOf { maxOf(u(44f), label(it, TextRole.Control).prefWidth+u(20f)).toDouble() } +
                        orWord.prefWidth+u(24f) <= readingWidth
                if (horizontal) {
                    val row = Table()
                    val linkWidth = (readingWidth-orWord.prefWidth-u(16f))/2
                    for ((index, name) in prerequisites.withIndex()) {
                        if (index > 0) row.add(orWord).padLeft(u(8f)).padRight(u(8f))
                        val related = node.parents.first { it.promotion.name == name }
                        row.add(button(name, "ui-promotion-link:$name") { select(related) })
                            .width(linkWidth).minHeight(u(44f))
                    }
                    reading.add(row).growX().minWidth(0f).padBottom(u(6f)).row()
                } else {
                    for ((index, name) in prerequisites.withIndex()) {
                        if (index > 0) reading.add(label("OR", TextRole.Supporting, true)).padBottom(u(4f)).row()
                        val related = node.parents.firstOrNull { it.promotion.name == name }
                        // Keep missing/inapplicable prerequisites visible without inventing selectable nodes.
                        if (related == null) reading.add(label(name, TextRole.Control, true, true)).padBottom(u(6f)).row()
                        else reading.add(button(name, "ui-promotion-link:$name") { select(related) })
                            .minHeight(u(44f)).padBottom(u(6f)).row()
                    }
                }
            }
            reading.add(label(statusText ?: node.promotion.getDescription(tree.possiblePromotions), detailBodyRole, wrap = true)).padTop(u(12f)).padBottom(u(16f)).row()
            reading.add(label("Unlocks", TextRole.Supporting, true, true)).padBottom(u(6f)).row()
            if (node.children.isEmpty()) reading.add(label("End of this branch", detailBodyRole, wrap = true)).row()
            for (related in orderedNodes.filter { it in node.children })
                reading.add(button(related.promotion.name, "ui-promotion-link:${related.promotion.name}") { select(related) })
                    .minHeight(u(44f)).padBottom(u(6f)).row()
            reading.add(button("Civilopedia", "ui-promotion-civilopedia") { screen.openCivilopedia(node.promotion.makeLink()) }.apply {
                keyShortcuts.add(KeyboardBinding.Civilopedia)
            }).minHeight(u(44f)).padTop(u(8f)).row()
        }
        for (status in unit.getStatusMap().values.sortedBy { it.turnsLeft }) {
            reading.add(button("{${status.name}}: {[${status.turnsLeft}] turns}", "ui-promotion-status:${status.name}") {
                statusText = "{${status.name}}: {[${status.turnsLeft}] turns}".tr() + "\n" +
                    unit.civ().ruleset.unitPromotions[status.name]?.getDescription(emptySet()).orEmpty()
                refresh()
            }).minHeight(u(44f)).padTop(u(8f)).row()
        }
        if (node == null && statusText != null) reading.add(label(statusText!!, detailBodyRole, wrap = true)).row()
        if (unit.canSaveDefaultPromotions()) reading.add("Default promotions for [${unit.getBaseUnit().name}]".toCheckBox(saving) {
            saving = it; saveDefaults(it)
        }.apply {
            label.wrap = true
            label.setText("Default promotions for [${unit.getBaseUnit().name}]".tr(hideIcons = true))
            labelCell.growX().minWidth(0f)
            imageCell.size(u(24f)).padRight(u(8f))
            // CheckBox defaults to Scaling.none; the skin drawable can exceed the pt-sized cell.
            image.setScaling(Scaling.fit)
            label.setFontSize(this@PromotionAtlasView.style.font(TextRole.Control))
        }).minHeight(u(44f)).padTop(u(12f)).row()
        detailsScroll = AutoScrollPane(reading).apply { setScrollingDisabled(true, false); setOverscroll(false, false) }
        details.add(detailsScroll).grow().minSize(0f).row()
        if (node != null) {
            val path = tree.getPathTo(node.promotion)
            val reason = when {
                node.isAdopted -> "Acquired"
                node.pathIsAmbiguous && node.distanceToAdopted > 1 && tree.canBuyUpTo(node.promotion) -> "Path to [${node.promotion.name}] is ambiguous"
                !canChange -> "View only"
                !unit.hasMovement() || unit.attacksThisTurn > 0 -> "Unit must be able to move and must not have attacked"
                !unit.canBePromoted() -> "Not enough experience to promote"
                !canPick(node) -> "Promotion requirements not met"
                else -> "Consumes [${path.count { !it.hasUnique(com.unciv.models.ruleset.unique.UniqueType.FreePromotion) }}] promotion opportunities"
            }
            details.add(label(reason, detailMetaRole, true, true)).growX().minWidth(0f).padTop(u(10f)).padBottom(u(8f)).row()
            val action = if (node.isAdopted) "Acquired" else "Choose [${node.promotion.name}]"
            details.add(button(action, "ui-promotion-confirm", true) { pick(node) }.apply {
                // Keep the atlas disabled drawable; isEnabled swaps in the legacy skin.
                isDisabled = !canPick(node)
            }).growX().minWidth(0f).minHeight(u(44f))
        }
    }
}
