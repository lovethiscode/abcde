package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Align
import com.unciv.GUI
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.endless.EndlessRules
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.*
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.ZoomableScrollPane
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.Portrait
import com.unciv.ui.images.PortraitUnavailableWonderForTechTree
import com.unciv.ui.objectdescriptions.TechnologyDescriptions
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.TextureArraySpriteBatch
import com.unciv.utils.Concurrency
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A: technology atlas. Browsing never changes research; the confirmation action commits the draft explicitly. */
class TechPickerScreen(
    internal val civInfo: Civilization,
    centerOnTech: Technology? = null,
) : PickerScreen() {
    private val civTech = civInfo.tech
    private val ruleset = civInfo.gameInfo.ruleset
    private val freeTechPick = civTech.freeTechs > 0
    private val militaryImprovements = CivilWarCampaign.isActive(civInfo.gameInfo)
    private val displayedTechs = ruleset.technologies.values.filter {
        (!militaryImprovements || it.name in CivilWarCampaign.technologies) &&
            !EndlessRules.researchBlocked(civInfo, it.name)
    }
    private val iconsIndex = TechnologyDescriptions.buildTechIconsIndex(ruleset, civInfo)
    private val savedPlan = civTech.techsToResearch.toList()
    private val draft = ArrayList(savedPlan)
    private var selectedTech = (centerOnTech ?: civTech.currentTechnology())?.takeIf { it in displayedTechs }
        ?: displayedTechs.firstOrNull { civTech.canBeResearched(it.name) } ?: displayedTechs.firstOrNull()
    private var activeEra = selectedTech?.era()
    private var committed = false
    private var overlay: String? = null
    private var searchQuery = ""
    private var appearance = makeStyle()
    private var metrics = TechnologyAtlasLayout(stage.width / appearance.unit, stage.height / appearance.unit)
    private val root = Table()
    private val body = object : Table() {
        override fun draw(batch: Batch, parentAlpha: Float) {
            super.draw(batch, parentAlpha)
            appearance.drawFrame(batch, x, y, width, height)
        }
    }
    private val header = Table()
    private val mainView = Table()
    private val detailsView = Table()
    private val eraButtons = linkedMapOf<String, TextButton>()
    private val nodes = linkedMapOf<String, Table>()
    private lateinit var tree: WidgetGroup
    private lateinit var treeScroll: ZoomableScrollPane
    private var zoom = if (metrics.compact) .8f else 1f
    private var iconShader: ShaderProgram? = null
    private var initialized = false
    private var searchField: UncivTextField? = null
    private var lastFocus: Actor? = null
    private val eras = displayedTechs.map { it.era() }.distinct().sortedBy { ruleset.eras[it]?.eraNumber ?: 0 }
    private val searchableNames by lazy {
        displayedTechs.associate { tech ->
            val names = listOf(tech.name) + iconsIndex.enabledUnitsByTech[tech.name].orEmpty().map { it.name } +
                iconsIndex.enabledBuildingsByTech[tech.name].orEmpty().map { it.name } +
                iconsIndex.resourcesByRevealedTech[tech.name].orEmpty().map { it.name } +
                iconsIndex.improvementsByTechRequired[tech.name].orEmpty().map { it.name }
            tech.name to names.flatMap { listOf(it, it.tr()) }.joinToString("\n").lowercase()
        }
    }
    override val screenClearColor: Color get() = appearance.background
    private fun makeStyle() = TechnologyAtlasStyle(UiTypography.unitsPerPoint(stage))
    private fun u(value: Float) = appearance.u(value)
    private fun label(text: String, size: TextRole = TextRole.Body, color: Color = appearance.text, wrap: Boolean = false) =
        appearance.label(text, size, color, wrap)
    private fun action(caption: String, name: String, primary: Boolean = false, outlined: Boolean = false,
                       block: () -> Unit) = appearance.button(caption, primary, outlined).apply {
        this.name = name
        val icon = when (name) {
            "ui-tech-search" -> "Search"
            "ui-tech-queue" -> "MenuIcon"
            "ui-tech-edit-queue" -> "Pencil"
            "ui-tech-civilopedia" -> "Book"
            "ui-tech-era-previous", "ui-tech-overlay-back" -> "BackArrow"
            "ui-tech-era-next" -> "ForwardArrow"
            "ui-tech-add" -> if (caption == "Queued") "Checkmark" else null
            else -> null
        }
        if (icon != null) {
            clearChildren()
            add(maskIcon(ImageGetter.getImage("OtherIcons/$icon"))).size(u(16f)).padRight(if(caption.isEmpty())0f else u(6f))
            if (caption.isNotEmpty()) add(label).minWidth(0f)
        }
        onActivation { block() }
    }

    init {
        pickerPane.remove()
        root.name = "technology-atlas"
        root.setFillParent(true)
        stage.addActor(root)
        closeButton.clearActivationActions(ActivationTypes.Tap)
        closeButton.keyShortcuts.clear()
        closeButton.onActivation { back() }
        globalShortcuts.add(KeyCharAndCode.BACK) { back() }
        rightSideButton.clearActivationActions(ActivationTypes.Tap)
        rightSideButton.name = "ui-tech-confirm"
        rightSideButton.onActivation(UncivSound.Paper) { if (freeTechPick) previewResearch() else commitResearch() }
        build()
        initialized = true
        selectedTech?.let { centerOnTechnology(it) }
    }

    override fun getCivilopediaRuleset() = ruleset

    override fun resize(width: Int, height: Int) {
        val changed = hasSafeAreaChanged(width, height)
        val anchor = if (initialized) treeAnchor() else null
        super.resize(width, height)
        if (!initialized || !changed) return
        appearance = makeStyle()
        metrics = TechnologyAtlasLayout(stage.width / appearance.unit, stage.height / appearance.unit)
        build(anchor)
    }

    private fun treeAnchor(): Vector2 {
        val view = Rectangle()
        treeScroll.getViewport(view)
        return view.getCenter(Vector2()).scl(1f / appearance.unit)
    }

    private fun build(anchor: Vector2? = null) {
        root.clearChildren()
        root.pad(u(if (metrics.compact) 6f else 10f), u(metrics.padding), u(if (metrics.compact) 6f else 12f), u(metrics.padding))
        buildHeader()
        buildTree()
        root.add(header).growX().minWidth(0f).height(u(metrics.headerHeight)).row()
        root.add(body).grow().minWidth(0f).minHeight(0f).row()
        showBody()
        root.validate()
        if (anchor != null) Concurrency.runOnGLThread { centerAt(anchor.x * appearance.unit, anchor.y * appearance.unit) }
    }

    private fun buildHeader() {
        header.clearChildren()
        if (UiNavigation.isIos) UiNavigation.styleBackButton(closeButton, "Map")
        else {
            appearance.decorate(closeButton, outlined = true)
            closeButton.setText(if (metrics.stacked) "" else "Back".tr())
            closeButton.clearChildren()
            closeButton.add(maskIcon(ImageGetter.getImage("OtherIcons/BackArrow"))).size(u(18f))
            if (!metrics.stacked) closeButton.add(closeButton.label).padLeft(u(6f))
        }
        header.add(closeButton).minSize(u(44f)).padRight(u(12f))
        val identity = Table().apply {
            left()
            add(label(if (freeTechPick) "Free technology" else "Technology", TextRole.PageTitle))
                .growX().minWidth(0f).left().row()
            val current = civTech.currentTechnology()
            val summary = when {
                freeTechPick -> "[${civTech.freeTechs}] free technologies remaining".tr()
                !GUI.isAllowedChangeState() -> "View only — research cannot be changed".tr()
                current == null -> "No research selected".tr()
                else -> current.name.tr(hideIcons = true) + " · " + "[${civTech.turnsToTech(current.name)}] turns".tr()
            }
            add(label(summary, TextRole.Supporting, appearance.muted).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            if (current != null && !freeTechPick && GUI.isAllowedChangeState()) {
                row()
                val cost = civTech.costOfTech(current.name).coerceAtLeast(1)
                val progress = ImageGetter.ProgressBar(u(72f), u(3f), false).setBackground(appearance.line)
                    .setProgress(appearance.gold, (civTech.researchOfTech(current.name).toFloat() / cost).coerceIn(0f, 1f))
                add(progress).width(u(72f)).height(u(3f)).left().padTop(u(2f))
            }
        }
        header.add(identity).growX().minWidth(0f).padRight(u(8f))
        if (!metrics.stacked)
            header.add(label("${Fonts.science} +${civInfo.stats.statsForNextTurn.science.roundToInt()}", TextRole.Supporting, appearance.gold)).padRight(u(8f))
        header.add(action(if (metrics.stacked) "" else "Search", "ui-tech-search") { openOverlay("search") }).minSize(u(44f))
        if (!freeTechPick)
            header.add(action(if (metrics.stacked) "" else "{Queue} ${draft.size}", "ui-tech-queue") { openOverlay("queue") })
                .minSize(u(44f)).padLeft(u(8f))
    }

    private fun buildTree() {
        nodes.clear()
        val minCol = displayedTechs.minOfOrNull { it.column?.columnNumber ?: 0 } ?: 0
        val maxCol = if (militaryImprovements) 0 else (displayedTechs.maxOfOrNull { it.column?.columnNumber ?: 0 } ?: 0) - minCol
        val rowCount = if (militaryImprovements) displayedTechs.size else displayedTechs.maxOfOrNull { it.row } ?: 1
        val graph = TechnologyAtlasGraph
        val canvasWidth = u((maxCol + 1) * graph.columnStep + graph.inset * 2)
        val rowStep = if (militaryImprovements) graph.nodeHeight + 12f else graph.rowStep
        val canvasHeight = u(rowCount * rowStep + graph.topInset + graph.inset)
        tree = object : WidgetGroup() {
            override fun getPrefWidth() = canvasWidth
            override fun getPrefHeight() = canvasHeight
        }.apply { setSize(canvasWidth, canvasHeight); isTransform = true; touchable = Touchable.childrenOnly }
        val bands = object : Actor() {
            init { setSize(canvasWidth, canvasHeight); touchable = Touchable.disabled }
            override fun draw(batch: Batch, parentAlpha: Float) {
                val old = batch.packedColor
                for ((i, era) in eras.withIndex()) {
                    val techs = displayedTechs.filter { it.era() == era }
                    val first = if (militaryImprovements) 0 else (techs.minOfOrNull { it.column?.columnNumber ?: 0 } ?: minCol) - minCol
                    val last = if (militaryImprovements) 0 else (techs.maxOfOrNull { it.column?.columnNumber ?: 0 } ?: minCol) - minCol
                    val left = u(first * graph.columnStep)
                    val width = u((last-first+1)*graph.columnStep)
                    batch.color = (if (i % 2 == 0) appearance.deep else appearance.background).cpy().apply { a = parentAlpha }
                    batch.draw(ImageGetter.getWhiteDotDrawable().region, left, 0f, width, canvasHeight)
                    batch.color = appearance.quietLine
                    batch.draw(ImageGetter.getWhiteDotDrawable().region, left+width-u(1f), 0f, u(1f), canvasHeight)
                }
                batch.packedColor = old
            }
        }
        tree.addActor(bands)
        for (era in eras) {
            val first = if (militaryImprovements) 0 else (displayedTechs.filter { it.era() == era }.minOfOrNull { it.column?.columnNumber ?: 0 } ?: minCol) - minCol
            tree.addActor(label(if (militaryImprovements) "Military improvements" else era, TextRole.Supporting, appearance.muted).apply {
                setBounds(u(first*graph.columnStep+graph.inset), canvasHeight-u(36f), u(graph.columnStep-graph.inset*2), u(24f))
            })
        }
        tree.addActor(object : Actor() {
            init { setSize(canvasWidth, canvasHeight); touchable = Touchable.disabled }
            override fun draw(batch: Batch, parentAlpha: Float) {
                val old = batch.packedColor
                for (tech in displayedTechs) {
                    val target = nodes[tech.name] ?: continue
                    for (requirement in tech.prerequisites) {
                        val source = nodes[requirement] ?: continue
                        val route = tech.name in draft && (requirement in draft || civTech.isResearched(requirement))
                        batch.color = (if (route) appearance.gold else if (selectedTech?.name == tech.name) appearance.muted else appearance.line).cpy().apply { a = parentAlpha }
                        val thick = u(if (route) 2f else 1f)
                        val x1 = source.x+source.width; val y1 = source.y+source.height/2
                        val x2 = target.x; val y2 = target.y+target.height/2
                        val middle = (x1+x2)/2
                        val dot = ImageGetter.getWhiteDotDrawable().region
                        batch.draw(dot, minOf(x1,middle), y1-thick/2, kotlin.math.abs(middle-x1).coerceAtLeast(thick), thick)
                        batch.draw(dot, middle-thick/2, minOf(y1,y2), thick, kotlin.math.abs(y2-y1).coerceAtLeast(thick))
                        batch.draw(dot, minOf(middle,x2), y2-thick/2, kotlin.math.abs(x2-middle).coerceAtLeast(thick), thick)
                    }
                }
                batch.packedColor = old
            }
        })
        displayedTechs.forEachIndexed { index, tech ->
            val col = if (militaryImprovements) 0 else (tech.column?.columnNumber ?: minCol)-minCol
            val row = if (militaryImprovements) index else (tech.row-1).coerceAtLeast(0)
            val node = createNode(tech)
            node.setBounds(u(col*graph.columnStep+graph.inset),
                canvasHeight-u(graph.topInset+row*rowStep+graph.nodeHeight),
                u(graph.nodeWidth), u(graph.nodeHeight))
            nodes[tech.name] = node
            tree.addActor(node)
        }
        treeScroll = ZoomableScrollPane(minZoom = graph.minZoom, maxZoom = graph.maxZoom).apply {
            name = "ui-tech-map"
            actor = tree
            setOverscroll(false, false)
            setFadeScrollBars(false)
            setScrollingDisabled(false, false)
            zoom(this@TechPickerScreen.zoom)
            onZoomStopListener = { this@TechPickerScreen.setZoom(scaleX) }
        }
        mainView.clearChildren()
        mainView.background = appearance.surface(appearance.deep, 0f)
        mainView.add(buildEraStrip()).growX().height(u(52f)).row()
        val host = object : WidgetGroup() {
            init { addActor(treeScroll) }
            override fun layout() { treeScroll.resizeViewport(Rectangle(0f, 0f, width, height)) }
        }
        mainView.add(host).grow().minWidth(0f).minHeight(0f).row()
        if (metrics.stacked) mainView.add(buildSelectionPeek()).growX().row()
    }

    private fun createNode(tech: Technology): Table = Table().apply {
        name = "ui-tech:${tech.name}"
        touchable = Touchable.enabled
        pad(u(6f))
        val identity = techDisc(tech, 30f)
        add(identity).size(u(30f)).center().padRight(u(8f))
        val info = Table().apply {
            add(label(tech.name, TextRole.Control).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
            add(label(nodeState(tech), TextRole.CompactData, stateColor(tech)).apply { name = "state"; setEllipsis(true) })
                .growX().minWidth(0f).left().padTop(u(2f)).row()
            val all = TechnologyDescriptions.getTechEnabledIcons(tech, civInfo, u(16f), iconsIndex).toList()
            val icons = Table().apply {
                left()
                all.take(3).forEach { add(pictogram(it)).size(u(16f)).padRight(u(5f)) }
                if (all.size > 3) add(label("+${all.size-3}", TextRole.Supporting, appearance.muted))
                if (all.isEmpty()) add(label(if (tech.isContinuallyResearchable()) "Repeatable" else "Technology effects", TextRole.Supporting, appearance.muted))
            }
            add(icons).growX().height(u(18f)).padTop(u(3f))
        }
        add(info).growX().minWidth(0f).top()
        onClick { selectTechnology(tech) }
        onRightClick { selectTechnology(tech); addToQueue() }
        onDoubleClick(UncivSound.Paper) {
            if (freeTechPick) { selectedTech = tech; commitResearch() }
            else if (researchBlock(tech) == null) { setPlan(tech, append = false); commitResearch() }
        }
        decorateNode(this, tech)
    }

    private fun pictogram(actor: Actor): Actor {
        if (actor !is Portrait || actor.isPortrait) return actor
        val icon = maskIcon(actor.image)
        return if (actor is PortraitUnavailableWonderForTechTree) Table().apply {
            background = appearance.outline(appearance.deep, appearance.danger, 4f)
            add(icon).grow().pad(u(2f))
        } else icon
    }

    /** Reuses the original icon's alpha; some original sprites have black RGB pixels. */
    private fun maskIcon(source: Image): Image = object : Image(source.drawable) {
        init { color = appearance.text }
        override fun draw(batch: Batch, parentAlpha: Float) {
            val shader = iconShader ?: createIconShader(batch).also { iconShader = it }
            val previous = batch.shader
            batch.shader = shader
            super.draw(batch, parentAlpha)
            batch.shader = previous
        }
    }

    private fun createIconShader(batch: Batch): ShaderProgram {
        val arrayBatch = batch as? TextureArraySpriteBatch
        val vertex = "attribute vec4 a_position; attribute vec4 a_color; attribute vec2 a_texCoord0; " +
            (if (arrayBatch != null) "attribute float texture_index; varying float v_texture_index; " else "") +
            "uniform mat4 u_projTrans; varying vec4 v_color; varying vec2 v_texCoords; " +
            "void main(){v_color=a_color;v_color.a=v_color.a*(255.0/254.0);v_texCoords=a_texCoord0;" +
            (if (arrayBatch != null) "v_texture_index=texture_index;" else "") +
            "gl_Position=u_projTrans*a_position;}"
        val sampling = if (arrayBatch == null) {
            "uniform sampler2D u_texture; vec4 sampleIcon(){return texture2D(u_texture,v_texCoords);}"
        } else {
            // The game's batch binds a sampler array; constant indices also work on GLES 2.
            val count = arrayBatch.maxTextureUnits
            "varying float v_texture_index; uniform sampler2D u_textures[$count]; vec4 sampleIcon(){" +
                (0 until count).joinToString("") { "if(v_texture_index<$it.5)return texture2D(u_textures[$it],v_texCoords);" } +
                "return vec4(0.0);}"
        }
        val fragment = "#ifdef GL_ES\nprecision mediump float;\n#endif\n" +
            "varying vec4 v_color; varying vec2 v_texCoords; " + sampling +
            "void main(){gl_FragColor=vec4(v_color.rgb,v_color.a*sampleIcon().a);}"
        return ShaderProgram(vertex, fragment).also { check(it.isCompiled) { it.log } }
    }

    override fun dispose() {
        iconShader?.dispose()
        iconShader = null
        super.dispose()
    }

    private fun techDisc(tech: Technology, size: Float): Table = Table().apply {
        background = appearance.outline(appearance.deep, appearance.line, size/2)
        add(pictogram(ImageGetter.getTechIconPortrait(tech.name, u(size*.65f)))).size(u(size*.65f))
    }

    private fun done(tech: Technology) = civTech.isResearched(tech.name) && !tech.isContinuallyResearchable()
    private fun stateColor(tech: Technology): Color = when {
        done(tech) -> appearance.good
        tech.name in draft && !freeTechPick -> appearance.gold
        else -> appearance.muted
    }
    private fun nodeState(tech: Technology): String {
        val state = when {
            done(tech) -> "Researched".tr()
            !freeTechPick && draft.firstOrNull() == tech.name -> "Researching".tr()
            !freeTechPick && tech.name in draft -> "{Queue} ${draft.indexOf(tech.name)+1}".tr()
            civTech.canBeResearched(tech.name) -> "Available".tr()
            civTech.isUnresearchable(tech) -> "Unavailable".tr()
            else -> "Requires prerequisites".tr()
        }
        return if (done(tech)) state else "$state · ${"[${civTech.turnsToTech(tech.name)}] turns".tr()}"
    }
    private fun decorateNode(node: Table, tech: Technology) {
        node.background = appearance.outline(
            when { selectedTech == tech || (!freeTechPick && draft.firstOrNull() == tech.name) -> appearance.raised
                done(tech) || !civTech.canBeResearched(tech.name) -> appearance.background
                else -> appearance.panel },
            if (selectedTech == tech) appearance.gold else if (done(tech)) appearance.quietLine else appearance.line,
            12f, if (selectedTech == tech) 2f else 1f)
        node.findActor<Label>("state")?.apply { setText(nodeState(tech)); setFontColor(stateColor(tech)) }
        node.findActor<Actor>("order")?.remove()
        if (tech.name in draft && !freeTechPick) {
            val badge = Table().apply {
                name = "order"
                background = appearance.surface(appearance.gold, 11f)
                add(label((draft.indexOf(tech.name)+1).toString(), TextRole.Supporting, appearance.ink))
                setBounds(u(TechnologyAtlasGraph.nodeWidth-14f), u(TechnologyAtlasGraph.nodeHeight-14f), u(22f), u(22f))
                touchable = Touchable.disabled
            }
            node.addActor(badge)
        }
    }

    private fun buildEraStrip(): Table {
        eraButtons.clear()
        val row = Table().apply { defaults().height(u(44f)).padRight(u(4f)) }
        eras.forEach { era ->
            val b = action(if (militaryImprovements) "Military improvements" else era.tr().removeSuffix("时代").removeSuffix(" era"), "ui-tech-era:$era") {
                activeEra = era
                displayedTechs.firstOrNull { it.era() == era }?.let { centerOnTechnology(it) }
                updateEraButtons()
            }
            eraButtons[era] = b
            row.add(b)
        }
        updateEraButtons()
        val scroll = ScrollPane(row, BaseScreen.skin).apply { setScrollingDisabled(false, true); setOverscroll(false, false); setFadeScrollBars(true) }
        val bar = Table().apply {
            background = appearance.surface(appearance.background, 0f)
            if (!metrics.stacked) add(action("", "ui-tech-era-previous") { shiftEra(-1) }).size(u(44f))
            add(scroll).growX().minWidth(0f)
            if (!metrics.stacked) add(action("", "ui-tech-era-next") { shiftEra(1) }).size(u(44f))
        }
        Concurrency.runOnGLThread { eraButtons[activeEra]?.let { scroll.scrollTo(it.x,it.y,it.width,it.height,true,false); scroll.updateVisualScroll() } }
        return bar
    }
    private fun updateEraButtons() { eraButtons.forEach { (era, button) -> button.style = appearance.buttonStyle(primary = era == activeEra) } }
    private fun shiftEra(delta: Int) {
        if (eras.isEmpty()) return
        activeEra = eras[(eras.indexOf(activeEra)+delta).coerceIn(0,eras.lastIndex)]
        displayedTechs.firstOrNull { it.era() == activeEra }?.let { centerOnTechnology(it) }
        updateEraButtons()
    }
    private fun setZoom(value: Float) {
        treeScroll.validate()
        val center = Rectangle().also { treeScroll.getViewport(it) }.getCenter(Vector2())
        treeScroll.zoom(value.coerceIn(TechnologyAtlasGraph.minZoom, TechnologyAtlasGraph.maxZoom))
        treeScroll.validate()
        // Zoom keeps the same world point; only an explicit locate action reframes the graph.
        treeScroll.scrollX = center.x
        treeScroll.scrollY = treeScroll.maxY-center.y
        treeScroll.updateVisualScroll()
        treeScroll.updateCulling()
        zoom = treeScroll.scaleX
    }
    private fun centerAt(x: Float, y: Float) {
        treeScroll.validate()
        fun boundedCenter(value: Float, content: Float, viewport: Float) =
            if (content <= viewport) content / 2 else value.coerceIn(viewport / 2, content - viewport / 2)
        treeScroll.scrollX = boundedCenter(x, tree.width, treeScroll.width)
        treeScroll.scrollY = treeScroll.maxY-boundedCenter(y, tree.height, treeScroll.height)
        treeScroll.updateVisualScroll()
        treeScroll.updateCulling()
    }
    private fun centerOnTechnology(tech: Technology) {
        Concurrency.runOnGLThread {
            val node = nodes[tech.name] ?: return@runOnGLThread
            treeScroll.validate()
            val focus = Rectangle(node.x, node.y, node.width, node.height)
            // Show the current node in its research context, without zooming or clipping it.
            val related = tech.prerequisites.mapNotNull { nodes[it] } +
                nodes.values.filter { it !== node && it.x == node.x && kotlin.math.abs(it.y-node.y) <= u(TechnologyAtlasGraph.rowStep+1f) } +
                displayedTechs.filter { tech.name in it.prerequisites }.mapNotNull { nodes[it.name] }
            for (other in related.distinct()) {
                val candidate = Rectangle(focus).merge(Rectangle(other.x, other.y, other.width, other.height))
                if (candidate.width <= treeScroll.width*.94f && candidate.height <= treeScroll.height*.94f)
                    focus.set(candidate)
            }
            centerAt(focus.x+focus.width/2, focus.y+focus.height/2)
        }
    }

    private fun selectTechnology(tech: Technology, center: Boolean = false) {
        if (civInfo.gameInfo.gameParameters.godMode && selectedTech == tech && !civTech.isResearched(tech.name) && GUI.isAllowedChangeState())
            civTech.addTechnology(tech.name)
        selectedTech = tech
        activeEra = tech.era()
        nodes.forEach { (name,node) -> ruleset.technologies[name]?.let { decorateNode(node,it) } }
        updateEraButtons()
        refreshSelection()
        if (center) centerOnTechnology(tech)
    }
    private fun refreshSelection() {
        if (metrics.stacked && mainView.cells.size > 0) {
            val cell = mainView.cells.peek()
            cell.setActor(buildSelectionPeek())
        }
        buildDetails()
        if (freeTechPick) updateConfirm()
    }
    private fun buildSelectionPeek(): Table = Table().apply {
        background = appearance.surface(appearance.panel, 0f)
        pad(u(12f))
        selectedTech?.let { tech ->
            add(techDisc(tech,38f)).size(u(38f)).padRight(u(10f))
            val caption = Table().apply {
                add(label(tech.name,TextRole.Control).apply { setEllipsis(true) }).growX().minWidth(0f).left().row()
                add(label(nodeState(tech),TextRole.CompactData,appearance.muted).apply { setEllipsis(true) }).growX().minWidth(0f).left()
            }
            add(caption).growX().minWidth(0f)
            add(action("View details", "ui-tech-details", outlined = true) { openOverlay("details") }).height(u(44f)).padLeft(u(8f))
        } ?: add(label("No technologies available",TextRole.Supporting,appearance.muted))
        row()
        prepareConfirm()
        add(rightSideButton).growX().minWidth(0f).height(u(44f)).colspan(3).padTop(u(8f))
    }

    private fun showBody() {
        body.clearChildren()
        header.touchable = if (overlay == null) Touchable.childrenOnly else Touchable.disabled
        if (overlay != null) {
            when (overlay) {
                "search" -> body.add(buildSearch()).grow().minWidth(0f).minHeight(0f)
                "queue" -> body.add(buildQueue()).grow().minWidth(0f).minHeight(0f)
                "details" -> { buildDetails(); body.add(detailsView).grow().minWidth(0f).minHeight(0f) }
            }
        } else {
            if (metrics.stacked && mainView.cells.size > 0) mainView.cells.peek().setActor(buildSelectionPeek())
            body.add(mainView).grow().minWidth(0f).minHeight(0f)
            if (!metrics.stacked) {
                buildDetails()
                body.add(detailsView).width(u(metrics.detailsWidth)).growY().minHeight(0f)
            }
        }
        root.validate()
    }
    private fun openOverlay(kind: String) {
        if (overlay == null) lastFocus = stage.keyboardFocus
        overlay = kind
        showBody()
    }
    private fun closeOverlay() {
        overlay = null
        searchField = null
        Gdx.input.setOnscreenKeyboardVisible(false)
        showBody()
        stage.keyboardFocus = lastFocus?.takeIf { it.stage == stage }
        stage.scrollFocus = treeScroll
    }
    private fun overlayHeader(title: String): Table = Table().apply {
        add(label(title,TextRole.SectionTitle)).growX().minWidth(0f).left()
        val close = if (UiNavigation.isIos) UiNavigation.dismissButton().apply {
            name = "ui-tech-overlay-back"
            onActivation { closeOverlay() }
        } else action("Back", "ui-tech-overlay-back", outlined = true) { closeOverlay() }
        add(close).height(u(44f))
    }

    private fun buildDetails() {
        detailsView.clearChildren()
        detailsView.background = appearance.surface(appearance.panel, 0f)
        val tech = selectedTech
        if (tech == null) { detailsView.add(label("No technologies available",TextRole.Supporting,appearance.muted,true)).growX().pad(u(20f)); return }
        if (overlay == "details") detailsView.add(overlayHeader("Technology details")).growX().pad(u(10f)).row()
        val content = Table().apply {
            top().left(); pad(u(if (metrics.compact) 12f else 22f)); defaults().growX().minWidth(0f).left()
        }
        val title = Table().apply {
            add(techDisc(tech, if (metrics.compact) 36f else 50f)).size(u(if (metrics.compact) 36f else 50f)).padRight(u(12f))
            val names = Table().apply {
                add(label(tech.name,TextRole.PageTitle,wrap=true)).growX().minWidth(0f).left().row()
                add(label(tech.era(),TextRole.Supporting,appearance.muted,true)).growX().minWidth(0f).left().padTop(u(4f))
            }
            add(names).growX().minWidth(0f)
        }
        content.add(title).padBottom(u(if(metrics.compact)6f else 12f)).row()
        if (metrics.compact) {
            content.add(label("${civTech.costOfTech(tech.name).tr()} ${Fonts.science} · ${nodeState(tech)}",TextRole.Supporting,stateColor(tech),true)).row()
        } else {
            content.add(label("{Research cost}  ${civTech.costOfTech(tech.name).tr()} ${Fonts.science}",TextRole.Supporting)).row()
            content.add(label(nodeState(tech),TextRole.Supporting,stateColor(tech),true)).padTop(u(5f)).row()
        }
        fun heading(text: String) { content.add(label(text,TextRole.SectionTitle)).padTop(u(if(metrics.compact)8f else 20f)).padBottom(u(if(metrics.compact)4f else 7f)).row() }
        fun chips(technologies: List<Technology>): Table = Table().apply {
            left()
            technologies.forEachIndexed { i,related ->
                val b = action(related.name,"ui-tech-related:${related.name}",outlined=true) { selectTechnology(related,true) }
                b.clearChildren()
                b.add(techDisc(related,22f)).size(u(22f)).padRight(u(6f))
                b.add(b.label).minWidth(0f)
                b.isDisabled = related.name !in nodes
                if (i>0 && i%2==0) row()
                add(b).minWidth(0f).maxWidth(u(if(metrics.compact)100f else 122f)).height(u(44f)).padRight(u(5f)).padBottom(u(4f))
            }
        }
        val prerequisites = tech.prerequisites.mapNotNull { ruleset.technologies[it] }
        if (metrics.compact && prerequisites.size <= 1) {
            val required = Table().apply {
                add(label("Prerequisite technologies",TextRole.Supporting,appearance.muted)).padRight(u(6f))
                if(prerequisites.isEmpty()) add(label("None",TextRole.Supporting,appearance.muted)) else add(chips(prerequisites)).minWidth(0f)
                add().expandX()
            }
            content.add(required).padTop(u(6f)).row()
        } else {
            heading("Prerequisite technologies")
            if (prerequisites.isEmpty()) content.add(label("None",TextRole.Supporting,appearance.muted)).row()
            else content.add(chips(prerequisites)).row()
        }
        heading("Unlocks")
        val unlocked = iconsIndex.enabledUnitsByTech[tech.name].orEmpty().map { Triple(it.name, "Unit", it.makeLink()) } +
            iconsIndex.enabledBuildingsByTech[tech.name].orEmpty().map { Triple(it.name, when { it.isWonder -> "Wonder"; it.isNationalWonder -> "National Wonder"; else -> "Building" }, it.makeLink()) } +
            iconsIndex.resourcesByRevealedTech[tech.name].orEmpty().map { Triple(it.name,"Resource",it.makeLink()) } +
            iconsIndex.improvementsByTechRequired[tech.name].orEmpty().filter { EndlessRules.improvementAllowed(civInfo,it.name) }.map { Triple(it.name,"Improvement",it.makeLink()) }
        unlocked.forEach { (name,kind,link) ->
            val row = Table().apply {
                touchable = Touchable.enabled
                val portrait = when (kind) {
                    "Resource" -> ImageGetter.getResourcePortrait(name,u(26f))
                    "Improvement" -> ImageGetter.getImprovementPortrait(name,u(26f))
                    else -> ImageGetter.getConstructionPortrait(name,u(26f))
                }
                add(pictogram(portrait)).size(u(26f)).padRight(u(10f))
                val info = Table().apply {
                    add(label(name,TextRole.Supporting,wrap=true)).growX().minWidth(0f).left().row()
                    val alreadyBuilt = kind == "Wonder" && !civTech.isResearched(tech.name) && civInfo.gameInfo.getCities().any { it.cityConstructions.isBuilt(name) }
                    val summary = when (kind) {
                        "Unit" -> ruleset.units[name]?.getShortDescription(uniqueExclusionFilter=tech::uniqueIsRequirementForThisTech)
                        "Building", "Wonder", "National Wonder" -> ruleset.buildings[name]?.getShortDescription(uniqueInclusionFilter=tech::uniqueIsNotRequirementForThisTech)
                        else -> null
                    }
                    val meta = if(alreadyBuilt) "{Wonder} · {Already built}".tr() else if(kind=="Resource") "Reveals [$name] on the map".tr() else kind.tr()
                    add(label(meta + if(summary.isNullOrBlank()) "" else " · " + summary.tr(),TextRole.Supporting,appearance.muted,true)).growX().minWidth(0f).left().padTop(u(3f))
                }
                add(info).growX().minWidth(0f)
                add(maskIcon(ImageGetter.getImage("OtherIcons/ForwardArrow"))).size(u(12f)).padLeft(u(8f))
                pad(u(7f),0f,u(7f),0f)
                onClick { openCivilopedia(link) }
            }
            content.add(row).minHeight(u(49f)).row()
        }
        // The canonical description retains conditional abilities, civilization replacements,
        // improvement changes, obsoletions, mod text and campaign-specific explanation in full.
        var description = TechnologyDescriptions.getDescription(tech,civInfo,includeUnlocks=false)
        if (militaryImprovements && tech.name == "Military Transport") description += "\n\n" +
            "道路与铁路维修耗时减少 50%，向上取整且至少 1 回合。已有铁路及其连接收益开局可用；研究本身不增加产能奖励。".tr()
        if(description.isNotBlank()) {
            heading("Technology effects")
            content.add(label(description,TextRole.Body,appearance.muted,true)).row()
        }
        val next = displayedTechs.filter { tech.name in it.prerequisites }
        if (next.isNotEmpty()) {
            heading("Leads to")
            content.add(chips(next)).row()
        }
        content.add(action("Civilopedia","ui-tech-civilopedia") { openCivilopedia(tech.makeLink()) }.apply { keyShortcuts.add(KeyboardBinding.Civilopedia) }).height(u(44f)).padTop(u(12f)).row()
        val scroll = ScrollPane(content,BaseScreen.skin).apply {
            name="ui-tech-detail-scroll"; setScrollingDisabled(true,false); setOverscroll(false,false); setFadeScrollBars(false)
            style = ScrollPane.ScrollPaneStyle(style).apply {
                vScroll = appearance.surface(appearance.quietLine,0f).apply { minWidth=u(3f) }
                vScrollKnob = appearance.surface(appearance.muted,2f).apply { minWidth=u(3f); minHeight=u(24f) }
            }
        }
        detailsView.add(scroll).grow().minWidth(0f).minHeight(0f).row()
        val actions = Table().apply { pad(u(10f),u(12f),u(10f),u(12f)) }
        val block = researchBlock(tech)
        val path = if(block==null) civTech.getRequiredTechsToDestination(tech).map { it.name } else emptyList()
        val message = block ?: if(freeTechPick) "No science cost" else "[${routeTurns(path)}] turns for the full route (including overflow)"
        actions.add(label(message,TextRole.Body,if(block==null)appearance.muted else appearance.danger,true)).growX().colspan(2).padBottom(u(8f)).row()
        if (!freeTechPick) {
            val research = action("Research this route", "ui-tech-research", outlined=true) { previewResearch() }
            research.isDisabled = block != null
            actions.add(research).growX().minWidth(0f).height(u(44f))
            val append = action(if(tech.name in draft) "Queued" else "Add to queue", "ui-tech-add", outlined=true) { addToQueue() }
            append.isDisabled = block != null || tech.name in draft
            if (!metrics.compact) actions.row()
            actions.add(append).growX().minWidth(0f).height(u(44f)).padLeft(if(metrics.compact)u(6f)else 0f).padTop(if(metrics.compact)0f else u(8f))
        }
        if (!metrics.stacked || overlay == "details") {
            actions.row()
            prepareConfirm()
            actions.add(rightSideButton).growX().minWidth(0f).height(u(44f)).colspan(if (freeTechPick || !metrics.compact) 1 else 2).padTop(u(8f))
        }
        detailsView.add(actions).growX().minWidth(0f)
    }

    private fun prepareConfirm() {
        appearance.decorate(rightSideButton, primary = true, size = TextRole.Control)
        updateConfirm()
    }
    private fun updateConfirm() {
        // PickerPane creates its action button with disable(), which also disables hit testing.
        rightSideButton.touchable = Touchable.enabled
        rightSideButton.setText((if(freeTechPick) "Get free technology" else "Confirm research").tr())
        rightSideButton.isDisabled = committed || !GUI.isAllowedChangeState() ||
            if(freeTechPick) selectedTech?.let { researchBlock(it) != null } ?: true else draft.isEmpty() || !validPlan(draft)
    }
    private fun researchBlock(tech: Technology): String? = when {
        committed || !GUI.isAllowedChangeState() -> "Research cannot be changed now"
        done(tech) -> "Already researched"
        civTech.isUnresearchable(tech) -> "Unavailable under the current rules"
        freeTechPick && (civTech.freeTechs <= 0 || !civTech.canBeResearched(tech.name)) -> "Choose a technology with completed prerequisites"
        civTech.getRequiredTechsToDestination(tech).isEmpty() -> "Unavailable under the current rules"
        else -> null
    }
    private fun validPlan(plan: List<String>): Boolean {
        val known = civTech.techsResearched.toMutableSet()
        return plan.all { name ->
            val tech = ruleset.technologies[name]
            val valid = tech != null && !done(tech) && !civTech.isUnresearchable(tech) && tech.prerequisites.all { it in known }
            if(valid) known.add(name)
            valid
        }
    }
    private fun routeTurns(plan: List<String>): String {
        val remaining = (plan.sumOf { civTech.costOfTech(it)-civTech.researchOfTech(it) }-civTech.getOverflowScience()).coerceAtLeast(0)
        val science = civInfo.stats.statsForNextTurn.science
        return if(remaining==0) "0" else if(science<=0f) Fonts.infinity.toString() else ceil(remaining/science.toDouble()).toInt().tr()
    }
    private fun setPlan(tech: Technology, append: Boolean) {
        if(researchBlock(tech)!=null) return
        val path = civTech.getRequiredTechsToDestination(tech).map { it.name }
        if(!append) draft.clear()
        draft.addAll(path.filterNot { it in draft })
        refreshPlan()
    }
    private fun refreshPlan() {
        nodes.forEach { (name,node) -> ruleset.technologies[name]?.let { decorateNode(node,it) } }
        buildHeader(); refreshSelection(); updateConfirm()
        if(overlay=="queue") showBody()
    }
    private fun addToQueue() { selectedTech?.let { setPlan(it, true) } }
    private fun previewResearch() {
        val tech = selectedTech ?: return
        val reason = researchBlock(tech)
        if(reason != null) { ToastPopup(reason,this); return }
        val popup = Popup(this)
        popup.addGoodSizedLabel((if(freeTechPick) "Get free technology" else "Research this route").tr()+" · "+tech.name.tr(hideIcons = true)).row()
        val path = civTech.getRequiredTechsToDestination(tech).map { it.name }
        if(!freeTechPick) popup.addGoodSizedLabel(path.joinToString(" → ") { it.tr(hideIcons = true) }+"\n"+
            "[${routeTurns(path)}] turns for the full route (including overflow)".tr()).row()
        popup.addCloseButton("Cancel",style=appearance.buttonStyle(outlined=true))
        popup.addButton(if(freeTechPick) "Get free technology" else "Set research plan",style=appearance.buttonStyle(primary=true)) {
            popup.close()
            if(freeTechPick) commitResearch() else { setPlan(tech,false); closeOverlay() }
        }.actor.name = "ui-tech-route-apply"
        popup.open()
    }
    private fun commitResearch() {
        if(committed || !GUI.isAllowedChangeState()) return
        if(freeTechPick) {
            val tech = selectedTech ?: return
            if(researchBlock(tech)!=null || civTech.freeTechs<=0 || !civTech.canBeResearched(tech.name)) return
            committed = true
            civTech.getFreeTechnology(tech.name)
        } else {
            if(draft.isEmpty() || !validPlan(draft)) return
            committed = true
            civTech.techsToResearch = ArrayList(draft)
        }
        civTech.updateResearchProgress()
        game.settings.addCompletedTutorialTask("Pick technology")
        game.popScreen()
    }
    private fun back() {
        if(overlay!=null) { closeOverlay(); return }
        if(draft==savedPlan || freeTechPick) { game.popScreen(); return }
        val popup = Popup(this)
        popup.addGoodSizedLabel("Discard changes to the research plan?").row()
        if (UiNavigation.isIos) popup.addCloseButton()
        val keepEditing = popup.addCloseButton("Keep editing", style = if (UiNavigation.isIos) UiNavigation.buttonStyle() else appearance.buttonStyle(outlined = true))
        if (UiNavigation.isIos) UiNavigation.styleTextButton(keepEditing.actor)
        popup.addButton("Discard changes", style = if (UiNavigation.isIos)
            skin.get("negative", com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle::class.java)
            else appearance.buttonStyle(primary = true)) { popup.close(); game.popScreen() }
        popup.open()
    }

    private fun buildSearch(): Table = Table().apply {
        background = appearance.surface(appearance.panel,12f)
        pad(u(16f))
        add(overlayHeader("Search technologies")).growX().row()
        val results = Table().apply { top(); defaults().growX().minWidth(0f) }
        fun update() {
            results.clearChildren()
            val query = searchQuery.trim().lowercase()
            val found = displayedTechs.filter { query.isEmpty() || searchableNames[it.name].orEmpty().contains(query) }
            if(found.isEmpty()) results.add(label("No matching technologies",TextRole.Supporting,appearance.muted,true)).pad(u(24f)).row()
            found.forEach { tech ->
                val b = action("${tech.name.tr(hideIcons = true)}\n${nodeState(tech)}","ui-tech-search-result:${tech.name}",outlined=true) {
                    selectTechnology(tech,true)
                    overlay = if(metrics.stacked) "details" else null
                    Gdx.input.setOnscreenKeyboardVisible(false)
                    stage.keyboardFocus = null
                    showBody()
                }
                results.add(b).height(u(64f)).padBottom(u(6f)).row()
            }
        }
        val field = UncivTextField("Technology, unit, building or resource",searchQuery).apply {
            name = "ui-tech-search-input"
            onChange { searchQuery = text; update() }
        }
        searchField = field
        add(field).growX().height(u(44f)).padTop(u(12f)).padBottom(u(12f)).row()
        val scroll = ScrollPane(results,BaseScreen.skin).apply { setScrollingDisabled(true,false); setOverscroll(false,false) }
        add(scroll).grow().minHeight(0f).minWidth(0f)
        update()
        Concurrency.runOnGLThread { if(overlay=="search") stage.keyboardFocus = field }
    }

    private fun buildQueue(): Table = Table().apply {
        background = appearance.surface(appearance.panel,12f)
        pad(u(16f))
        add(overlayHeader("Research queue")).growX().colspan(2).row()
        add(label("[${routeTurns(draft)}] turns for the full route (including overflow)",TextRole.Supporting,appearance.muted,true)).growX().colspan(2).padTop(u(8f)).padBottom(u(12f)).row()
        val rows = Table().apply { top(); defaults().growX().minWidth(0f) }
        draft.forEachIndexed { index,name ->
            val tech = ruleset.technologies[name] ?: return@forEachIndexed
            val row = Table().apply {
                add(label((index+1).toString(),TextRole.Supporting,appearance.gold)).width(u(22f))
                add(action("${name.tr(hideIcons = true)}\n${"[${civTech.turnsToTech(name)}] turns".tr()}","ui-tech-queue-select:$name") {
                    selectTechnology(tech,true); overlay=if(metrics.stacked)"details" else null; showBody()
                }).growX().minWidth(0f).height(u(58f))
                val move = action("Move up","ui-tech-queue-up:$name",outlined=true) {
                    val reordered = draft.toMutableList()
                    java.util.Collections.swap(reordered,index,index-1)
                    if(validPlan(reordered)) { draft.clear();draft.addAll(reordered);refreshPlan() }
                    else ToastPopup("Prerequisites must be researched first",this@TechPickerScreen)
                }.apply { isDisabled=index==0 || !GUI.isAllowedChangeState() }
                add(move).height(u(44f)).padLeft(u(4f))
                add(action("Remove","ui-tech-queue-remove:$name",outlined=true) { removeFromQueue(name) }.apply {
                    isDisabled = !GUI.isAllowedChangeState()
                }).height(u(44f)).padLeft(u(4f))
            }
            rows.add(row).padBottom(u(7f)).row()
        }
        if(draft.isEmpty()) rows.add(label("No research selected",TextRole.Supporting,appearance.muted,true)).pad(u(24f)).row()
        val scroll = ScrollPane(rows,BaseScreen.skin).apply { setScrollingDisabled(true,false);setOverscroll(false,false) }
        add(scroll).grow().minWidth(0f).minHeight(0f).colspan(2).row()
        add(label("Removing a prerequisite also removes plans that depend on it",TextRole.Supporting,appearance.muted,true)).growX().colspan(2).padTop(u(12f)).padBottom(u(12f)).row()
        add(action("Restore original plan","ui-tech-queue-restore",outlined=true) {
            if(GUI.isAllowedChangeState()) { draft.clear();draft.addAll(savedPlan);refreshPlan() }
        }.apply { isDisabled = !GUI.isAllowedChangeState() || draft==savedPlan }).growX().height(u(44f)).padRight(u(8f))
        add(action("Done","ui-tech-queue-done",primary=true) { closeOverlay() }).growX().height(u(44f))
    }
    private fun removeFromQueue(name: String) {
        if(!GUI.isAllowedChangeState()) return
        val removed = mutableSetOf(name)
        var changed: Boolean
        do {
            changed = false
            draft.forEach { candidate ->
                if(candidate !in removed && ruleset.technologies[candidate]?.prerequisites.orEmpty().any { it in removed && !civTech.isResearched(it) })
                    changed = removed.add(candidate) || changed
            }
        } while(changed)
        draft.removeAll(removed)
        refreshPlan()
    }
}
