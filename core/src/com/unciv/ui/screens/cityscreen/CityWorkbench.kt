package com.unciv.ui.screens.cityscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.city.CityFocus
import com.unciv.models.stats.Stat
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.*
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.input.onChange
import com.unciv.ui.screens.pickerscreens.CityRenamePopup
import com.unciv.ui.screens.basescreen.BaseScreen
import kotlin.math.roundToInt

/** The city workbench arranges existing city controls; all mutations still go through CityView. */
internal class CityWorkbench(
    private val screen: CityScreen,
    private val constructions: CityConstructionsTable,
    private val stats: CityStatsTable,
    private val tile: CityScreenTileTable,
    private val detail: ConstructionInfoTable,
    private val map: CityMapHolder,
    private val managementActions: () -> Table
) : Table() {
    private val unit = UiNavigation.minimumTouchSize() / 44f
    private var geometry = CityWorkbenchLayout(screen.stage.width / unit, screen.stage.height / unit)
    private val city get() = screen.cityView
    private val panelColor = Color.valueOf("203f4a")
    private val gold = Color.valueOf("e4c48a")
    private val header = Table()
    private val yields = Table()
    private val panel = Table()
    private val productionPanel = Table()
    private val mapControls = Table()
    private val mapHeader = Table()
    private val exitButton = UiNavigation.backButton("Map", unit = 1f).apply {
        name = "ui-city-exit"
        onActivation { screen.exit() }
    }
    private val content = Table()
    private val contentPane = AutoScrollPane(content)
    private var detailedStats: Actor? = null
    var page = if (geometry.tablet) "Citizens" else "Production"
    var cityPage = "Overview"
    var queueOpen = false
    private var manageOpen = false
    private var mapOnly = false
    private var expanded = false
    private var statsFilter: Stat? = Stat.Production
    private var statsDetailed = false
    private var lastContentKey = ""
    private val scrollPositions = mutableMapOf<String, Float>()
    internal val isPhone get() = !geometry.tablet
    internal val isExpanded get() = expanded && !geometry.tablet && !geometry.portrait

    val bodyWidth get() = (if (geometry.tablet) geometry.production.width else geometry.management.width) - 24f
    val managementWidth get() = geometry.management.width - 24f

    fun systemBack() {
        when {
            screen.selectedConstruction != null || screen.selectedTile != null -> { screen.clearSelection(); update() }
            mapOnly -> { mapOnly = false; update() }
            manageOpen || queueOpen -> { manageOpen = false; queueOpen = false; update() }
            geometry.tablet && screen.selectedConstruction != null -> { screen.clearSelection(); update() }
            expanded -> { expanded = false; update() }
            else -> screen.exit()
        }
    }

    init {
        name = "ui-city-workbench"
        isTransform = true
        setScale(unit)
        setSize(geometry.width, geometry.height)
        // The real map is a sibling in stage coordinates so its zoom/pan implementation stays intact.
        background = null
        listOf(yields, panel, productionPanel, mapControls, mapHeader, exitButton).forEach(::addActor)
        if (!geometry.tablet) addActor(header)
        header.defaults().space(4f)
        contentPane.setOverscroll(false, false)
        contentPane.setScrollingDisabled(true, false)
        panel.background = ImageGetter.getWhiteDotDrawable().tint(panelColor)
        productionPanel.background = ImageGetter.getWhiteDotDrawable().tint(panelColor)
        panel.name = "ui-city-management-panel"
        productionPanel.name = "ui-city-production-panel"
        mapControls.name = "ui-city-map-controls"
        screen.globalShortcuts.add(KeyboardBinding.ShowStats) { showPage("Stats") }
        screen.globalShortcuts.add(KeyboardBinding.CitizenManagement) { showPage("Citizens") }
        screen.globalShortcuts.add(KeyboardBinding.SpecialistDetail) { showPage("Citizens") }
        screen.globalShortcuts.add(KeyboardBinding.GreatPeopleDetail) { showPage("City", "Great People") }
        screen.globalShortcuts.add(KeyboardBinding.ReligionDetail) { showPage("City", "Religion") }
        screen.globalShortcuts.add(KeyboardBinding.BuildingsDetail) { showPage("City", "Buildings") }
        if (!geometry.tablet) for (focus in CityFocus.entries.filter { it.tableEnabled }) {
            screen.globalShortcuts.add(focus.binding) {
                if (screen.canCityBeChanged()) { city.trySetCityFocus(focus); screen.updateAsync() }
            }
        }
        for ((binding, category) in listOf(KeyboardBinding.BuildUnits to "Units",
            KeyboardBinding.BuildBuildings to "Buildings", KeyboardBinding.BuildWonders to "Wonders",
            KeyboardBinding.BuildNationalWonders to "National Wonders", KeyboardBinding.BuildOther to "Other",
            KeyboardBinding.BuildDisabled to "Disabled")) screen.globalShortcuts.add(binding) {
            constructions.selectWorkbenchCategory(category)
            showPage(if (geometry.tablet) page else "Production")
        }
    }

    private fun button(text: String, id: String, action: () -> Unit): TextButton =
        text.tr(hideIcons = true).toTextButton(TextButton.TextButtonStyle(BaseScreen.skin.get(TextButton.TextButtonStyle::class.java)).apply {
            up = ImageGetter.getWhiteDotDrawable().tint(Color.valueOf("2c505c"))
            down = ImageGetter.getWhiteDotDrawable().tint(Color.valueOf("496269"))
            checked = down
            fontColor = Color.valueOf("f1f1e7")
            checkedFontColor = gold
        }, unit = 1f).apply {
            label.setFontSize(UiTypography.size(TextRole.Control, unit = 1f, legacySize = 15))
            name = id
            label.setEllipsis(true)
            labelCell.minWidth(0f).pad(4f)
            onClick { action() }
        }

    private fun showPage(target: String, section: String = cityPage) {
        page = target; cityPage = section; manageOpen = false
        screen.clearSelection(); updatePanels()
    }

    private fun title(text: String) = text.toLabel(fontSize = UiTypography.size(TextRole.SectionTitle, unit = 1f, legacySize = 18), fontColor = gold)

    private fun navigationIcon(image: String, text: String, id: String, action: () -> Unit) =
        button("", id, action).apply {
            clearChildren()
            add(ImageGetter.getImage(image)).size(20f)
            addTooltip(text)
        }

    private fun cityPageButton(forward: Boolean) = UiNavigation.directionButton(
        forward, if (forward) "Next city" else "Previous city", unit = 1f,
    ).apply {
        name = if (forward) "ui-city-next" else "ui-city-previous"
        isDisabled = screen.viewableCities.size < 2
        onActivation { if (!isDisabled) screen.page(if (forward) 1 else -1) }
    }

    fun update() {
        geometry = CityWorkbenchLayout(screen.stage.width / unit, screen.stage.height / unit, expanded)
        if (!geometry.tablet) {
            updatePhoneHeader()
            updatePanels()
            updatePhoneMap()
            return
        }
        detailedStats = null
        header.clear()
        header.add(cityPageButton(forward = false)).size(44f)
        header.add(button(city.name.tr(hideIcons = true) + "  " + city.getPopulationCount(), "ui-city-name") {
            if (screen.canChangeState) CityRenamePopup(screen, city) { update() }
        }).minWidth(0f).growX().height(44f)
        header.add(cityPageButton(forward = true)).size(44f)
        header.add(navigationIcon("OtherIcons/Options", "City management", "ui-city-manage") {
            manageOpen = !manageOpen; screen.clearSelection(); update()
        }).size(44f)
        exitButton.setBounds(geometry.margin, geometry.height - 46f, exitButton.prefWidth, 44f)
        updateYields()
        updatePanels()
        updateMap()
        spaceCityWorkbenchIcons(header)
        spaceCityWorkbenchIcons(mapHeader)
        spaceCityWorkbenchIcons(mapControls)
    }

    private fun updateYields() {
        yields.clear()
        yields.add().width(exitButton.prefWidth + 8f).height(44f)
        val visibleStats = Stat.entries.filter { it != Stat.Faith || city.viewingCiv().isReligionEnabled() }
        visibleStats.forEachIndexed { index, stat ->
            val amount = if (stat == Stat.Happiness) city.getHappinessList().values.sum() else city.getCurrentCityStats()[stat]
            val item = Table().apply {
                name = "ui-city-yield:${stat.name}"
                val focus = CityFocus.safeValueOf(stat)
                if (focus == city.getCityFocus()) background = ImageGetter.getWhiteDotDrawable().tint(Color.valueOf("496269"))
                add(ImageGetter.getStatIcon(stat.name)).size(20f).padRight(8f)
                add(amount.roundToInt().toString().toLabel(fontSize = UiTypography.size(TextRole.Body, unit = 1f, legacySize = 17)))
                addTooltip(stat.name)
                if (screen.canCityBeChanged()) onActivation(binding = if (city.getCityFocus() == focus) CityFocus.NoFocus.binding else focus.binding) {
                    city.trySetCityFocus(if (city.getCityFocus() == focus) CityFocus.NoFocus else focus)
                    screen.updateAsync()
                }
            }
            yields.add(item).minWidth(0f).growX().height(44f)
            if (geometry.portrait && !geometry.tablet && index == 2) yields.row()
        }
        yields.setBounds(geometry.margin, geometry.contentTop, geometry.width - geometry.margin * 2, geometry.yieldsHeight)
    }

    private fun tabs(): Table = Table().apply {
        val pages = if (geometry.tablet) listOf("Citizens", "City", "Stats") else listOf("Production", "Citizens", "City", "Stats")
        val controls = pages.map { entry -> button(entry, "ui-city-tab:$entry") {
            page = entry; manageOpen = false; screen.clearSelection(); updatePanels()
        }.apply {
            isChecked = entry == page
            if (entry == page) label.color = gold
            label.setEllipsis(false)
            spaceCityWorkbenchIcons(this)
        } }
        // Allocate the actual label width before sharing spare space. Equal-width tabs split
        // "Production" even when the complete set fits comfortably in the panel.
        val columns = (controls.size downTo 1).first { count ->
            controls.chunked(count).all { row -> row.sumOf { maxOf(44f, it.prefWidth).toDouble() } <= geometry.management.width }
                || count == 1
        }
        controls.forEachIndexed { index, control ->
            add(control).growX().minWidth(maxOf(44f, control.prefWidth)).minHeight(if (geometry.tablet) 46f else 44f)
            if ((index + 1) % columns == 0) row()
        }
    }

    private fun updatePanels() {
        if (!geometry.tablet) { updatePhonePanels(); return }
        panel.clear()
        panel.setBounds(geometry.management.x, geometry.management.y, geometry.management.width, geometry.management.height)
        productionPanel.clear()
        productionPanel.isVisible = geometry.tablet && !mapOnly
        productionPanel.setBounds(geometry.production.x, geometry.production.y, geometry.production.width, geometry.production.height)
        panel.isVisible = !mapOnly
        panel.add(header).growX().minWidth(0f).height(44f).pad(4f).row()
        if (geometry.tablet) {
            productionPanel.add(title("Production")).left().pad(12f).row()
            productionPanel.add(productionContent()).grow().minSize(0f)
        }
        val bottomTabs = geometry.portrait && !geometry.tablet
        if (!bottomTabs) panel.add(tabs()).growX().row()
        content.clear()
        content.top().left().pad(12f)
        content.defaults().growX().minWidth(0f)
        val selected = screen.selectedConstruction
        val selectedTile = screen.selectedTile
        when {
            manageOpen -> {
                content.add(title("City management")).left().row()
                content.add(button("Rename city", "ui-city-rename") {
                    if (screen.canChangeState) CityRenamePopup(screen, city) { update() }
                }).height(44f).row()
                content.add(managementActions()).row()
            }
            selectedTile != null -> {
                addClose("Tile")
                tile.update(selectedTile)
                content.add(tile).row()
            }
            selected != null && (!geometry.tablet || city.constructions.isBuilt(selected.name)) -> {
                addClose(selected.name)
                detail.update(selected)
                content.add(detail).row()
                if (!city.constructions.isBuilt(selected.name)) content.add(constructions.workbenchActions(selected)).row()
            }
            page == "Production" && !geometry.tablet -> Unit
            page == "Citizens" -> content.add(stats.workbenchPage("Citizens")).row()
            page == "City" -> {
                val navigation = Table()
                for (entry in listOf("Overview", "Great People", "Religion", "Buildings")) {
                    if (entry == "Religion" && !city.viewingCiv().isReligionEnabled()) continue
                    navigation.add(button(entry, "ui-city-section:$entry") { cityPage = entry; updatePanels() }
                        .apply { isChecked = entry == cityPage }).minWidth(0f).growX().height(44f)
                }
                content.add(navigation).row()
                content.add(stats.workbenchPage(cityPage)).padTop(12f).row()
            }
            page == "Stats" -> {
                if (detailedStats == null) detailedStats = DetailedStatsPopup(screen).workbenchContent()
                content.add(detailedStats).row()
            }
        }
        if (page == "Production" && !geometry.tablet && selected == null && selectedTile == null && !manageOpen)
            panel.add(productionContent()).grow().minSize(0f).row()
        else panel.add(contentPane).grow().minSize(0f).row()
        if (bottomTabs) panel.add(tabs()).growX().row()
        spaceCityWorkbenchIcons(panel)
        spaceCityWorkbenchIcons(productionPanel)
        panel.invalidateHierarchy()
    }

    private fun addClose(text: String) {
        val row = Table()
        row.add(title(text).apply { wrap = true }).minWidth(0f).growX()
        row.add(UiNavigation.dismissButton(unit = 1f).apply {
            name = "ui-city-detail-close"
            onActivation { screen.clearSelection(); manageOpen = false; queueOpen = false; update() }
        }).size(44f)
        content.add(row).row()
    }

    private fun productionContent(): Actor {
        if (screen.isSpying) return "Production information is not available".toLabel(TextRole.Body, unit = 1f).apply { wrap = true }
        if (geometry.tablet && screen.selectedConstruction != null && !city.constructions.isBuilt(screen.selectedConstruction!!.name)) {
            val table = Table().apply { top().pad(12f) }
            table.add(UiNavigation.backButton(unit = 1f).apply {
                name = "ui-city-production-back"
                onActivation { screen.clearSelection(); update() }
            }).height(44f).row()
            detail.update(screen.selectedConstruction)
            table.add(detail).growX().row()
            table.add(constructions.workbenchActions(screen.selectedConstruction!!)).growX().row()
            return AutoScrollPane(table).apply { setOverscroll(false, false) }
        }
        return constructions.workbenchContent(bodyWidth, queueOpen) { queueOpen = !queueOpen; updatePanels() }
    }

    private fun updateMap() {
        val bounds = if (mapOnly) Rectangle(geometry.margin, 12f, geometry.width - geometry.margin * 2, geometry.contentTop - 12f) else geometry.map
        val changed = kotlin.math.abs(map.width * map.scaleX - bounds.width * unit) > .1f ||
            kotlin.math.abs(map.height * map.scaleY - bounds.height * unit) > .1f
        if (changed) map.resizeViewport(Rectangle(bounds.x * unit, bounds.y * unit, bounds.width * unit, bounds.height * unit))
        else map.setPosition(bounds.x * unit, bounds.y * unit)
        mapHeader.clear()
        mapHeader.add(city.name.toLabel(fontSize = UiTypography.size(TextRole.PageTitle, unit = 1f, legacySize = 14)).apply { setEllipsis(true) }).minWidth(0f).growX()
        mapHeader.setBounds(bounds.x + 10f, bounds.y + bounds.height - 54f, bounds.width - 20f, 44f)
        mapControls.clear()
        mapControls.add("Click to assign population".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = 1f, legacySize = 11), hideIcons = true).apply { wrap = true }).growX().minWidth(0f)
        mapControls.add(button("Buy tile", "ui-city-buy-mode") {
            city.chooseNewTileToOwn()?.let { screen.selectWorkbenchTile(it); screen.updateAsync() }
        }).minWidth(0f).height(44f).padLeft(8f)
        val mapToggle = button(if (mapOnly) "Back" else "Map", "ui-city-map-full") {
            mapOnly = !mapOnly; update()
        }
        if (mapOnly) UiNavigation.styleBackButton(mapToggle, unit = 1f)
        mapControls.add(mapToggle).height(44f).padLeft(8f)
        mapControls.setBounds(bounds.x + 10f, bounds.y + 10f, bounds.width - 20f, 44f)
    }

    private fun updatePhoneHeader() {
        header.clear()
        val width = geometry.width - geometry.margin * 2
        header.setSize(width, 44f)
        header.add(exitButton).minWidth(exitButton.prefWidth).minHeight(44f).padRight(4f)
        if (!geometry.portrait) header.add(cityPageButton(forward = false)).size(44f)
        val identity = button("", "ui-city-name", ::showCityPicker).apply {
            clearChildren()
            pad(0f, 4f, 0f, 4f)
            val nameLabel = city.name.toLabel(TextRole.PageTitle, unit = 1f).apply { wrap = true }
            add(nameLabel).minWidth(0f).growX().left()
            if (geometry.portrait) row()
            add(("{Population} " + city.getPopulationCount()).toLabel(TextRole.Supporting, unit = 1f)).left().padLeft(if (geometry.portrait) 0f else 8f)
            addTooltip(city.name)
        }
        header.add(identity).growX().minWidth(0f).minHeight(44f)
        if (!geometry.portrait) header.add(cityPageButton(forward = true)).size(44f)
        header.add(button(if (expanded) "Collapse details" else "Expand details", "ui-city-expand") {
            expanded = !expanded; mapOnly = false; update(); screen.updateAsync()
        }.apply {
            label.setEllipsis(false)
            label.wrap = true
            if (geometry.portrait) {
                clearChildren()
                add(ImageGetter.getImage("OtherIcons/" + if (expanded) "BackArrow" else "ArrowRight")).size(20f)
                addTooltip(if (expanded) "Collapse details" else "Expand details")
            }
        }).apply { if (geometry.portrait) size(44f) else minWidth(104f).minHeight(44f) }
        header.add(phoneIcon("OtherIcons/Options", "City management", "ui-city-manage") {
            manageOpen = !manageOpen; screen.clearSelection(); updatePanels()
        }).size(44f)
        spaceCityWorkbenchIcons(header)
        // Measure after assigning the real width, so wrapped identity/action text reserves
        // its actual height instead of being clipped by a fixed phone header budget.
        header.invalidateHierarchy()
        header.validate()
        val height = maxOf(44f, header.prefHeight)
        geometry = CityWorkbenchLayout(geometry.width, geometry.height, expanded, height)
        header.setBounds(geometry.margin, geometry.height - height, width, height)
        header.validate()
    }

    private fun phoneIcon(image: String, caption: String, id: String, action: () -> Unit) =
        navigationIcon(image, caption, id, action).apply { pad(0f) }

    private fun showCityPicker() {
        val popup = CityWorkbenchPopup(screen, "Cities", city.name)
        val list = Table()
        screen.viewableCities.forEachIndexed { index, view ->
            val choice = view.name.toTextButton(unit = UiTypography.unitsPerPoint(screen.stage)).apply {
                name = "ui-city-pick:$index"
                onActivation {
                popup.close()
                screen.page(index - screen.viewableCities.indexOf(city))
                }
            }
            choice.label.wrap = true
            list.add(choice).width(minOf(360f * unit, screen.stage.width - 64f * unit)).minHeight(44f * unit).padBottom(4f * unit).row()
        }
        popup.add(AutoScrollPane(list).apply { setScrollingDisabled(true, false) }).growX().row()
        popup.addCloseButton()
        popup.open()
    }

    private fun rememberContentScroll() {
        if (lastContentKey.isNotEmpty() && contentPane.parent != null)
            scrollPositions[lastContentKey] = contentPane.scrollY
    }

    private fun phoneDetailHeader(caption: String): Table = Table().apply {
        add(UiNavigation.backButton(unit = 1f).apply {
            name = "ui-city-detail-close"
            onActivation {
                when {
                    screen.selectedConstruction != null || screen.selectedTile != null -> screen.clearSelection()
                    manageOpen -> manageOpen = false
                    else -> queueOpen = false
                }
                update()
            }
        }).minHeight(44f).padRight(8f)
        add(title(caption).apply { wrap = true }).minWidth(0f).growX().left()
    }

    private fun updatePhonePanels() {
        rememberContentScroll()
        panel.clear()
        productionPanel.isVisible = false
        panel.isVisible = !mapOnly
        val b = geometry.management
        panel.setBounds(b.x, b.y, b.width, b.height)
        panel.add(tabs()).growX().minHeight(44f).row()
        content.clear()
        content.top().left().pad(8f, 12f, 8f, 12f)
        content.defaults().growX().minWidth(0f)
        val selected = screen.selectedConstruction
        val selectedTile = screen.selectedTile
        lastContentKey = "$page:$cityPage:${selected?.name ?: ""}:${selectedTile?.position() ?: ""}:$manageOpen"
        when {
            manageOpen -> {
                panel.add(phoneDetailHeader("City management")).growX().minHeight(44f).pad(4f, 12f, 4f, 12f).row()
                content.add(button("Rename city", "ui-city-rename") {
                    if (screen.canChangeState) CityRenamePopup(screen, city) { update() }
                }.apply { isDisabled = !screen.canChangeState }).minHeight(44f).row()
                content.add(managementActions()).row()
            }
            selectedTile != null -> {
                panel.add(phoneDetailHeader("Tile")).growX().minHeight(44f).pad(4f, 12f, 4f, 12f).row()
                tile.update(selectedTile)
                content.add(tile).row()
            }
            selected != null -> {
                panel.add(phoneDetailHeader(selected.name)).growX().minHeight(44f).pad(4f, 12f, 4f, 12f).row()
                detail.update(selected)
                content.add(detail).row()
            }
            page == "Production" -> {
                if (queueOpen) panel.add(phoneDetailHeader("City construction queue")).growX().minHeight(44f).pad(4f, 12f, 4f, 12f).row()
                panel.add(productionContent()).grow().minSize(0f).row()
                spaceCityWorkbenchIcons(panel)
                panel.invalidateHierarchy()
                return
            }
            page == "Stats" -> {
                panel.add(DetailedStatsPopup(screen).workbenchContent(statsFilter, statsDetailed) { stat, detailed ->
                    statsFilter = stat; statsDetailed = detailed
                }).grow().minSize(0f).row()
                spaceCityWorkbenchIcons(panel)
                return
            }
            page == "Citizens" -> content.add(stats.workbenchPage("Citizens")).row()
            page == "City" -> {
                val sections = listOf("Overview", "Great People", "Religion", "Buildings")
                    .filter { it != "Religion" || city.viewingCiv().isReligionEnabled() }
                if (cityPage !in sections) cityPage = "Overview"
                val selector = cityWorkbenchSelectBox().apply {
                    setItems(*sections.map { it.tr() }.toTypedArray())
                    selectedIndex = sections.indexOf(cityPage)
                    name = "ui-city-section-picker"
                    onChange { cityPage = sections[selectedIndex]; updatePanels() }
                }
                val navigation = Table().apply {
                    add("City".toLabel(TextRole.Control, unit = 1f)).padRight(8f)
                    add(selector).growX().minWidth(0f).minHeight(44f)
                }
                panel.add(navigation).growX().padLeft(12f).padRight(12f).row()
                content.add(stats.workbenchPage(cityPage)).row()
            }
        }
        panel.add(contentPane).grow().minSize(0f).row()
        if (selected != null) {
            val actions = if (city.constructions.isBuilt(selected.name)) detail.fixedWorkbenchActions()
                else constructions.workbenchActions(selected)
            if (actions.hasChildren()) panel.add(actions).growX().pad(8f, 12f, 8f, 12f).row()
        }
        contentPane.layout()
        contentPane.scrollY = scrollPositions[lastContentKey] ?: 0f
        contentPane.updateVisualScroll()
        spaceCityWorkbenchIcons(panel)
        panel.invalidateHierarchy()
    }

    internal fun revealMapForPlacement() {
        expanded = false
        mapOnly = false
        update()
    }

    private fun updatePhoneMap() {
        val hidden = expanded && !mapOnly
        map.isVisible = !hidden
        mapControls.isVisible = !hidden
        yields.isVisible = !hidden
        mapHeader.isVisible = false
        if (hidden) return
        val bounds = if (mapOnly) Rectangle(geometry.margin, 0f,
            geometry.width - geometry.margin * 2, geometry.contentTop) else geometry.map
        map.resizeViewport(Rectangle(bounds.x * unit, bounds.y * unit, bounds.width * unit, bounds.height * unit))
        yields.clear()
        yields.background = ImageGetter.getWhiteDotDrawable().tint(Color.valueOf("102b35").apply { a = .96f })
        val stats = Stat.entries.filter { it != Stat.Faith || city.viewingCiv().isReligionEnabled() }
        val items = stats.map { stat ->
            val amount = if (stat == Stat.Happiness) city.getHappinessList().values.sum() else city.getCurrentCityStats()[stat]
            Table().apply {
                name = "ui-city-yield:${stat.name}"
                pad(0f, 4f, 0f, 4f)
                add(ImageGetter.getStatIcon(stat.name)).size(16f).padRight(6f)
                add(amount.roundToInt().toString().toLabel(TextRole.CompactData, unit = 1f))
                addTooltip(stat.name)
                onActivation { statsFilter = stat; showPage("Stats") }
            }
        }
        val columns = (items.size downTo 1).first { count ->
            items.chunked(count).all { row -> row.sumOf { maxOf(44f, it.prefWidth).toDouble() } <= bounds.width }
                || count == 1
        }
        items.forEachIndexed { index, item ->
            yields.add(item).minWidth(maxOf(44f, item.prefWidth)).growX().minHeight(44f)
            if ((index + 1) % columns == 0) yields.row()
        }
        val yieldsHeight = ((items.size + columns - 1) / columns) * 44f
        yields.setBounds(bounds.x, bounds.y + bounds.height - yieldsHeight - 4f, bounds.width, yieldsHeight)
        mapControls.clear()
        mapControls.add(button("Citizen Management", "ui-city-assign") { showPage("Citizens") }).growX().minWidth(0f).minHeight(44f)
        mapControls.add(button("Buy tile", "ui-city-buy-mode") {
            city.chooseNewTileToOwn()?.let { screen.selectWorkbenchTile(it); screen.updateAsync() }
        }.apply { isDisabled = !screen.canCityBeChanged() }).growX().minWidth(0f).minHeight(44f).padLeft(8f)
        mapControls.add(phoneIcon("OtherIcons/" + if (mapOnly) "BackArrow" else "ArrowRight", if (mapOnly) "Back" else "Map", "ui-city-map-full") {
            mapOnly = !mapOnly; update()
        }).size(44f).padLeft(8f)
        mapControls.setBounds(bounds.x + 8f, bounds.y + 8f, bounds.width - 16f, 44f)
        spaceCityWorkbenchIcons(yields)
    }

    fun restore(other: CityWorkbench) {
        page = other.page; cityPage = other.cityPage; queueOpen = other.queueOpen
        expanded = other.expanded; mapOnly = other.mapOnly
        statsFilter = other.statsFilter; statsDetailed = other.statsDetailed
        other.rememberContentScroll()
        scrollPositions.putAll(other.scrollPositions)
        constructions.restoreWorkbench(other.constructions)
        // A queue index belongs to a city, unlike its display preferences.
        if (city == other.city) screen.selectedQueueEntry = other.screen.selectedQueueEntry
        if (geometry.tablet && page == "Production") page = "Citizens"
        update()
    }
}

/** SelectBox popups attach to the stage, outside the workbench's point-sized transform. */
internal fun cityWorkbenchSelectBox(): com.badlogic.gdx.scenes.scene2d.ui.SelectBox<String> {
    val style = com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle(
        BaseScreen.skin.get(com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle::class.java))
    style.font = UiTypography.controlFont(unit = 1f)
    style.listStyle = com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle(style.listStyle)
    style.listStyle.font = UiTypography.controlFont()
    style.listStyle.selection = ImageGetter.getWhiteDotDrawable().tint(Color.valueOf("496269")).apply {
        val padding = maxOf(0f, (UiNavigation.minimumTouchSize() - style.listStyle.font.capHeight + style.listStyle.font.descent * 2) / 2)
        topHeight = padding
        bottomHeight = padding
    }
    return com.badlogic.gdx.scenes.scene2d.ui.SelectBox(style)
}
