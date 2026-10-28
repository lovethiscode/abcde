package com.unciv.ui.screens.modmanager

import com.unciv.ui.components.fonts.TextRole
import com.unciv.models.metadata.ModCategories
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.Popup
import kotlin.math.sign

/** The popup and inline picker share values, never Scene2D actors. */
internal class ModManagementOptions(
    private val modManagementScreen: ModManagementScreen,
    previous: State? = null
) {
    companion object {
        val sortByName = Comparator { a, b: ModUIData -> a.name.compareTo(b.name, true) }
        val sortByNameDesc = Comparator { a, b: ModUIData -> b.name.compareTo(a.name, true) }
        val sortByDate = Comparator { a, b: ModUIData -> a.lastUpdated().compareTo(b.lastUpdated()) }
        val sortByDateDesc = Comparator { a, b: ModUIData -> b.lastUpdated().compareTo(a.lastUpdated()) }
        val sortByStars = Comparator { a, b: ModUIData ->
            10 * (b.stargazers() - a.stargazers()) + a.name.compareTo(b.name, true).sign
        }
        val sortByStatus = Comparator { a, b: ModUIData ->
            10 * (b.stateSortWeight() - a.stateSortWeight()) + a.name.compareTo(b.name, true).sign
        }
        const val installedHeaderText = "Current mods"
        const val onlineHeaderText = "Downloadable mods"
    }

    enum class SortType(val label: String, val symbols: String, val comparator: Comparator<in ModUIData>) {
        Name("Name ${Fonts.sortUpArrow}", Fonts.sortUpArrow.toString(), sortByName),
        NameDesc("Name ${Fonts.sortDownArrow}", Fonts.sortDownArrow.toString(), sortByNameDesc),
        Date("Date ${Fonts.sortUpArrow}", "${Fonts.clock}${Fonts.sortUpArrow}", sortByDate),
        DateDesc("Date ${Fonts.sortDownArrow}", "${Fonts.clock}${Fonts.sortDownArrow}", sortByDateDesc),
        Stars("Stars ${Fonts.sortDownArrow}", "${Fonts.star}${Fonts.sortDownArrow}", sortByStars),
        Status("Status ${Fonts.sortDownArrow}", "${Fonts.status}${Fonts.sortDownArrow}", sortByStatus);
        fun next() = entries[(ordinal + 1) % entries.size]
        companion object {
            fun fromSelectBox(selectBox: TranslatedSelectBox) = entries.firstOrNull { it.label == selectBox.selected.value } ?: Name
        }
    }
    data class State(val text: String, val category: String, val installed: SortType, val online: SortType)
    class Filter(val text: String, val topic: String)
    val searchField = UncivTextField("Search mods").apply { text = previous?.text.orEmpty() }
    var category = ModCategories.asSequence().firstOrNull { it.topic == previous?.category } ?: ModCategories.default()
    var sortInstalled = previous?.installed ?: SortType.Name
    var sortOnline = previous?.online ?: SortType.Stars

    fun getFilter() = Filter(searchField.text, category.topic)
    fun snapshot() = State(searchField.text, category.topic, sortInstalled, sortOnline)
    fun clearFilter() {
        category = ModCategories.default()
        searchField.text = ""
        modManagementScreen.refreshInstalledModTable()
        modManagementScreen.refreshOnlineModTable()
    }

    fun inlineSort(installed: Boolean): TranslatedSelectBox = sortPicker(installed)

    private fun sortPicker(installed: Boolean) = TranslatedSelectBox(
        SortType.entries.filter { !installed || it != SortType.Stars }.map { it.label },
        (if (installed) sortInstalled else sortOnline).label
    ).apply {
        onChange {
            if (installed) sortInstalled = SortType.fromSelectBox(this)
            else sortOnline = SortType.fromSelectBox(this)
            modManagementScreen.refreshInstalledModTable()
            modManagementScreen.refreshOnlineModTable()
            modManagementScreen.refreshSortPicker()
        }
    }

    fun openPopup() {
        val popup = Popup(modManagementScreen, ordinaryShell = true)
        val width = popup.bodyWidth
        if (UiNavigation.isMobile) popup.addTitleWithCloseButton()
        val categorySelect = TranslatedSelectBox(ModCategories.asSequence().map { it.label }.toList(), category.label)
        categorySelect.onChange {
            category = ModCategories.fromSelectBox(categorySelect)
            modManagementScreen.refreshInstalledModTable()
            modManagementScreen.refreshOnlineModTable()
        }
        for ((label, select) in listOf("Category:" to categorySelect,
            "Sort Current:" to sortPicker(true), "Sort Downloadable:" to sortPicker(false))) {
            popup.add(ModManagementStyle.label(label, TextRole.Control)).width(width).left().padBottom(6f).row()
            popup.add(select).width(width).minHeight(ModManagementStyle.touchHeight).padBottom(16f).row()
        }
        if (!UiNavigation.isMobile) popup.addCloseButton()
        popup.open()
    }
}
