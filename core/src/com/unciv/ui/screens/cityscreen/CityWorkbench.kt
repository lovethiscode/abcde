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
    private val geometry = CityWorkbenchLayout(screen.stage.width / unit, screen.stage.height / unit)
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
    val bodyWidth get() = (if (geometry.tablet) geometry.production.width else geometry.management.width) - 24f
    val managementWidth get() = geometry.management.width - 24f

    init {
        name = "ui-city-workbench"
        isTransform = true
        setScale(unit)
        setSize(geometry.width, geometry.height)
        // The real map is a sibling in stage coordinates so its zoom/pan implementation stays intact.
        background = null
        listOf(yields, panel, productionPanel, mapControls, mapHeader, exitButton).forEach(::addActor)
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

    fun update() {
        detailedStats = null
        header.clear()
        header.add(button("‹", "ui-city-previous") { screen.page(-1) }.apply {
            isDisabled = screen.viewableCities.size < 2
        }).size(44f)
        header.add(button(city.name.tr(hideIcons = true) + "  " + city.getPopulationCount(), "ui-city-name") {
            if (screen.canChangeState) CityRenamePopup(screen, city) { update() }
        }).minWidth(0f).growX().height(44f)
        header.add(button("›", "ui-city-next") { screen.page(1) }.apply {
            isDisabled = screen.viewableCities.size < 2
        }).size(44f)
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
                add(amount.roundToInt().toString().toLabel(fontSize = UiTypography.size(TextRole.Body, unit = 1f, legacySize = 17))).padRight(4f)
                add("".toLabel(fontSize = UiTypography.size(TextRole.Supporting, unit = 1f, legacySize = 11)).apply {
                    setText(stat.name.tr(hideIcons = true, hideStats = true))
                }).minWidth(0f)
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
        for (entry in pages) add(button(entry, "ui-city-tab:$entry") {
            page = entry; manageOpen = false; screen.clearSelection(); updatePanels()
        }.apply { isChecked = entry == page; if (entry == page) label.color = gold }).growX().minWidth(0f).height(46f)
    }

    private fun updatePanels() {
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

    fun restore(other: CityWorkbench) {
        page = other.page; cityPage = other.cityPage; queueOpen = other.queueOpen
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
