package com.unciv.ui.screens.mapeditorscreen

import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen

/** A map-first workspace: persistent save and modes, two-level bottom palette. */
class MapEditorChrome(private val editor: MapEditorScreen) : Group() {
    enum class Mode { Draw, Move, Inspect }
    var mode = Mode.Draw
        private set
    private val u get() = MapEditorStyle.unit
    private val header = Table()
    private val toolbar = Table()
    private val mapControls = Table()
    private val controlButtons = ArrayList<TextButton>()
    private val brushBar = Table()
    private val panel = Table()
    private val panelTitle = "".toLabel(fontSize = (20*u).toInt())
    private val title = "".toLabel(fontSize = (18*u).toInt(), fontColor = MapEditorStyle.text)
    private val status = "".toLabel(fontSize = (12*u).toInt(), fontColor = MapEditorStyle.muted)
    private val brush = MapEditorStyle.button("Brush") { editor.tabs.edit.openBrushHelp() }
    private val range = MapEditorStyle.button("Brush ([1]):") { rangePopup() }
    private val undo = MapEditorStyle.button("Undo") { editor.undo() }
    private val redo = MapEditorStyle.button("Redo") { editor.redo() }
    private val modes = ArrayList<TextButton>()
    private var advancedOpen = false
    private var lastStatus = ""

    init {
        touchable = Touchable.childrenOnly
        header.background = MapEditorStyle.background(MapEditorStyle.panel)
        header.defaults().height(44*u).pad(4*u)
        header.add(if (UiNavigation.isIos) UiNavigation.backButton().apply { onActivation { editor.closeEditor() } }
            else MapEditorStyle.button("Back") { editor.closeEditor() })
        val heading = Table().apply {
            title.setEllipsis(true)
            add(title).growX().minWidth(0f).left().row()
            add(status).left()
        }
        header.add(heading).growX().minWidth(0f).padLeft(8*u)
        header.add(MapEditorStyle.button("Load") { editor.tabs.selectPage(3) })
        header.add(MapEditorStyle.button("Save", true) { editor.tabs.selectPage(4) })
        header.add(MapEditorStyle.button("More") { morePopup() })
        addActor(header)

        toolbar.defaults().height(44*u).pad(4*u)
        listOf("Draw", "Move", "Inspect tile").forEachIndexed { index, caption ->
            val button = MapEditorStyle.button(caption) { setMode(Mode.entries[index]) }
            modes.add(button); toolbar.add(button).minWidth(68*u)
        }
        addActor(toolbar)
        mapControls.defaults().height(44*u).pad(4*u)
        controlButtons.add(MapEditorStyle.button("−") { editor.mapHolder.zoomOut() })
        controlButtons.add(MapEditorStyle.button("+") { editor.mapHolder.zoomIn() })
        controlButtons.add(undo); controlButtons.add(redo)
        addActor(mapControls)
        brushBar.background = MapEditorStyle.background(MapEditorStyle.panel)
        brushBar.defaults().height(44*u).pad(4*u)
        brushBar.add(brush).growX().minWidth(0f)
        brushBar.add(range)
        addActor(brushBar)
        addActor(editor.tabs.edit)

        panel.background = MapEditorStyle.background(MapEditorStyle.panel)
        panel.touchable = Touchable.enabled
        val panelHeader = Table().apply {
            add(panelTitle).growX().left().padLeft(16*u)
            add(if (UiNavigation.isIos) UiNavigation.dismissButton().apply { onActivation { setMode(mode) } }
                else MapEditorStyle.button("Close") { setMode(mode) }).height(44*u).pad(4*u)
                .apply { if (UiNavigation.isIos) width(44*u) }
        }
        panel.add(panelHeader).growX().row()
        panel.add(editor.tabs).grow().minSize(0f)
        addActor(panel)
        editor.tabs.onSelection { index, caption, _ ->
            if (index == 2) { advancedOpen = false; mode = Mode.Draw }
            else { advancedOpen = true; panelTitle.setText(caption.tr()) }
            layoutChrome()
        }
        setMode(Mode.Draw)
    }

    fun setMode(value: Mode) {
        editor.tabs.edit.endStroke()
        editor.commitEdit()
        mode = value
        advancedOpen = false
        editor.tabs.selectPage(-1)
        when (value) {
            Mode.Draw -> editor.tabs.selectPage(2)
            Mode.Move -> editor.tileClickHandler = null
            Mode.Inspect -> {
                editor.tabs.selectPage(0)
                advancedOpen = false
                val inspect = editor.tileClickHandler
                editor.tileClickHandler = { tile -> inspect?.invoke(tile); advancedOpen = true; layoutChrome() }
            }
        }
        layoutChrome()
    }

