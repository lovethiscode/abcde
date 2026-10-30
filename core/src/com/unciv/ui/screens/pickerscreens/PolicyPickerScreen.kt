package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.*
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.TimeUtils
import com.unciv.logic.campaign.CivilWarCampaign
import com.unciv.logic.civilization.Civilization
import com.unciv.models.TutorialTrigger
import com.unciv.models.UncivSound
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr
import com.unciv.ui.components.InputDisabling
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.*
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Concurrency
import kotlin.math.abs
import kotlin.math.max

/** C catalog + A contextual detail. Browsing never adopts a policy. */
class PolicyPickerScreen(
    val viewingCiv: Civilization,
    val canChangeState: Boolean,
    select: String? = null
) : PickerScreen() {
    private val ruleset = viewingCiv.gameInfo.ruleset
    private val policies = viewingCiv.policies
    private val branches = ruleset.policyBranches.values.toList()
    private var appearance = PolicyCodexStyle(UiNavigation.minimumTouchSize() / 44f, 1f)
    private var metrics = PolicyCodexLayout(stage.width / appearance.unit, stage.height / appearance.unit)
    private var selected = ruleset.policies[select] ?: branches.asSequence()
        .filter { policies.isAdopted(it.name) }
        .flatMap { members(it).asSequence() }.firstOrNull { policies.isAdoptable(it) }
        ?: branches.firstOrNull { policies.isAdoptable(it) } ?: branches.firstOrNull()
    private val collapsed = mutableSetOf<String>().apply {
        if (metrics.portrait && metrics.width <= 1000f) addAll(branches.map { it.name }.filter { it != selected?.branch?.name })
    }
    private val root = Table()
    private val header = Table()
    private val body = Table()
    private val catalog = Table()
    private val cards = linkedMapOf<String, Table>()
    private val nodes = linkedMapOf<String, Table>()
    private lateinit var catalogScroll: ScrollPane
    private var detailScroll: ScrollPane? = null
    private var overlayScroll: ScrollPane? = null
    private var overlayLayer: Table? = null
    private var overlay: String? = null
    private var searchQuery = ""
    private var initialized = false
    private var busy = false
    private var confirming = false
    private var confirmation: ConfirmPopup? = null
    private var lastFocus: Actor? = null
    private var lastTappedPolicy: String? = null
    private var lastPolicyTapNanos = 0L

    override val screenClearColor: Color get() = appearance.background
    override fun getCivilopediaRuleset() = ruleset
    private fun u(value: Float) = appearance.u(value)
    private fun label(text: String, size: Int = 13, color: Color = appearance.text, wrap: Boolean = false) =
        appearance.label(text, size, color, wrap)
    private fun members(branch: PolicyBranch) = branch.policies.filter { it.policyBranchType == PolicyBranchType.Member }
    private fun reward(branch: PolicyBranch) = branch.policies.lastOrNull { it.policyBranchType == PolicyBranchType.BranchComplete }
    private fun done(branch: PolicyBranch) = members(branch).count { policies.isAdopted(it.name) }
    private fun readOnly() = !canChangeState || !viewingCiv.isCurrentPlayer() || viewingCiv.isDefeated() || viewingCiv.isSpectator
    private fun modeRestricted() = viewingCiv.gameInfo.endless != null || CivilWarCampaign.isActive(viewingCiv.gameInfo)
    private fun pickable(policy: Policy) = !busy && !readOnly() && policy.policyBranchType != PolicyBranchType.BranchComplete &&
        !policies.isAdopted(policy.name) && policies.isAdoptable(policy) && policies.canAdoptPolicy()

    private fun action(caption: String, id: String, primary: Boolean = false, outlined: Boolean = false,
                       block: () -> Unit): TextButton = appearance.button(caption, primary, outlined, size = UiTypography.size(TextRole.Control, unit = 1f, legacySize = 13)).apply {
        name = id
        onActivation { block() }
    }

    private fun icon(policy: Policy, color: Color = appearance.text): Actor {
        val path = if (policy.policyBranchType == PolicyBranchType.Member) "PolicyIcons/${policy.name}"
            else "PolicyBranchIcons/${policy.branch.name}"
        return ImageGetter.getImage(path).apply { this.color = color }
    }

    private fun verticalScroll(content: Actor, id: String) = ScrollPane(content, skin).apply {
        name = id
        setScrollingDisabled(true, false)
        setOverscroll(false, false)
        setFadeScrollBars(false)
        style = ScrollPane.ScrollPaneStyle(style).apply {
            if (UiNavigation.isMobile) {
                vScroll = null
                vScrollKnob = null
            } else {
                vScroll = appearance.surface(appearance.deep, 0f).apply { minWidth = u(3f) }
                vScrollKnob = appearance.surface(appearance.muted, 2f).apply { minWidth = u(3f); minHeight = u(24f) }
            }
        }
    }

    init {
        pickerPane.remove()
        root.name = "policy-codex"
        root.setFillParent(true)
        // A detail sheet leaves the visible catalog interactive. Only a tap on its
        // background dismisses it; scrolling and policy taps keep their own actions.
        root.addListener(object : UncivActorGestureListener() {
            override fun tap(event: InputEvent?, x: Float, y: Float, count: Int, button: Int) {
                if (overlay != "details" || confirming || busy) return
                var target = event?.target
                while (target != null && target !== root) {
                    if (target.name?.startsWith("ui-policy:") == true) return
                    target = target.parent
                }
                closeOverlay()
            }
        })
        stage.addActor(root)
        closeButton.clearActivationActions(ActivationTypes.Tap)
        closeButton.keyShortcuts.clear()
        closeButton.onActivation { back() }
        globalShortcuts.add(KeyCharAndCode.BACK) { back() }
        globalShortcuts.add(KeyboardBinding.Civilopedia) { selected?.let { openCivilopedia(it.makeLink()) } }
        rightSideButton.clearActivationActions(ActivationTypes.Tap)
        rightSideButton.name = "ui-policy-confirm"
        rightSideButton.onActivation(UncivSound.Policy) { confirmAction() }
        build()
        initialized = true
        selected?.let { reveal(it) }
        displayTutorial(TutorialTrigger.CultureAndPolicies)
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val changed = hasSafeAreaChanged(width, height)
        if (changed) lastTappedPolicy = null
        val anchor = if (initialized) captureAnchor() else null
        val detailY = detailScroll?.scrollY?.div(appearance.unit) ?: 0f
        val detailWasVisible = metrics.docked || overlay == "details"
        super.resize(width, height)
        if (!initialized || !changed) return
        appearance = PolicyCodexStyle(UiNavigation.minimumTouchSize() / 44f, 1f)
        metrics = PolicyCodexLayout(stage.width / appearance.unit, stage.height / appearance.unit)
        if (overlay == "details" && metrics.docked) overlay = null
        else if (overlay == null && detailWasVisible && !metrics.docked) overlay = "details"
        confirmation?.wrapPrompt(minOf(stage.width-u(64f),u(460f)))
        build(anchor)
        detailScroll?.let { it.scrollY = u(detailY); it.updateVisualScroll() }
    }

    /** Keep the same branch and local offset when column count or safe area changes. */
    private fun captureAnchor(): Pair<String, Float>? {
        val top = catalog.height - catalogScroll.scrollY
        val entry = cards.entries.firstOrNull { it.value.y <= top && it.value.y + it.value.height >= top }
            ?: cards.entries.firstOrNull { it.value.y + it.value.height <= top } ?: return null
        return entry.key to ((entry.value.y + entry.value.height - top) / appearance.unit)
    }

    private fun build(anchor: Pair<String, Float>? = if (initialized) captureAnchor() else null) {
        root.clearChildren()
        root.pad(u(metrics.topInset), u(metrics.padding), u(metrics.bottomInset), u(metrics.padding))
        buildHeader()
        buildCatalog()
        root.add(header).growX().minWidth(0f).padBottom(u(metrics.headerGap)).row()
        body.clearChildren()
        val overview = Table().apply {
            if (!metrics.compact) add(catalogToolbar()).growX().minWidth(0f).padBottom(u(10f)).row()
            add(catalogScroll).grow().minWidth(0f).minHeight(0f)
        }
        body.add(overview).grow().minWidth(0f).minHeight(0f)
        if (metrics.docked) {
            body.add(ImageGetter.getWhiteDot().apply { color = appearance.line }).width(u(1f)).growY().padLeft(u(12f)).padRight(u(11f))
            body.add(buildDetails(false)).width(u(metrics.detailWidth)).growY().minHeight(0f)
        }
        root.add(body).grow().minWidth(0f).minHeight(0f).row()
        root.validate()
        if (anchor != null) cards[anchor.first]?.let {
            catalogScroll.scrollY = catalog.height - (it.y + it.height) + u(anchor.second)
            catalogScroll.updateVisualScroll()
        }
        showOverlay()
    }

    private fun buildHeader() {
        header.clearChildren()
        if (UiNavigation.isMobile) UiNavigation.styleBackButton(closeButton, "Map", appearance.unit)
        else {
            appearance.decorate(closeButton)
            closeButton.clearChildren()
            closeButton.add(ImageGetter.getImage("OtherIcons/BackArrow")).size(u(16f))
            if (!metrics.portrait && !metrics.compact) closeButton.add(label("Back", 13)).padLeft(u(6f))
        }
        header.add(closeButton).minSize(u(44f)).padRight(u(if (metrics.compact) 6f else 10f))
        header.add().growX()
        val progress = Table().apply {
            val caption = when {
                branches.isEmpty() -> "No policies available".tr()
                ruleset.policies.values.all { policies.isAdopted(it.name) } -> "All policies adopted".tr()
                policies.freePolicies > 0 -> "[${policies.freePolicies}] free policies remaining".tr()
                else -> "${policies.storedCulture} / ${policies.getCultureNeededForNextPolicy()} ${"Culture".tr()}"
            }
            add(label(caption, 13, if (policies.freePolicies > 0) appearance.gold else appearance.text, !metrics.compact)
                .apply { if (metrics.compact) setEllipsis(true) }).growX().minWidth(0f).left().row()
            val cost = policies.getCultureNeededForNextPolicy().coerceAtLeast(1)
            add(ImageGetter.ProgressBar(u(180f), u(3f), false).setBackground(appearance.line)
                .setProgress(appearance.gold, (policies.storedCulture.toFloat() / cost).coerceIn(0f, 1f)))
                .growX().minWidth(0f).height(u(3f)).padTop(u(if (metrics.compact) 3f else 7f))
        }
        val search = action("", "ui-policy-search") { openOverlay("search") }.apply {
            clearChildren(); add(ImageGetter.getImage("OtherIcons/Search")).size(u(20f))
        }
        if (metrics.compact) {
            // Share one 44-point navigation row; the policy tree owns the remaining height.
            header.add(progress).width(u((metrics.width * .2f).coerceIn(112f, 180f))).padLeft(u(8f)).padRight(u(8f))
            header.add(branchLocator()).minWidth(u(96f)).height(u(44f))
            header.add(foldAllAction()).minWidth(u(96f)).height(u(44f)).padLeft(u(4f))
            header.add(search).size(u(44f)).padLeft(u(4f))
        } else if (metrics.portrait && metrics.width < 700f) {
            header.add(search).size(u(44f))
            header.row()
            header.add(progress).colspan(3).growX().minWidth(0f).padTop(u(12f))
        } else {
            header.add(progress).width(u(if (metrics.compact) 210f else 250f)).padLeft(u(12f))
            header.add(search).size(u(44f)).padLeft(u(12f))
        }
    }

    private fun branchLocator() = action("Find branch", "ui-policy-branches", outlined = true) { openOverlay("branches") }

    private fun foldAllAction(): TextButton {
        val allFolded = branches.all { it.name in collapsed }
        return action(if (allFolded) "Expand all" else "Collapse all", "ui-policy-fold-all") {
            if (allFolded) collapsed.clear() else collapsed.addAll(branches.map { it.name })
            build()
        }
    }

    private fun catalogToolbar() = Table().apply {
        val title = label("Policy codex", 18)
        val locate = branchLocator()
        val fold = foldAllAction()
        val stacked = UiNavigation.isAndroid && title.prefWidth + locate.prefWidth + fold.prefWidth + u(12f) > u(metrics.catalogWidth)
        if (stacked) {
            title.wrap = true
            add(title).colspan(2).growX().minWidth(0f).left().padBottom(u(8f)).row()
            for ((index, button) in listOf(locate, fold).withIndex()) {
                button.label.setEllipsis(false)
                button.label.wrap = true
                button.labelCell.minWidth(0f).growX()
                add(button).growX().minWidth(0f).minHeight(UiNavigation.minimumTouchSize())
                    .padLeft(if (index == 0) 0f else u(4f))
            }
        } else {
            add(title).growX().minWidth(0f).left()
            add(locate).height(u(44f)).padLeft(u(8f))
            add(fold).height(u(44f)).padLeft(u(4f))
        }
    }

    private fun branchStatus(branch: PolicyBranch) = when {
        policies.isAdopted(branch.name) -> "${done(branch)} / ${members(branch).size} ${"Adopted".tr()}"
        (ruleset.eras[branch.era]?.eraNumber ?: 0) > viewingCiv.getEraNumber() -> branch.era.tr()
        else -> "Not adopted".tr()
    }

    private fun buildCatalog() {
        catalog.clearChildren(); cards.clear(); nodes.clear()
        catalog.top().left()
        if (branches.isEmpty()) catalog.add(label("No policies available", 15, appearance.muted, true)).growX().pad(u(20f))
        branches.forEachIndexed { index, branch ->
            val card = buildBranch(branch)
            cards[branch.name] = card
            catalog.add(card).width(u(metrics.branchWidth)).top()
                .padRight(if ((index + 1) % metrics.columns == 0) 0f else u(metrics.gap)).padBottom(u(metrics.gap))
            if ((index + 1) % metrics.columns == 0) catalog.row()
        }
        catalogScroll = verticalScroll(catalog, "ui-policy-catalog-scroll")
    }

    private fun buildBranch(branch: PolicyBranch): Table = Table().apply {
        val active = selected?.branch == branch
        background = appearance.outline(appearance.deep, if (active) appearance.gold else appearance.line)
        pad(u(if (UiNavigation.isMobile) 6f else 9f))
        val title = Table().apply {
            touchable = Touchable.enabled
            name = "ui-policy-fold:${branch.name}"
            add(icon(branch)).size(u(if (metrics.compact) 24f else 28f)).padRight(u(10f))
            val text = Table().apply {
                add(label(branch.name, UiTypography.size(TextRole.SectionTitle, unit = 1f, legacySize = if (metrics.compact) 14 else 16), wrap = true)).growX().minWidth(0f).left().row()
                if (!metrics.compact) add(label(branchStatus(branch), 12, appearance.muted, true)).growX().minWidth(0f).left().padTop(u(3f))
            }
            add(text).growX().minWidth(0f)
            add(ImageGetter.getImage("OtherIcons/BackArrow").apply {
                setSize(u(12f), u(12f))
                setOrigin(Align.center)
                rotation = if (branch.name in collapsed) 180f else 90f
            }).size(u(12f)).padLeft(u(8f))
            onClick {
                if (!collapsed.add(branch.name)) collapsed.remove(branch.name)
                build()
            }
        }
        add(title).growX().minWidth(0f).minHeight(u(if (metrics.compact) 44f else 52f)).row()
        if (branch.name !in collapsed) {
            add(ruleLine()).height(u(1f)).growX().padTop(u(6f)).padBottom(u(4f)).row()
            val opener = action(if (policies.isAdopted(branch.name)) "Branch adopted" else "View adoption effects", "ui-policy:${branch.name}") { selectPolicy(branch) }
            nodes[branch.name] = opener
            add(opener).growX().minWidth(0f).height(u(44f)).row()
            val graph = branchGraph(branch, metrics.branchWidth - 18f)
            // Nonstandard mod grids may be wider; preserve their coordinates instead of overlapping targets.
            val wideGraph = graph.prefWidth > u(metrics.branchWidth - 18f) + .1f
            val graphActor: Actor = if (wideGraph) ScrollPane(graph, BaseScreen.skin).apply {
                setScrollingDisabled(false, true); setOverscroll(false, false)
            } else graph
            add(graphActor).growX().minWidth(0f).height(graph.prefHeight + u(if (wideGraph) 8f else 0f)).row()
            reward(branch)?.let { completion ->
                add(ruleLine()).height(u(1f)).growX().padTop(u(4f)).row()
                val rewardRow = Table().apply {
                    name = "ui-policy:${completion.name}"; touchable = Touchable.enabled
                    val copy = Table().apply {
                        add(label(if (policies.isAdopted(completion.name)) "Completion reward received" else "Completion reward", 12,
                            if (policies.isAdopted(completion.name)) appearance.gold else appearance.text)).growX().minWidth(0f).left().row()
                        add(label(effects(completion).lineSequence().firstOrNull().orEmpty(), 12, appearance.muted).apply { setEllipsis(true) })
                            .growX().minWidth(0f).left().padTop(u(3f))
                    }
                    add(copy).growX().minWidth(0f)
                    add(ImageGetter.getImage("OtherIcons/ForwardArrow")).size(u(12f)).padLeft(u(8f))
                    onClick { selectPolicy(completion) }
                }
                nodes[completion.name] = rewardRow
                add(rewardRow).growX().minWidth(0f).minHeight(u(if (metrics.compact) 48f else 60f))
            }
        }
    }

    private fun ruleLine() = ImageGetter.getWhiteDot().apply { color = appearance.line }

    private fun branchGraph(branch: PolicyBranch, availableWidth: Float): WidgetGroup {
        val entries = members(branch)
        val maxCol = max(5, entries.maxOfOrNull { it.column } ?: 5)
        val maxRow = max(1, entries.maxOfOrNull { it.row } ?: 1)
        val nodeWidth = if (metrics.branchWidth < 360f)
            ((availableWidth - 28f) / 3f).coerceIn(64f, 76f) else 88f
        val nodeHeight = if (metrics.compact) 64f else 94f
        val rowStep = nodeHeight + if (UiNavigation.isMobile) 10f else 18f
        val minColumnDistance = entries.groupBy { it.row }.values.flatMap { row ->
            row.map { it.column }.distinct().sorted().zipWithNext { a, b -> b - a }
        }.minOrNull()?.coerceAtLeast(1) ?: 2
        val spacing = (nodeWidth + 8f) / minColumnDistance
        val graphWidth = max(availableWidth, nodeWidth + 12f + (maxCol - 1) * spacing)
        val graphHeight = maxRow * rowStep + 4f
        val graph = object : WidgetGroup() {
            override fun getPrefWidth() = u(graphWidth)
            override fun getPrefHeight() = u(graphHeight)
        }
        graph.setSize(u(graphWidth), u(graphHeight))
        val coords = entries.associate { policy ->
            policy.name to Vector2(u(6f + (policy.column.coerceAtLeast(1) - 1) * (graphWidth - nodeWidth - 12f) / (maxCol - 1)),
                u(graphHeight - (policy.row.coerceAtLeast(1) - 1) * rowStep - nodeHeight - 4f))
        }
        for (policy in entries) for (required in policy.requires.orEmpty()) {
            val from = coords[required] ?: continue
            val to = coords[policy.name] ?: continue
            val x1 = from.x + u(nodeWidth / 2); val y1 = from.y
            val x2 = to.x + u(nodeWidth / 2); val y2 = to.y + u(nodeHeight)
            val mid = (y1 + y2) / 2
            val color = if (selected?.name == policy.name) appearance.gold else Color.valueOf("7d9ba5")
            fun line(ax: Float, ay: Float, bx: Float, by: Float) {
                if (abs(ax-bx) + abs(ay-by) < .1f) return
                graph.addActor(ImageGetter.getLine(ax, ay, bx, by, u(1.5f)).apply { this.color = color; touchable = Touchable.disabled })
            }
            line(x1,y1,x1,mid); line(x1,mid,x2,mid); line(x2,mid,x2,y2)
        }
        for (policy in entries) {
            val adopted = policies.isAdopted(policy.name)
            val chosen = selected?.name == policy.name
            val node = Table().apply {
                name = "ui-policy:${policy.name}"; touchable = Touchable.enabled
                background = appearance.outline(if (chosen) appearance.raised else appearance.panel,
                    if (chosen) appearance.gold else appearance.line, thickness = if (chosen) 2f else 1f)
                pad(u(4f))
                add(icon(policy, if (adopted) appearance.gold else appearance.text)).size(u(if (metrics.compact) 22f else 30f)).row()
                add(label(policy.name, 12).apply { setAlignment(Align.center); setEllipsis(true) })
                    .growX().minWidth(0f).padTop(u(3f)).row()
                add(label(if (adopted) "Adopted" else if (policies.isAdoptable(policy)) "Requirements met" else "Locked", 11,
                    if (adopted) appearance.gold else appearance.muted).apply { setAlignment(Align.center); setEllipsis(true) })
                    .growX().minWidth(0f)
                onClick {
                    // Selection rebuilds actors. Keep double-tap history on the screen, not the old node listener.
                    val now = TimeUtils.nanoTime()
                    val doubleTap = metrics.docked && lastTappedPolicy == policy.name &&
                        now - lastPolicyTapNanos < (game.settings.multiTapInterval * 1_000_000_000L).toLong()
                    lastTappedPolicy = if (doubleTap) null else policy.name
                    lastPolicyTapNanos = now
                    selectPolicy(policy)
                    if (doubleTap) confirmAction()
                }
            }
            node.setSize(u(nodeWidth),u(nodeHeight))
            val position = coords.getValue(policy.name)
            node.setPosition(position.x,position.y)
            graph.addActor(node); nodes[policy.name] = node
        }
        return graph
    }

    private fun stateText(policy: Policy): String = when {
        policies.isAdopted(policy.name) -> (if (policy.policyBranchType == PolicyBranchType.BranchComplete) "Completion reward received" else "Adopted").tr()
        policy.policyBranchType == PolicyBranchType.BranchComplete -> "Complete the branch to receive this reward".tr()
        readOnly() -> "View only — policies cannot be changed".tr()
        modeRestricted() -> "Policies are unavailable in this game mode".tr()
        (ruleset.eras[policy.branch.era]?.eraNumber ?: 0) > viewingCiv.getEraNumber() -> "{Unlocked at} {${policy.branch.era}}".tr()
        policy.requires.orEmpty().any { !policies.isAdopted(it) } -> "Prerequisite policies required".tr()
        !policies.isAdoptable(policy) -> "Policy conditions not met".tr()
        policies.freePolicies > 0 -> "Adopt free policy".tr()
        policies.storedCulture < policies.getCultureNeededForNextPolicy() -> "[${policies.getCultureNeededForNextPolicy()-policies.storedCulture}] more Culture needed".tr()
        else -> "Adopt policy".tr()
    }

    /** Canonical policy display keeps mod effects, translation and conditional text. */
    private fun effects(policy: Policy) = policy.getDescription().let {
        if (policy.policyBranchType == PolicyBranchType.Member) it.substringAfter('\n', "") else it
    }

    private fun buildDetails(isOverlay: Boolean): Table = Table().apply {
        if (isOverlay) add(overlayHeader("Policy details")).growX().padBottom(u(10f)).row()
        else add(label("Policy details", 13, appearance.muted)).growX().left().height(u(44f)).row()
        val policy = selected
        if (policy == null) { add(label("Select a policy to view its effects",14,appearance.muted,true)).grow(); return@apply }
        val content = Table().apply {
            top().left(); defaults().growX().minWidth(0f).left()
            val title = Table().apply {
                add(icon(policy)).size(u(if (metrics.compact) 32f else 42f)).padRight(u(12f))
                val text = Table().apply {
                    add(label(if (policy.policyBranchType == PolicyBranchType.BranchComplete) "Completion reward" else policy.name,
                        if (metrics.compact) 20 else 23, wrap = true)).growX().minWidth(0f).left().row()
                    add(label(policy.branch.name,12,appearance.muted,true)).growX().minWidth(0f).left().padTop(u(4f))
                }
                add(text).growX().minWidth(0f)
            }
            add(title).padTop(u(8f)).padBottom(u(14f)).row()
            add(label(stateText(policy),12,appearance.gold,true)).padBottom(u(16f)).row()
            add(label(effects(policy),UiTypography.size(TextRole.Body, unit = 1f, legacySize = if(metrics.compact)14 else 15),wrap=true)).row()
            fun heading(text: String) { add(label(text,12,appearance.muted)).padTop(u(20f)).padBottom(u(8f)).row() }
            val requires = if (policy.policyBranchType == PolicyBranchType.BranchComplete) members(policy.branch).map { it.name } else policy.requires.orEmpty()
            if (requires.isNotEmpty()) {
                heading("Prerequisite policies")
                for (name in requires) ruleset.policies[name]?.let { required ->
                    add(action(required.name, "ui-policy-related:${required.name}", outlined = true) { selectPolicy(required) })
                        .minWidth(0f).height(u(44f)).padBottom(u(6f)).row()
                }
            }
            val constraints = policy.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals).toList() +
                policy.getMatchingUniques(UniqueType.Unavailable, GameContext.IgnoreConditionals).toList()
            if (constraints.isNotEmpty()) {
                heading("Policy conditions")
                constraints.forEach { add(label(it.getDisplayText().tr(),UiTypography.size(TextRole.Body, unit = 1f, legacySize = 13),appearance.muted,true)).padBottom(u(6f)).row() }
            }
            if (policy.policyBranchType != PolicyBranchType.BranchComplete) reward(policy.branch)?.let { completion ->
                heading("Completion reward")
                add(label(effects(completion),13,appearance.gold,true)).row()
                val total = members(policy.branch).size
                add(label("[${done(policy.branch)}] of [$total] policies adopted",12,appearance.muted,true)).padTop(u(5f)).row()
            }
        }
        val scroll = verticalScroll(content,"ui-policy-detail-scroll")
        detailScroll = scroll
        add(scroll).grow().minWidth(0f).minHeight(0f).row()
        val controls = Table().apply {
            add(ruleLine()).growX().height(u(1f)).colspan(2).padTop(u(10f)).padBottom(u(10f)).row()
            appearance.decorate(rightSideButton,primary=true)
            rightSideButton.setText(when {
                busy -> "Adopting policy…".tr()
                pickable(policy) -> (if (policies.freePolicies > 0) "Adopt free policy" else if (policy is PolicyBranch) "Adopt branch" else "Adopt policy").tr()
                else -> stateText(policy)
            })
            rightSideButton.touchable = Touchable.enabled
            rightSideButton.isDisabled = !pickable(policy)
            add(rightSideButton).growX().minWidth(0f).height(u(48f))
            if (!metrics.compact) row()
            add(action("Civilopedia", "ui-policy-civilopedia") { openCivilopedia(policy.makeLink()) }).minWidth(0f).height(u(44f))
                .apply { if (!metrics.compact) growX() else padLeft(u(5f)) }
            row()
            val cost = when {
                policies.isAdopted(policy.name) -> "Policy effects remain active".tr()
                policy.policyBranchType == PolicyBranchType.BranchComplete -> "Completion rewards are granted automatically".tr()
                policies.freePolicies > 0 -> "Uses 1 free policy — no Culture cost".tr()
                else -> "Cost: [${policies.getCultureNeededForNextPolicy()}] Culture · Available: [${policies.storedCulture}]".tr()
            }
            add(label(cost,11,appearance.muted,true)).colspan(if(metrics.compact)2 else 1).growX().padTop(u(6f))
        }
        add(controls).growX().minWidth(0f)
    }

    private fun selectPolicy(policy: Policy) {
        val anchor = captureAnchor()
        val wasBrowsing = overlay == null
        if (wasBrowsing) lastFocus = stage.keyboardFocus
        selected = policy
        collapsed.remove(policy.branch.name)
        overlay = if (metrics.docked) null else "details"
        Gdx.input.setOnscreenKeyboardVisible(false)
        stage.keyboardFocus = null
        build(anchor)
        if (!wasBrowsing || metrics.docked) reveal(policy)
    }

    private fun reveal(policy: Policy) {
        root.validate()
        val actor = nodes[policy.name] ?: cards[policy.branch.name] ?: return
        val point = actor.localToAscendantCoordinates(catalog, Vector2())
        catalogScroll.scrollTo(point.x,point.y,actor.width,actor.height,false,false)
        catalogScroll.updateVisualScroll()
    }

    private fun overlayHeader(title: String) = Table().apply {
        add(label(title,18,wrap=true)).growX().minWidth(0f).left()
        val ordinary = UiNavigation.isMobile && viewingCiv.gameInfo.campaign == null && viewingCiv.gameInfo.endless == null
        val dismiss = if (ordinary)
            UiNavigation.dismissButton(appearance.unit).apply {
                name = "ui-policy-overlay-close"
                onActivation { closeOverlay() }
            }
        else action("Close", "ui-policy-overlay-close", outlined=true) { closeOverlay() }
        add(dismiss).height(u(44f)).padLeft(u(8f)).apply { if (ordinary) width(u(44f)) }
    }

    private fun openOverlay(kind: String) {
        lastTappedPolicy = null
        if (overlay == null) lastFocus = stage.keyboardFocus
        overlay = kind
        showOverlay()
    }

    private fun closeOverlay() {
        overlay = null
        Gdx.input.setOnscreenKeyboardVisible(false)
        showOverlay()
        if (metrics.docked) build() // Reattach the shared action button if a detail overlay was open before resizing.
        stage.keyboardFocus = lastFocus?.takeIf { it.stage == stage }
        stage.scrollFocus = catalogScroll
    }

    private fun showOverlay() {
        overlayLayer?.remove(); overlayLayer = null
        root.touchable = when (overlay) {
            null -> Touchable.childrenOnly
            "details" -> Touchable.enabled
            else -> Touchable.disabled
        }
        val kind = overlay ?: return
        overlayScroll = null
        val panel = when (kind) {
            "details" -> buildDetails(true)
            "search" -> buildSearch()
            else -> buildBranchLocator()
        }.apply {
            touchable = Touchable.enabled
            background = appearance.surface(appearance.background)
            pad(u(if(metrics.compact)12f else 20f))
        }
        val layer = Table().apply {
            name = "ui-policy-overlay"
            setFillParent(true)
            background = ImageGetter.getWhiteDotDrawable().tint(Color(0f,0f,0f,if (kind == "details") .18f else .55f))
            touchable = if (kind == "details") Touchable.childrenOnly else Touchable.enabled
            if (metrics.portrait && metrics.width <= 700f) add(panel).grow().minWidth(0f).minHeight(0f)
            else { right();pad(u(12f));add(panel).width(u(if(kind=="details")350f else minOf(460f,metrics.contentWidth))).growY().minHeight(0f) }
        }
        overlayLayer = layer
        stage.addActor(layer)
        layer.validate()
        stage.scrollFocus = if (kind == "details") detailScroll else overlayScroll
        // Resizing must never cover a live confirmation/tutorial with the rebuilt detail sheet.
        stage.actors.filterIsInstance<Popup>().forEach { it.toFront() }
    }

    private fun buildSearch() = Table().apply {
        add(overlayHeader("Search policies")).growX().row()
        val results = Table().apply { top(); defaults().growX().minWidth(0f) }
        fun updateResults() {
            results.clearChildren()
            val query = searchQuery.trim().lowercase()
            val found = ruleset.policies.values.filter { policy ->
                query.isEmpty() || listOf(policy.name, policy.name.tr(),policy.branch.name.tr(),policy.getDescription())
                    .any { it.lowercase().contains(query) }
            }
            if (found.isEmpty()) {
                results.add(label("No matching policies",14,appearance.muted,true)).pad(u(16f)).row()
            }
            for (policy in found) results.add(action("${policy.name.tr(hideIcons=true)}\n${stateText(policy)}",
                "ui-policy-search-result:${policy.name}",outlined=true) { selectPolicy(policy) }).height(u(64f)).padBottom(u(6f)).row()
        }
        add(label("Search by name, branch or effect",12,appearance.muted,true)).growX().left().padTop(u(12f)).row()
        val field = UncivTextField("Search policies",searchQuery).apply {
            name = "ui-policy-search-input"
            onChange { searchQuery = text;updateResults() }
        }
        add(field).growX().minWidth(0f).height(u(44f)).padTop(u(6f)).padBottom(u(12f)).row()
        add(verticalScroll(results,"ui-policy-search-results").also { overlayScroll = it }).grow().minWidth(0f).minHeight(0f)
        updateResults()
        Concurrency.runOnGLThread {
            val screenStage = this@PolicyPickerScreen.stage
            if (overlay == "search" && field.stage === screenStage) screenStage.keyboardFocus = field
        }
    }

    private fun buildBranchLocator() = Table().apply {
        add(overlayHeader("Find branch")).growX().row()
        val list = Table().apply { top();defaults().growX().minWidth(0f) }
        branches.forEach { branch ->
            list.add(action("${branch.name.tr(hideIcons=true)}\n${branchStatus(branch)}", "ui-policy-jump:${branch.name}", outlined=true) {
                collapsed.remove(branch.name)
                overlay = null
                build()
                root.validate()
                cards[branch.name]?.let { catalogScroll.scrollY = catalog.height - it.y - it.height;catalogScroll.updateVisualScroll() }
                this@PolicyPickerScreen.stage.scrollFocus = catalogScroll
            }).height(u(64f)).padBottom(u(6f)).row()
        }
        add(verticalScroll(list,"ui-policy-branch-list").also { overlayScroll = it }).grow().minWidth(0f).minHeight(0f).padTop(u(12f))
    }

    private fun confirmAction() {
        val policy = selected ?: return
        if (confirming || !pickable(policy)) return
        confirming = true
        val costText = if (policies.freePolicies > 0) "Uses 1 free policy — no Culture cost".tr()
            else "Cost: [${policies.getCultureNeededForNextPolicy()}] Culture · Remaining: [${policies.storedCulture-policies.getCultureNeededForNextPolicy()}]".tr()
        val completion = reward(policy.branch)
        val completes = policy.policyBranchType == PolicyBranchType.Member && completion != null &&
            !policies.isAdopted(completion.name) && members(policy.branch).all { it.name == policy.name || policies.isAdopted(it.name) }
        val question = "Are you sure you want to adopt [${policy.name}]?".tr() + "\n\n" + costText +
            (if (completes) "\n\n" + "Completes the [${policy.branch.name}] branch and grants its reward".tr() else "")
        ConfirmPopup(this,question,"Adopt",true,restoreDefault={ confirming=false },
            presentation = ConfirmPopup.Presentation("Are you sure you want to adopt [${policy.name}]?", "Social policies",
                if (completes) "Completes the [${policy.branch.name}] branch and grants its reward" else "",
                if (policies.freePolicies > 0) listOf("Cost" to "Uses 1 free policy — no Culture cost")
                else listOf("Culture cost" to policies.getCultureNeededForNextPolicy().toString(),
                    "Culture remaining" to (policies.storedCulture - policies.getCultureNeededForNextPolicy()).toString()),
                confirmCaption = if (policies.freePolicies > 0) "Adopt free policy"
                    else "Spend [${policies.getCultureNeededForNextPolicy()}] Culture · Adopt")
        ) {
            confirming=false
            if (pickable(policy)) adopt(policy, completes)
        }.apply {
            confirmation = this
            closeListeners.add { confirming = false; confirmation = null }
            wrapPrompt(minOf(this@PolicyPickerScreen.stage.width-u(64f),u(460f)))
        }.open(force=true)
    }

    private fun adopt(policy: Policy, completes: Boolean) {
        if (!pickable(policy)) return
        busy = true
        rightSideButton.isDisabled = true
        Concurrency.run {
            InputDisabling.withInputDisabled { policies.adopt(policy) }
            Concurrency.runOnGLThread {
                busy = false
                // Keep the existing picker handoff for policy side effects that switch screens.
                if (game.screen !== this@PolicyPickerScreen) {
                    if (game.screen !is PolicyPickerScreen) game.popScreen()
                    return@runOnGLThread
                }
                if (completes) selected = reward(policy.branch) ?: policy
                build()
                if (completes) ToastPopup("[${policy.branch.name}] branch completed".tr(),this@PolicyPickerScreen)
            }
        }
    }

    private fun back() {
        if (busy) return
        if (overlay != null) closeOverlay() else game.popScreen()
    }
}
