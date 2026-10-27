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
    private var queueSelection: String? = null
    private var queueDetailOpen = false
    private var queueScroll: ScrollPane? = null
    private var queueOffset = 0f
    private var readingScroll: ScrollPane? = null
    private var readingOffset = 0f
    private var readingTech: String? = null
    private var readingReturnToQueue = false
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
    private fun minimumActionSize() = u(if (UiNavigation.isAndroid) 48f else 44f)
    private val detailsOwnHeader get() = UiNavigation.isMobile && overlay == "details"
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
        if (UiNavigation.isAndroid && (name == "ui-tech-research" || name == "ui-tech-add" || name.startsWith("ui-tech-related:"))) {
            label.setEllipsis(null)
            label.wrap = true
            labelCell.minWidth(0f).growX()
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
        queueScroll?.let { queueOffset = it.scrollY / appearance.unit }; queueScroll = null
        readingScroll?.let { readingOffset = it.scrollY / appearance.unit }; readingScroll = null
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
        if (UiNavigation.isMobile && overlay == "queue") { buildQueueHeader(); return }
        if (detailsOwnHeader) { buildDetailsHeader(); return }
        if (UiNavigation.isMobile) UiNavigation.styleBackButton(closeButton, "Map")
        else {
            appearance.decorate(closeButton, outlined = true)
            closeButton.setText(if (metrics.stacked) "" else "Back".tr())
            closeButton.clearChildren()
            closeButton.add(maskIcon(ImageGetter.getImage("OtherIcons/BackArrow"))).size(u(18f))
            if (!metrics.stacked) closeButton.add(closeButton.label).padLeft(u(6f))
        }
        header.add(closeButton).minSize(minimumActionSize()).padRight(u(12f))
        val identity = Table().apply {
            left()
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
        header.add(action(if (metrics.stacked) "" else "Search", "ui-tech-search") { openOverlay("search") }).minSize(minimumActionSize())
        if (!freeTechPick)
            header.add(action(if (metrics.stacked) "" else "{Queue} ${draft.size}", "ui-tech-queue") { openOverlay("queue") })
                .minSize(minimumActionSize()).padLeft(u(8f))
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
        treeScroll = ZoomableScrollPane(
            minZoom = graph.minZoom,
            maxZoom = graph.maxZoom,
            // Nodes own tap, double-tap and long-press gestures. Capture the two pointers first so
            // a pinch that starts over one or two nodes still reaches the graph zoom listener.
            captureZoomGestures = true,
        ).apply {
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
        val heading = Table().apply {
            add(techDisc(tech, 24f)).size(u(24f)).padRight(u(6f))
            add(label(tech.name, TextRole.CompactData).apply { setEllipsis(true) }).growX().minWidth(0f).left()
        }
        add(heading).growX().minWidth(0f).height(u(24f)).row()

        val all = TechnologyDescriptions.getTechEnabledIcons(tech, civInfo, u(16f), iconsIndex).toList()
        val icons = Table().apply {
            right()
            all.take(3).forEach { add(pictogram(it)).size(u(16f)).padLeft(u(5f)) }
            if (all.size > 3) add(label("+${all.size-3}", TextRole.Supporting, appearance.muted)).padLeft(u(3f))
            if (all.isEmpty() && tech.isContinuallyResearchable())
                add(label("Repeatable", TextRole.Supporting, appearance.muted)).padLeft(u(5f))
        }
        val summary = Table().apply {
            add(label(nodeState(tech), TextRole.CompactData, stateColor(tech)).apply { name = "state"; setEllipsis(true) })
                .growX().minWidth(0f).left()
            add(icons).height(u(18f)).right()
        }
        add(summary).growX().minWidth(0f).height(u(18f)).padTop(u(2f))
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
        if (detailsOwnHeader) { showBody(); return }
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
            add(action("View details", "ui-tech-details", outlined = true) { openOverlay("details") }).minHeight(minimumActionSize()).padLeft(u(8f))
        } ?: add(label("No technologies available",TextRole.Supporting,appearance.muted))
        row()
        prepareConfirm()
        add(rightSideButton).growX().minWidth(0f).minHeight(minimumActionSize()).colspan(3).padTop(u(8f))
    }

    private fun showBody() {
        body.clearChildren()
        if (UiNavigation.isMobile) {
            buildHeader()
            root.getCell(header)?.apply {
                if (detailsOwnHeader) height(Value.prefHeight).minHeight(minimumActionSize())
                else if (overlay == "queue") height(Value.prefHeight).minHeight(u(if (metrics.stacked) 56f else 48f))
                else height(u(metrics.headerHeight))
            }
        }
        header.touchable = if (overlay == null || detailsOwnHeader || (UiNavigation.isMobile && overlay == "queue")) Touchable.childrenOnly else Touchable.disabled
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
        if (kind == "queue") {
            queueDetailOpen = false
            if (queueSelection !in draft) queueSelection = draft.firstOrNull()
        }
        overlay = kind
        showBody()
    }
    private fun closeOverlay() {
        if (UiNavigation.isMobile && overlay == "details" && readingReturnToQueue) {
            readingReturnToQueue = false
            overlay = "queue"
            queueDetailOpen = true
            showBody()
            return
        }
        readingReturnToQueue = false
        overlay = null
        searchField = null
        Gdx.input.setOnscreenKeyboardVisible(false)
        showBody()
        stage.keyboardFocus = lastFocus?.takeIf { it.stage == stage }
        stage.scrollFocus = treeScroll
    }
    private fun overlayHeader(title: String): Table = Table().apply {
        add(label(title,TextRole.SectionTitle)).growX().minWidth(0f).left()
        add(overlayCloseButton()).minSize(minimumActionSize())
    }
    private fun overlayCloseButton(): TextButton =
        if (UiNavigation.isMobile) UiNavigation.dismissButton(appearance.unit).apply {
            name = "ui-tech-overlay-back"
            onActivation { closeOverlay() }
        } else action("Back", "ui-tech-overlay-back", outlined = true) { closeOverlay() }

    private fun buildDetails() {
        detailsView.clearChildren()
        detailsView.background = appearance.surface(appearance.panel, 0f)
        val tech = selectedTech
        if (tech == null) { detailsView.add(label("No technologies available",TextRole.Supporting,appearance.muted,true)).growX().pad(u(20f)); return }
        if (UiNavigation.isMobile) { buildReadingDetails(); return }
        if (overlay == "details") detailsView.add(overlayHeader("Technology details")).growX().pad(u(10f)).row()
        // The short landscape pane is a dense inspector. Full-screen portrait details and wider
        // layouts retain the normal reading roles.
        val detailTitleRole = if (metrics.compact) TextRole.Body else TextRole.SectionTitle
        val detailHeadingRole = if (metrics.compact) TextRole.Supporting else TextRole.Control
        val detailBodyRole = if (metrics.compact) TextRole.Supporting else TextRole.Body
        val detailMetaRole = if (metrics.compact) TextRole.Metadata else TextRole.Supporting
        val content = Table().apply {
            top().left(); pad(u(if (metrics.compact) 12f else 22f)); defaults().growX().minWidth(0f).left()
        }
        val title = Table().apply {
            add(techDisc(tech, if (metrics.compact) 36f else 50f)).size(u(if (metrics.compact) 36f else 50f)).padRight(u(12f))
            val names = Table().apply {
                add(label(tech.name,detailTitleRole,wrap=true)).growX().minWidth(0f).left().row()
                add(label(tech.era(),detailMetaRole,appearance.muted,true)).growX().minWidth(0f).left().padTop(u(4f))
            }
            add(names).growX().minWidth(0f)
        }
        content.add(title).padBottom(u(if(metrics.compact)6f else 12f)).row()
        if (metrics.compact) {
            content.add(label("${civTech.costOfTech(tech.name).tr()} ${Fonts.science} · ${nodeState(tech)}",TextRole.CompactData,stateColor(tech),true)).row()
        } else {
            content.add(label("{Research cost}  ${civTech.costOfTech(tech.name).tr()} ${Fonts.science}",TextRole.Supporting)).row()
            content.add(label(nodeState(tech),TextRole.Supporting,stateColor(tech),true)).padTop(u(5f)).row()
        }
        fun heading(text: String) { content.add(label(text,detailHeadingRole)).padTop(u(if(metrics.compact)8f else 20f)).padBottom(u(if(metrics.compact)4f else 7f)).row() }
        fun chips(technologies: List<Technology>): Table = Table().apply {
            left()
            technologies.forEachIndexed { i,related ->
                val b = action(related.name,"ui-tech-related:${related.name}",outlined=true) { selectTechnology(related,true) }
                b.clearChildren()
                b.add(techDisc(related,22f)).size(u(22f)).padRight(u(6f))
                b.add(b.label).minWidth(0f).growX()
                b.isDisabled = related.name !in nodes
                if (i>0 && (UiNavigation.isAndroid || i%2==0)) row()
                add(b).minWidth(0f).apply {
                    if (UiNavigation.isAndroid) growX() else maxWidth(u(if(metrics.compact)100f else 122f))
                }.minHeight(UiNavigation.minimumTouchSize()).padRight(u(5f)).padBottom(u(4f))
            }
        }
        val prerequisites = tech.prerequisites.mapNotNull { ruleset.technologies[it] }
        if (metrics.compact && prerequisites.size <= 1 && !UiNavigation.isAndroid) {
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
                    add(label(meta + if(summary.isNullOrBlank()) "" else " · " + summary.tr(),detailMetaRole,appearance.muted,true)).growX().minWidth(0f).left().padTop(u(3f))
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
            content.add(label(description,detailBodyRole,appearance.muted,true)).row()
        }
        val next = displayedTechs.filter { tech.name in it.prerequisites }
        if (next.isNotEmpty()) {
            heading("Leads to")
            content.add(chips(next)).row()
        }
        content.add(action("Civilopedia","ui-tech-civilopedia") { openCivilopedia(tech.makeLink()) }.apply { keyShortcuts.add(KeyboardBinding.Civilopedia) }).height(u(44f)).padTop(u(12f)).row()
        val scroll = ScrollPane(content,BaseScreen.skin).apply {
            name="ui-tech-detail-scroll"; setScrollingDisabled(true,false); setOverscroll(false,false)
            style = ScrollPane.ScrollPaneStyle(style).apply {
                // Mobile content remains touch-scrollable without a persistent scrollbar.
                vScroll = null
                vScrollKnob = null
            }
        }
        detailsView.add(scroll).grow().minWidth(0f).minHeight(0f).row()
        val actions = Table().apply { pad(u(10f),u(12f),u(10f),u(12f)) }
        val block = researchBlock(tech)
        val path = if(block==null) civTech.getRequiredTechsToDestination(tech).map { it.name } else emptyList()
        val message = block ?: if(freeTechPick) "No science cost" else "[${routeTurns(path)}] turns for the full route (including overflow)"
        actions.add(label(message,TextRole.Supporting,if(block==null)appearance.muted else appearance.danger,true)).growX().colspan(2).padBottom(u(8f)).row()
        if (!freeTechPick) {
            val research = action("Research this route", "ui-tech-research", outlined=true) { previewResearch() }
            research.isDisabled = block != null
            actions.add(research).growX().minWidth(0f).minHeight(UiNavigation.minimumTouchSize())
            val append = action(if(tech.name in draft) "Queued" else "Add to queue", "ui-tech-add", outlined=true) { addToQueue() }
            append.isDisabled = block != null || tech.name in draft
            if (!metrics.compact || UiNavigation.isAndroid) actions.row()
            actions.add(append).growX().minWidth(0f).minHeight(UiNavigation.minimumTouchSize()).padLeft(if(metrics.compact && !UiNavigation.isAndroid)u(6f)else 0f).padTop(if(metrics.compact && !UiNavigation.isAndroid)0f else u(8f))
        }
        if (!metrics.stacked || overlay == "details") {
            actions.row()
            prepareConfirm()
            actions.add(rightSideButton).growX().minWidth(0f).height(u(44f)).colspan(if (freeTechPick || !metrics.compact || UiNavigation.isAndroid) 1 else 2).padTop(u(8f))
        }
        detailsView.add(actions).growX().minWidth(0f)
    }

    private fun prepareConfirm() {
        // The same actor moves between the reading footer and the portrait graph peek.
        // Restore its intrinsic label width before the shared style rebuilds its cells.
        if (UiNavigation.isMobile) rightSideButton.label.wrap = false
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
        val path = civTech.getRequiredTechsToDestination(tech).map { it.name }
        val popup = object : Popup(this@TechPickerScreen) {
            init {
                if (ordinaryPresentation) {
                    addDialogTitle(if (freeTechPick) "Get free technology" else "Research [${tech.name}] route?", "Research plan")
                    addGoodSizedLabel(path.joinToString(" → ") { it.tr(hideIcons = true) }).row()
                    if (freeTechPick) addGoodSizedLabel("No science cost").row()
                    else {
                        addFactRow("Estimated time", "[${routeTurns(path)}] turns")
                        addGoodSizedLabel("Overflow science is included; research speed can change the estimate.",
                            size = UiTypography.size(TextRole.Supporting, popupUnit)).row()
                    }
                } else {
                    addGoodSizedLabel((if(freeTechPick) "Get free technology" else "Research this route").tr()+" · "+tech.name.tr(hideIcons = true)).row()
                    if(!freeTechPick) addGoodSizedLabel(path.joinToString(" → ") { it.tr(hideIcons = true) }+"\n"+
                        "[${routeTurns(path)}] turns for the full route (including overflow)".tr()).row()
                }
            }
        }
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
        if (UiNavigation.isMobile && overlay == "queue" && metrics.stacked && queueDetailOpen) {
            queueDetailOpen = false; showBody(); return
        }
        if(overlay!=null) { closeOverlay(); return }
        if(draft==savedPlan || freeTechPick) { game.popScreen(); return }
        val popup = Popup(this)
        popup.addGoodSizedLabel("Discard changes to the research plan?").row()
        val keepEditing = popup.addCloseButton("Keep editing", style = if (UiNavigation.isMobile) UiNavigation.buttonStyle() else appearance.buttonStyle(outlined = true))
        if (UiNavigation.isMobile) UiNavigation.styleTextButton(keepEditing.actor)
        popup.addButton("Discard changes", style = if (UiNavigation.isMobile)
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

    private fun buildQueue(): Table {
        if (UiNavigation.isMobile) return buildReadingQueue()
        return buildLegacyQueue()
    }

    private fun buildLegacyQueue(): Table = Table().apply {
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
        val removed = queuedDependents(name)
        draft.removeAll(removed)
        refreshPlan()
    }

    private fun buildDetailMetadata(tech: Technology, inline: Boolean = false): Table = Table().apply {
        val block = researchBlock(tech)
        val cost = label(if (freeTechPick) "No science cost" else "${civTech.costOfTech(tech.name).tr()} ${Fonts.science}",
            TextRole.Supporting, appearance.gold, true)
        val state = label(block ?: nodeState(tech), TextRole.Supporting,
            if (block == null) stateColor(tech) else appearance.muted, true)
        if (inline) {
            cost.wrap = false
            val costWidth = cost.prefWidth
            cost.wrap = true
            add(cost).prefWidth(costWidth).minWidth(0f).fillX().left().padRight(u(8f))
            add(state).growX().minWidth(0f).right()
        } else {
            add(cost).growX().minWidth(0f).right().row()
            add(state).growX().minWidth(0f).right()
        }
    }

    private fun buildDetailsHeader() {
        header.add(UiNavigation.backButton(if (readingReturnToQueue) "Research queue" else "Technology", appearance.unit).apply {
            name = "ui-tech-overlay-back"
            onActivation { closeOverlay() }
        }).minSize(minimumActionSize()).padRight(u(10f))
        val tech = selectedTech
        if (tech == null) {
            header.add(label("No technologies available", TextRole.Supporting, appearance.muted, true)).growX().minWidth(0f)
            return
        }
        header.add(techDisc(tech, 36f)).size(u(36f)).padRight(u(8f))
        header.add(Table().apply {
            add(label(tech.name, TextRole.SectionTitle, wrap = true)).growX().minWidth(0f).left().row()
            add(label(tech.era(), TextRole.Supporting, appearance.muted, true)).growX().minWidth(0f).left()
        }).growX().minWidth(0f)
        if (metrics.stacked) {
            header.row()
            header.add(buildDetailMetadata(tech, inline = true)).colspan(3).growX().minWidth(0f).padTop(u(4f))
        } else header.add(buildDetailMetadata(tech)).width(u(160f)).minWidth(0f).padLeft(u(8f))
    }

    /** Mobile A: reserve the fixed area for identity and the final action, not every research tool. */
    private fun buildReadingDetails() {
        val tech = selectedTech ?: return
        val previous = readingScroll
        if (readingTech == tech.name && previous != null) readingOffset = previous.scrollY / appearance.unit
        else if (readingTech != tech.name) readingOffset = 0f
        readingTech = tech.name
        val block = researchBlock(tech)
        if (!detailsOwnHeader) {
            val identity = Table().apply {
                pad(u(8f), u(12f), u(8f), u(12f))
                add(techDisc(tech, 36f)).size(u(36f)).padRight(u(8f))
                add(Table().apply {
                    add(label(tech.name, TextRole.SectionTitle, wrap = true)).growX().minWidth(0f).left().row()
                    add(label(tech.era(), TextRole.Supporting, appearance.muted, true)).growX().minWidth(0f).left()
                }).growX().minWidth(0f)
                add(buildDetailMetadata(tech)).width(u(if (metrics.compact) 100f else 140f)).minWidth(0f).padLeft(u(8f))
            }
            detailsView.add(identity).growX().minWidth(0f).row()
        }
        val content = Table().apply { top().left(); pad(u(8f), u(12f), u(12f), u(12f)); defaults().growX().minWidth(0f).left() }
        fun heading(text: String) {
            content.add(label(text, TextRole.SectionTitle, wrap = true)).padTop(u(12f)).padBottom(u(4f)).row()
        }
        fun relationships(title: String, technologies: List<Technology>) {
            if (technologies.isEmpty()) return
            heading(title)
            technologies.forEach { related ->
                val button = readingAction(related.name, "ui-tech-related:${related.name}") { selectTechnology(related, true) }
                button.clearChildren()
                button.add(techDisc(related, 22f)).size(u(22f)).padRight(u(8f))
                button.label.setAlignment(Align.left)
                button.add(button.label).growX().minWidth(0f).left()
                button.add(maskIcon(ImageGetter.getImage("OtherIcons/ForwardArrow"))).size(u(12f)).padLeft(u(6f))
                button.isDisabled = related.name !in nodes
                content.add(button).minHeight(minimumActionSize()).row()
            }
        }
        val unlocked = iconsIndex.enabledUnitsByTech[tech.name].orEmpty().map { Triple(it.name, "Unit", it.makeLink()) } +
            iconsIndex.enabledBuildingsByTech[tech.name].orEmpty().map { Triple(it.name, when {
                it.isWonder -> "Wonder"; it.isNationalWonder -> "National Wonder"; else -> "Building"
            }, it.makeLink()) } +
            iconsIndex.resourcesByRevealedTech[tech.name].orEmpty().map { Triple(it.name, "Resource", it.makeLink()) } +
            iconsIndex.improvementsByTechRequired[tech.name].orEmpty().filter { EndlessRules.improvementAllowed(civInfo, it.name) }
                .map { Triple(it.name, "Improvement", it.makeLink()) }
        if (unlocked.isNotEmpty()) {
            content.add(label("Unlocks", TextRole.SectionTitle)).padBottom(u(4f)).row()
            unlocked.forEach { (name, kind, link) ->
                val row = Table().apply {
                    touchable = Touchable.enabled
                    val portrait = when (kind) {
                        "Resource" -> ImageGetter.getResourcePortrait(name, u(26f))
                        "Improvement" -> ImageGetter.getImprovementPortrait(name, u(26f))
                        else -> ImageGetter.getConstructionPortrait(name, u(26f))
                    }
                    add(pictogram(portrait)).size(u(26f)).padRight(u(8f))
                    add(Table().apply {
                        add(label(name, TextRole.Body, wrap = true)).growX().minWidth(0f).left().row()
                        val built = kind == "Wonder" && !civTech.isResearched(tech.name) && civInfo.gameInfo.getCities().any { it.cityConstructions.isBuilt(name) }
                        val summary = when (kind) {
                            "Unit" -> ruleset.units[name]?.getShortDescription(uniqueExclusionFilter = tech::uniqueIsRequirementForThisTech)
                            "Building", "Wonder", "National Wonder" -> ruleset.buildings[name]?.getShortDescription(uniqueInclusionFilter = tech::uniqueIsNotRequirementForThisTech)
                            else -> null
                        }
                        val meta = if (built) "{Wonder} · {Already built}".tr() else if (kind == "Resource") "Reveals [$name] on the map".tr() else kind.tr()
                        add(label(meta + if (summary.isNullOrBlank()) "" else " · " + summary.tr(), TextRole.Supporting, appearance.muted, true))
                            .growX().minWidth(0f).left()
                    }).growX().minWidth(0f)
                    add(maskIcon(ImageGetter.getImage("OtherIcons/ForwardArrow"))).size(u(12f)).padLeft(u(8f))
                    pad(u(4f), 0f, u(4f), 0f)
                    onClick { openCivilopedia(link) }
                }
                content.add(row).minHeight(minimumActionSize()).row()
            }
        }
        var description = TechnologyDescriptions.getDescription(tech, civInfo, includeUnlocks = false)
        if (militaryImprovements && tech.name == "Military Transport") description += "\n\n" +
            "道路与铁路维修耗时减少 50%，向上取整且至少 1 回合。已有铁路及其连接收益开局可用；研究本身不增加产能奖励。".tr()
        if (description.isNotBlank()) {
            heading("Technology effects")
            content.add(label(description, TextRole.Body, appearance.muted, true)).row()
        }
        relationships("Prerequisite technologies", tech.prerequisites.mapNotNull { ruleset.technologies[it] })
        relationships("Leads to", displayedTechs.filter { tech.name in it.prerequisites })
        if (!freeTechPick && block == null) {
            val path = civTech.getRequiredTechsToDestination(tech).map { it.name }
            content.add(label("[${routeTurns(path)}] turns for the full route (including overflow)", TextRole.Supporting, appearance.muted, true))
                .padTop(u(12f)).row()
        }
        content.add(readingAction("Civilopedia", "ui-tech-civilopedia") { openCivilopedia(tech.makeLink()) }.apply {
            keyShortcuts.add(KeyboardBinding.Civilopedia)
        }).minHeight(minimumActionSize()).padTop(u(8f)).row()
        val scroll = readingPane(content, "ui-tech-detail-scroll")
        readingScroll = scroll
        detailsView.add(scroll).grow().minWidth(0f).minHeight(0f).row()
        val actions = Table().apply { pad(u(8f), u(12f), u(8f), u(12f)) }
        if (!freeTechPick) actions.add(readingAction("Research actions", "ui-tech-actions", outlined = true) {
            showResearchActions(tech)
        }.apply { isDisabled = block != null }).growX().minWidth(0f).minHeight(minimumActionSize()).padRight(u(8f))
        // A hidden portrait inspector must not reparent the shared confirmation actor
        // away from the visible selection peek on the technology graph.
        if (!metrics.stacked || overlay == "details") {
            prepareConfirm()
            rightSideButton.label.setEllipsis(null)
            rightSideButton.label.wrap = false
            val confirmWidth = rightSideButton.label.prefWidth
            rightSideButton.label.wrap = true
            rightSideButton.labelCell.prefWidth(confirmWidth).minWidth(0f).growX()
            actions.add(rightSideButton).growX().minWidth(0f).minHeight(minimumActionSize())
        }
        detailsView.add(actions).growX().minWidth(0f)
        Concurrency.runOnGLThread {
            if (readingScroll === scroll) { scroll.layout(); scroll.scrollY = u(readingOffset); scroll.updateVisualScroll() }
        }
    }

    private fun readingAction(caption: String, name: String, primary: Boolean = false, outlined: Boolean = false,
                              block: () -> Unit): TextButton = action(caption, name, primary, outlined, block).apply {
        label.setEllipsis(null)
        label.wrap = false
        val textWidth = label.prefWidth
        label.wrap = true
        // Wrapped Label reports zero prefWidth. Keep its intrinsic width as the preferred,
        // never minimum, so fixed tools stay horizontal and narrow content can still wrap.
        labelCell.prefWidth(textWidth).minWidth(0f).growX()
    }

    private fun readingPane(content: Actor, id: String) = ScrollPane(content, BaseScreen.skin).apply {
        name = id
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        style = ScrollPane.ScrollPaneStyle(style).apply { vScroll = null; vScrollKnob = null }
    }

    private fun showResearchActions(tech: Technology) {
        val popup = Popup(this)
        popup.addGoodSizedLabel("Research actions").row()
        popup.addGoodSizedLabel(tech.name.tr(), size = UiTypography.size(TextRole.SectionTitle, appearance.unit)).row()
        val path = civTech.getRequiredTechsToDestination(tech).map { it.name }
        popup.addGoodSizedLabel("[${routeTurns(path)}] turns for the full route (including overflow)",
            size = UiTypography.size(TextRole.Supporting, appearance.unit)).row()
        popup.addButton("Research this route", style = appearance.buttonStyle(outlined = true)) {
            popup.close(); previewResearch()
        }.actor.apply { name = "ui-tech-research"; isDisabled = researchBlock(tech) != null }
        popup.row()
        popup.addButton(if (tech.name in draft) "Queued" else "Add to queue", style = appearance.buttonStyle(outlined = true)) {
            popup.close(); addToQueue()
        }.actor.apply { name = "ui-tech-add"; isDisabled = researchBlock(tech) != null || tech.name in draft }
        popup.row()
        popup.addCloseButton("Cancel", style = appearance.buttonStyle(outlined = true))
        popup.open()
    }

    /** B queue: the list owns order; the inspector owns dependency explanations and actions. */
    private fun buildQueueHeader() {
        val detail = metrics.stacked && queueDetailOpen
        val back = UiNavigation.backButton(if (detail) "Research queue" else "Technology", appearance.unit).apply {
            name = "ui-tech-overlay-back"
            onActivation { if (detail) { queueDetailOpen = false; showBody() } else closeOverlay() }
        }
        header.add(back).minSize(minimumActionSize()).padRight(u(10f))
        header.add(Table().apply {
            add(label("[${draft.size}] items · Estimated total [${routeTurns(draft)}] turns", TextRole.Supporting, appearance.muted, true))
                .growX().minWidth(0f).left()
        }).growX().minWidth(0f)
        header.add(readingAction("More", "ui-tech-queue-help", outlined = true) { showQueueHelp() }).minSize(minimumActionSize()).padLeft(u(8f))
    }

    private fun buildReadingQueue(): Table = Table().apply {
        background = appearance.surface(appearance.panel, 0f)
        val oldScroll = queueScroll
        if (oldScroll != null) queueOffset = oldScroll.scrollY / appearance.unit
        if (queueSelection !in draft) queueSelection = draft.firstOrNull()
        val detailWidth = minOf(290f, metrics.contentWidth * .4f)
        val list = Table().apply { top(); defaults().growX().minWidth(0f) }
        fun queueHeading(): Table = Table().apply {
            pad(0f, u(8f), 0f, u(8f))
            add(label("Queue order", TextRole.Supporting, appearance.muted)).width(u(40f)).left()
            add(label("Technology", TextRole.Supporting, appearance.muted)).growX().minWidth(0f).left().padLeft(u(30f))
            add(label("Item time", TextRole.Supporting, appearance.muted, true)).width(u(if (metrics.stacked) 76f else 86f)).right()
        }
        val rows = Table().apply { top(); defaults().growX().minWidth(0f) }
        draft.forEachIndexed { index, name ->
            val tech = ruleset.technologies[name] ?: return@forEachIndexed
            val button = readingAction(name, "ui-tech-queue-select:$name") {
                queueSelection = name
                queueDetailOpen = true
                showBody()
            }
            button.clearChildren()
            button.pad(0f, u(8f), 0f, u(8f))
            button.style = TextButton.TextButtonStyle(button.style).apply {
                up = appearance.surface(if (queueSelection == name) appearance.raised else appearance.panel, 0f)
                checked = up
                over = appearance.surface(appearance.raised, 0f)
                down = over
            }
            button.add(label((index + 1).toString(), TextRole.CompactData, appearance.gold)).width(u(40f)).left()
            button.add(techDisc(tech, 22f)).size(u(22f)).padRight(u(8f))
            button.label.setAlignment(Align.left)
            button.add(button.label).growX().minWidth(0f).left()
            button.add(label("[${civTech.turnsToTech(name)}] turns", TextRole.Body,
                appearance.text, true)).width(u(if (metrics.stacked) 76f else 86f)).right().padLeft(u(4f))
            rows.add(button).minHeight(minimumActionSize()).row()
        }
        if (draft.isEmpty()) rows.add(label("No research selected", TextRole.Body, appearance.muted, true)).pad(u(20f)).row()
        rows.add(label("Item time excludes waiting for earlier technologies.", TextRole.Supporting, appearance.muted, true))
            .pad(u(12f)).row()
        val scroll = readingPane(rows, "ui-tech-queue-scroll")
        val listVisible = !metrics.stacked || !queueDetailOpen || draft.isEmpty()
        queueScroll = if (listVisible) scroll else null
        list.add(queueHeading()).growX().minHeight(u(28f)).row()
        list.add(scroll).grow().minHeight(0f)
        val panels = Table()
        if (listVisible) panels.add(list).grow().minWidth(0f).minHeight(0f)
        if (queueSelection != null && (!metrics.stacked || queueDetailOpen)) {
            panels.add(buildQueueInspector()).apply {
                if (metrics.stacked) grow() else width(u(detailWidth)).growY()
            }.minWidth(0f).minHeight(0f)
        }
        add(panels).grow().minWidth(0f).minHeight(0f).row()
        Concurrency.runOnGLThread {
            if (queueScroll === scroll) { scroll.layout(); scroll.scrollY = u(queueOffset); scroll.updateVisualScroll() }
        }
    }

    private fun buildQueueInspector(): Table = Table().apply {
        background = appearance.surface(appearance.background, 0f)
        pad(u(12f))
        val name = queueSelection ?: return@apply
        val tech = ruleset.technologies[name] ?: return@apply
        val index = draft.indexOf(name)
        val content = Table().apply { top(); defaults().growX().minWidth(0f).left() }
        content.add(Table().apply {
            add(techDisc(tech, 32f)).size(u(32f)).padRight(u(8f))
            add(label(name, TextRole.SectionTitle, wrap = true)).growX().minWidth(0f).left()
        }).row()
        content.add(label("Queue position [${index + 1}] · [${civTech.turnsToTech(name)}] turns for this item", TextRole.Supporting, appearance.muted, true))
            .padTop(u(4f)).row()
        content.add(label("Prerequisite technologies", TextRole.SectionTitle, wrap = true)).padTop(u(12f)).padBottom(u(4f)).row()
        val prerequisites = tech.prerequisites.filterNot { civTech.isResearched(it) }
        content.add(label(if (prerequisites.isEmpty()) "Prerequisites completed" else
            "Research first: [${prerequisites.joinToString(", ") { it.tr(hideIcons = true) }}]", TextRole.Body, wrap = true)).row()
        val affected = queuedDependents(name).size - 1
        content.add(label(if (affected == 0) "Removing this item leaves other plans unchanged." else
            "Removing this item also removes [${affected}] dependent plans.", TextRole.Supporting, appearance.muted, true)).padTop(u(8f)).row()
        if (index > 0 && !canMoveQueueUp(name)) content.add(label("Prerequisites must be researched first", TextRole.Supporting, appearance.muted, true)).padTop(u(8f)).row()
        content.add(readingAction("View details", "ui-tech-queue-view-tech:$name") {
            selectTechnology(tech, true)
            readingReturnToQueue = true
            overlay = "details"
            showBody()
        }).minHeight(minimumActionSize()).padTop(u(8f)).row()
        add(readingPane(content, "ui-tech-queue-inspector-scroll")).grow().minHeight(0f).row()
        val actions = Table().apply {
            add(readingAction("Move up one place", "ui-tech-queue-up:$name", outlined = true) {
                if (GUI.isAllowedChangeState() && canMoveQueueUp(name)) {
                    val position = draft.indexOf(name)
                    java.util.Collections.swap(draft, position, position - 1)
                    refreshPlan()
                }
            }.apply { isDisabled = !GUI.isAllowedChangeState() || !canMoveQueueUp(name) }).growX().minWidth(0f).minHeight(minimumActionSize()).padRight(u(8f))
            add(readingAction("Remove", "ui-tech-queue-remove:$name", outlined = true) { confirmRemoveQueue(name) }.apply {
                isDisabled = !GUI.isAllowedChangeState()
            }).growX().minWidth(0f).minHeight(minimumActionSize())
        }
        add(actions).growX().minWidth(0f).padTop(u(8f))
    }

    private fun canMoveQueueUp(name: String): Boolean {
        val index = draft.indexOf(name)
        if (index <= 0) return false
        val reordered = draft.toMutableList()
        java.util.Collections.swap(reordered, index, index - 1)
        return validPlan(reordered)
    }

    private fun queuedDependents(name: String): Set<String> {
        val removed = mutableSetOf(name)
        var changed: Boolean
        do {
            changed = false
            draft.forEach { candidate ->
                if (candidate !in removed && ruleset.technologies[candidate]?.prerequisites.orEmpty().any { it in removed && !civTech.isResearched(it) })
                    changed = removed.add(candidate) || changed
            }
        } while (changed)
        return removed
    }

    private fun confirmRemoveQueue(name: String) {
        if (!GUI.isAllowedChangeState()) return
        val removed = queuedDependents(name)
        val popup = Popup(this)
        popup.addGoodSizedLabel("Remove [${removed.size}] research plans?").row()
        popup.addGoodSizedLabel(draft.filter { it in removed }.joinToString(" · ") { it.tr(hideIcons = true) }).row()
        popup.addGoodSizedLabel("Dependent plans are included. Researched technologies are kept.", size = UiTypography.size(TextRole.Body, appearance.unit)).row()
        popup.addCloseButton("Cancel", style = appearance.buttonStyle(outlined = true))
        popup.addButton("Remove", style = appearance.buttonStyle(outlined = true)) { popup.close(); removeFromQueue(name) }
            .actor.name = "ui-tech-queue-remove-confirm"
        popup.open()
    }

    private fun confirmRestoreQueue() {
        if (!GUI.isAllowedChangeState() || draft == savedPlan) return
        val popup = Popup(this)
        popup.addGoodSizedLabel("Restore the research plan from when this screen was opened?").row()
        popup.addCloseButton("Keep editing", style = appearance.buttonStyle(outlined = true))
        popup.addButton("Restore original plan", style = appearance.buttonStyle(primary = true)) {
            popup.close()
            if (GUI.isAllowedChangeState()) { draft.clear(); draft.addAll(savedPlan); refreshPlan() }
        }.actor.name = "ui-tech-queue-restore-confirm"
        popup.open()
    }

    private fun showQueueHelp() {
        val popup = Popup(this)
        popup.addGoodSizedLabel("Research queue").row()
        popup.addGoodSizedLabel("Technologies are researched from top to bottom.").row()
        popup.addGoodSizedLabel("Item time excludes waiting for earlier technologies.").row()
        popup.addGoodSizedLabel("Overflow science is included; research speed can change the estimate.").row()
        popup.addGoodSizedLabel("Removing a prerequisite also removes plans that depend on it").row()
        popup.addGoodSizedLabel("Return to the technology screen to keep editing. Confirm research there to apply the draft.").row()
        popup.addButton("Restore original plan", style = appearance.buttonStyle(outlined = true)) {
            popup.close(); confirmRestoreQueue()
        }.actor.apply { name = "ui-tech-queue-restore"; isDisabled = !GUI.isAllowedChangeState() || draft == savedPlan }
        popup.row()
        popup.addCloseButton("OK", style = appearance.buttonStyle(primary = true))
        popup.open()
    }
}