    fun layoutChrome() {
        val w = editor.stage.width; val h = editor.stage.height
        setSize(w, h)
        header.setBounds(0f, h-60*u, w, 60*u)
        val tall = h/u > 600f
        toolbar.clearChildren(); mapControls.clearChildren()
        modes.forEach { button -> toolbar.add(button).minWidth(68*u); if (tall) toolbar.row() }
        controlButtons.forEachIndexed { index, button ->
            mapControls.add(button).minWidth(44*u)
            if (tall && index % 2 == 1) mapControls.row()
        }
        toolbar.pack(); toolbar.setPosition(w-toolbar.width-8*u, h-68*u-toolbar.height)
        mapControls.pack(); mapControls.setPosition(w-mapControls.width-8*u, toolbar.y-mapControls.height-4*u)
        val paletteHeight = if (mode == Mode.Draw && !advancedOpen) (if (editor.tabs.edit.collapsed) 44f else 132f)*u else 0f
        editor.tabs.edit.isVisible = paletteHeight > 0
        editor.tabs.edit.setBounds(0f, 0f, w, paletteHeight)
        brushBar.isVisible = mode == Mode.Draw && !advancedOpen
        brushBar.setBounds(8*u, paletteHeight+8*u, minOf(w-16*u, 380*u), 44*u)
        val bottom = paletteHeight
        editor.mapHolder.setBounds(0f, bottom, w, (h-60*u-bottom).coerceAtLeast(44*u))
        editor.mapHolder.invalidate()
        panel.isVisible = advancedOpen
        panel.setBounds(w-editor.getToolsWidth(), 0f, editor.getToolsWidth(), h-60*u)
        modes.forEachIndexed { i, button -> MapEditorStyle.select(button, i == mode.ordinal) }
        toolbar.isVisible = !advancedOpen
        mapControls.isVisible = !advancedOpen
    }

    override fun act(delta: Float) {
        super.act(delta)
        val mapName = editor.tileMap.mapParameters.name.ifEmpty { "New map".tr() }
        val state = if (editor.isDirty) "Unsaved changes" else if (editor.tileMap.mapParameters.name.isEmpty()) "New map" else "Saved"
        val current = "$mapName|$state|${editor.tabs.edit.brushName}|${editor.tabs.edit.brushSize}"
        if (current != lastStatus) {
            lastStatus = current
            title.setText(mapName); status.setText(state.tr())
            brush.setText(editor.tabs.edit.brushName.tr(hideIcons = true))
            val size = editor.tabs.edit.brushSize
            range.setText(if (size == -1) "Floodfill".tr() else "Brush ([$size]):".tr())
        }
        undo.isDisabled = !editor.history.canUndo
        redo.isDisabled = !editor.history.canRedo
        undo.color.a = if (undo.isDisabled) .55f else 1f
        redo.color.a = if (redo.isDisabled) .55f else 1f
    }

    private fun morePopup() {
        Popup(editor).apply {
            addGoodSizedLabel("Map editor").row()
            listOf(1 to "Generate", 0 to "View", 3 to "Load", 4 to "Save", 5 to "Mods", 6 to "Options").forEach { (page, caption) ->
                add(MapEditorStyle.button(caption) { close(); editor.tabs.selectPage(page) }).growX().height(44*u).pad(4*u).row()
            }
            addCloseButton(); open()
        }
    }
    private fun rangePopup() {
        Popup(editor).apply {
            add("Brush size".toLabel(fontSize = (18*u).toInt())).row()
            val popup = this
            val sizes = Table().apply {
                for (size in 1..5) add(MapEditorStyle.button(size.toString()) {
                    editor.tabs.edit.brushSize = size; popup.close()
                }).size(44*u).pad(4*u)
            }
            add(sizes).row()
            add(MapEditorStyle.button("Floodfill") {
                editor.tabs.edit.brushSize = -1; close()
            }).growX().height(44*u).pad(4*u).row()
            add("Floodfill follows the matching rule in Options.".toLabel(fontSize = (14*u).toInt()).apply { wrap = true })
                .width(minOf(340*u, editor.stage.width-80*u)).row()
            addCloseButton(); open()
        }
    }
}
