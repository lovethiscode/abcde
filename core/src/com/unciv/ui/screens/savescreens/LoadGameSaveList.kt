package com.unciv.ui.screens.savescreens

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Input
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontColor
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Rich rows are local to Load Game; the save/export screen keeps its existing list. */
internal class LoadGameSaveList(private val ui: LoadGameScreenStyle) : AutoScrollPane(Table()) {
    private val table get() = actor as Table
    private val rows = ArrayList<SaveRow>()
    private var selected: SaveRow? = null
    var onSelected: (FileHandle) -> Unit = {}
    var onDoubleSelected: (FileHandle) -> Unit = {}

    init {
        setScrollingDisabled(true, false); setOverscroll(false, false)
        table.top().defaults().growX().minWidth(0f)
        keyShortcuts.add(Input.Keys.UP) { move(-1) }
        keyShortcuts.add(Input.Keys.DOWN) { move(1) }
        keyShortcuts.add(Input.Keys.HOME) { rows.firstOrNull()?.let { select(it) } }
        keyShortcuts.add(Input.Keys.END) { rows.lastOrNull()?.let { select(it) } }
        keyShortcuts.add(Input.Keys.PAGE_UP) { movePage(-1) }
        keyShortcuts.add(Input.Keys.PAGE_DOWN) { movePage(1) }
    }

    fun setFiles(files: List<FileHandle>, previous: FileHandle?) {
        table.clear(); rows.clear(); selected = null
        for (file in files) {
            val row = SaveRow(file)
            rows.add(row)
            row.button.onClick { select(row) }
            row.button.onDoubleClick { select(row); onDoubleSelected(file) }
            table.add(row.button).growX().minWidth(0f).minHeight(ui.px(if (UiNavigation.isIos) 58f else 70f)).padBottom(ui.px(3f)).row()
        }
        val initial = rows.firstOrNull { it.file.path() == previous?.path() } ?: rows.firstOrNull()
        initial?.let { select(it, reveal = false) }
        scrollY = 0f
    }

    fun updatePreview(file: FileHandle, preview: LoadGamePreview) {
        val row = rows.firstOrNull { it.file.path() == file.path() } ?: return
        val info = preview.info
        row.metadata.setText(if (info == null) "Could not load game!".tr() else civilizationAndTurn(info))
        row.metadata.setFontColor(if (info == null) ui.danger else ui.muted)
        row.button.invalidateHierarchy()
    }

    fun selectFile(file: FileHandle) { rows.firstOrNull { it.file.path() == file.path() }?.let { select(it) } }

    fun scrollToSelection() {
        val row = selected ?: return
        validate(); table.validate()
        scrollTo(row.button.x, row.button.y, row.button.width, row.button.height, false, true)
        updateVisualScroll()
    }

    private fun select(row: SaveRow, reveal: Boolean = true) {
        selected?.let { it.setSelected(false) }
        selected = row; row.setSelected(true)
        if (reveal) scrollToSelection()
        onSelected(row.file)
    }
    private fun move(delta: Int) {
        if (rows.isEmpty()) return
        val index = rows.indexOf(selected)
        select(rows[((index + delta) % rows.size + rows.size) % rows.size])
    }
    private fun movePage(direction: Int) {
        val rowHeight = selected?.button?.height ?: ui.px(if (UiNavigation.isIos) 58f else 70f)
        move(direction * (height / rowHeight).toInt().coerceAtLeast(1))
    }

    private inner class SaveRow(val file: FileHandle) {
        val button = TextButton("", ui.buttonStyle(ghost = true)).apply {
            name = "load-save-${file.name()}"
            val verticalPadding = ui.px(if (UiNavigation.isIos) 6f else 10f)
            pad(verticalPadding, ui.px(12f), verticalPadding, ui.px(12f))
        }
        private val title = ui.label("", TextRole.Control).apply { setText(file.name()); setEllipsis(true); setAlignment(Align.left) }
        val metadata = ui.label("Loading...", TextRole.Supporting, ui.muted).apply { setEllipsis(true) }
        private val icon: Image = (if (file.name().startsWith("Autosave")) SaveIcon.Clock else SaveIcon.Flag).image(ui.muted)
        private val iconBox = Table().apply {
            background = ui.surface(ui.panel, 10f)
            add(icon).size(ui.px(21f))
        }
        private val check = SaveIcon.Check.image(ui.gold).apply { isVisible = false }
        init {
            button.clearChildren()
            button.add(iconBox).size(ui.px(36f)).padRight(ui.px(12f))
            val text = Table().apply {
                add(title).growX().minWidth(0f).left().row()
                val meta = Table().apply {
                    add(metadata).minWidth(0f).left()
                    val type = ui.label(if (file.name().startsWith("Autosave")) "Automatic" else "Manual", TextRole.Metadata, ui.muted)
                    add(Table().apply { background = ui.surface(ui.panel, 4f); add(type).pad(ui.px(2f), ui.px(6f), ui.px(2f), ui.px(6f)) })
                        .padLeft(ui.px(8f))
                    add().growX()
                }
                add(meta).growX().minWidth(0f).left().padTop(ui.px(4f))
            }
            button.add(text).growX().minWidth(0f)
            // Time punctuation and leading zeroes are data, not localizable numbers.
            button.add(ui.label("", TextRole.Supporting, ui.muted).apply { setText(compactSaveDate(file.lastModified())) })
                .padLeft(ui.px(8f))
            button.add(check).size(ui.px(16f)).padLeft(ui.px(8f))
        }
        fun setSelected(value: Boolean) {
            button.isChecked = value
            title.setFontColor(if (value) ui.gold else ui.text)
            icon.color = if (value) ui.gold else ui.muted
            iconBox.background = ui.surface(if (value) ui.background else ui.panel, 10f)
            check.isVisible = value
        }
    }
}

internal fun civilizationAndTurn(info: LoadGameSaveInfo): String =
    "[${info.civilizations.joinToString { it.tr(hideIcons = true) }}] · Turn [${info.turns}]".tr(hideIcons = true)

internal fun compactSaveDate(timestamp: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(other: Calendar) = then.get(Calendar.ERA) == other.get(Calendar.ERA) &&
        then.get(Calendar.YEAR) == other.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(timestamp))
    return when {
        sameDay(today) -> "Today [load-game-time]".tr().replace("load-game-time", time)
        sameDay(yesterday) -> "Yesterday [load-game-time]".tr().replace("load-game-time", time)
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(timestamp))
    }
}
