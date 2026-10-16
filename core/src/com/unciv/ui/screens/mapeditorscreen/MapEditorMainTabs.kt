package com.unciv.ui.screens.mapeditorscreen

import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorEditTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorGenerateTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorLoadTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorModsTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorOptionsTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorSaveTab
import com.unciv.ui.screens.mapeditorscreen.tabs.MapEditorViewTab

class MapEditorMainTabs(
    editorScreen: MapEditorScreen
) : TabbedPager(
    minimumHeight = editorScreen.getToolsHeight(),
    maximumHeight = editorScreen.getToolsHeight(),
    headerFontSize = 24,
    capacity = 7,
    showHeader = false
) {
    val view = MapEditorViewTab(editorScreen)
    val generate = MapEditorGenerateTab(editorScreen, headerHeight)
    val edit = MapEditorEditTab(editorScreen, headerHeight)
    val load = MapEditorLoadTab(editorScreen, headerHeight)
    val save = MapEditorSaveTab(editorScreen, headerHeight)
    val mods = MapEditorModsTab(editorScreen)
    val options = MapEditorOptionsTab(editorScreen)

    init {
        prefWidth = editorScreen.getToolsWidth()

        addPage("View", view,
            ImageGetter.getImage("OtherIcons/Search"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('i'))
        addPage("Generate", generate,
            ImageGetter.getImage("OtherIcons/New"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('n'))
        val editLifecycle = object : com.badlogic.gdx.scenes.scene2d.ui.Table(), TabbedPager.IPageExtensions {
            override fun activated(index: Int, caption: String, pager: TabbedPager) = edit.activated(index, caption, pager)
            override fun deactivated(index: Int, caption: String, pager: TabbedPager) = edit.deactivated(index, caption, pager)
        }
        addPage("Edit", editLifecycle,
            ImageGetter.getImage("OtherIcons/Terrains"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('e'))
        addPage("Load", load,
            ImageGetter.getImage("OtherIcons/Load"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('l'))
        addPage("Save", save,
            ImageGetter.getImage("OtherIcons/Checkmark"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('s'))
        addPage("Mods", mods,
            ImageGetter.getImage("OtherIcons/Mods"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('d'))
        addPage("Options", options,
            ImageGetter.getImage("OtherIcons/Settings"), 25f,
            shortcutKey = KeyCharAndCode.ctrl('o'))
        selectPage(-1)

        headerScroll.fadeScrollBars = false
    }
}
