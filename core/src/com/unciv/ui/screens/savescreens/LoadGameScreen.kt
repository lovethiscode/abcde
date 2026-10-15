package com.unciv.ui.screens.savescreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.logic.MissingModsException
import com.unciv.logic.MissingNationException
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.PlatformSaverLoader
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.cloud.CloudSaveState
import com.unciv.logic.files.cloud.CloudSaveStatus
import com.unciv.logic.files.cloud.CloudSyncTrigger
import com.unciv.logic.github.GithubAPI
import com.unciv.logic.github.GithubAPI.downloadAndExtract
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.UncivDateFormat.formatDate
import com.unciv.ui.components.extensions.allChildren
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.ONLINE_MOD_MANAGEMENT_UNAVAILABLE
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CoroutineScope
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class LoadGameScreen(private val initialSavePath: String? = null, initialDetails: Boolean = false) :
    PickerScreen(disableScroll = true), RecreateOnResize {
    private val ui = LoadGameScreenStyle(UiNavigation.minimumTouchSize() / 44f)
    private val narrow = stage.width / ui.unit <= 700f
    private val wide = stage.width / ui.unit >= 1150f
    private var showingDetails = initialDetails
    private var restoringFiles = false
    @Volatile private var disposed = false
    @Volatile private var listGeneration = 0
    private var selectionGeneration = 0
    private var loadingGame = false
    private var selectedSave: FileHandle? = null
    private var selectedPreview: LoadGamePreview? = null
    private var blockedSave: LoadGameSaveKey? = null
    private var pendingLoad: LoadGameSaveKey? = null
    private val previews = ConcurrentHashMap<LoadGameSaveKey, LoadGamePreview>()
    private val root = Table()
    private val body = Table()
    private val savesPane = Table()
    private val detailsPane = Table()
    private val detailContent = Table()
    private val detailScroll = AutoScrollPane(detailContent)
    private val statusHolder = Table()
    private var statusSignature = ""
    private val managementActions = Table()
    private val listContent = Table()
    private val savesList = LoadGameSaveList(ui)
    private val countLabel = ui.label("Local saves", 14)
    private val showAutosavesCheckbox = CheckBox("Show autosaves".tr(), skin)
    private val errorLabel = ui.label("", 13, ui.danger, true)
    private val cloudStatusLabel = ui.label("", 14, ui.muted, true)
    private val cloudButton = ui.button("iCloud", SaveIcon.Cloud, size = 13)
    private val importSaveButton = ui.button("Import save", SaveIcon.Import, outline = true)
    private val copySavedGameToClipboardButton = getCopyExistingSaveToClipboardButton()
    private val deleteSaveButton = ui.button("Delete", SaveIcon.Delete, ghost = true, size = 13)
    private val cloudSaveSync = game.files.cloudSaveSync
    private var lastCloudChangeRevision = cloudSaveSync.status.localChangeRevision
    private val cloudStatusListener: (CloudSaveStatus) -> Unit = { status ->
        Concurrency.runOnGLThread {
            if (!disposed) {
                updateCloudStatus(status)
                if (status.localChangeRevision != lastCloudChangeRevision) {
                    lastCloudChangeRevision = status.localChangeRevision
                    refreshSaves()
                }
            }
        }
    }

    companion object : LoadOrSaveScreen.Helpers {
        private const val loadGame = "Load game"
        private const val loadFromCustomLocation = "Load from custom location"
        private const val loadFromClipboard = "Load copied data"
        private const val loadFromClipboardMenu = "Load from clipboard"
        private const val importFromFile = "Import from file"
        private const val importSave = "Import save"
        private const val loadThisSave = "Load this save"
        private const val confirmSyncSaves = "Sync local and iCloud saves now?\nA changed save may replace the same-named version\non the other side.\nConflicting versions are kept separately."
        private const val confirmRestoreSaves = "Restore saves from iCloud now?\nSaves deleted only on this device return\nif they still exist in iCloud.\nOther changes still sync both ways;\na same-named version may be replaced.\nConflicts are kept separately."
        private const val copyExistingSaveToClipboard = "Copy saved game to clipboard"
        private const val saveToClipboardErrorMessage = "Could not save game to clipboard!"
        internal const val downloadMissingMods = "Download missing mods"
    }

    init {
        pickerPane.remove()
        root.name = "load-game-root"; root.setFillParent(true)
        root.background = ui.surface(ui.background, 0f)
        root.pad(ui.px(if (wide) 28f else 16f), ui.px(16f), ui.px(if (wide) 32f else 16f), ui.px(16f))
        stage.addActor(root)
        closeButton.name = "load-back"; closeButton.setText("")
        ui.decorate(closeButton, SaveIcon.Back)
        closeButton.onActivation {
            if (narrow && showingDetails) { showingDetails = false; layoutBody() }
            else game.popScreen()
        }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        closeButton.addTooltip("Back")
        importSaveButton.name = "load-import"
        importSaveButton.onActivation { ImportSaveMenu(importSaveButton) }
        importSaveButton.keyShortcuts.add(KeyCharAndCode.ctrl('v')) { loadFromClipboard() }
        importSaveButton.addTooltip(KeyCharAndCode.ctrl('v'))
        cloudButton.name = "load-cloud"
        cloudButton.onActivation { openCloudMenu() }
        cloudButton.add(SaveIcon.Down.image(ui.muted)).size(ui.px(14f)).padLeft(ui.px(8f))
        updateCloudStatus(cloudSaveSync.status)
        rightSideButton.name = "load-read"; rightSideButton.setText(loadThisSave.tr())
        ui.decorate(rightSideButton, SaveIcon.Play, primary = true, size = 16)
        rightSideButton.setText(loadThisSave.tr()); rightSideButton.setLoadEnabled(false)
        rightSideButton.onActivation { onLoadGame(selectedSave) }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        copySavedGameToClipboardButton.name = "load-copy"
        copySavedGameToClipboardButton.setText("Copy save".tr())
        ui.decorate(copySavedGameToClipboardButton, SaveIcon.Copy, ghost = true, size = 13)
        copySavedGameToClipboardButton.addTooltip(copyExistingSaveToClipboard)
        deleteSaveButton.name = "load-delete"
        ui.decorate(deleteSaveButton, SaveIcon.Delete, ghost = true, size = 13, tint = ui.danger)
        deleteSaveButton.onActivation { deleteSelectedSave() }
        deleteSaveButton.keyShortcuts.add(KeyCharAndCode.DEL)
        deleteSaveButton.setLoadEnabled(false); copySavedGameToClipboardButton.setLoadEnabled(false)
        showAutosavesCheckbox.name = "load-autosaves"
        showAutosavesCheckbox.style = ui.checkboxStyle()
        showAutosavesCheckbox.label.setFontSize(ui.font(13)); showAutosavesCheckbox.label.color = ui.muted
        showAutosavesCheckbox.imageCell.size(ui.px(18f)).padRight(ui.px(8f))
        showAutosavesCheckbox.labelCell.minWidth(0f)
        showAutosavesCheckbox.isChecked = game.settings.showAutosaves
        showAutosavesCheckbox.onChange {
            game.settings.showAutosaves = showAutosavesCheckbox.isChecked
            refreshSaves()
        }
        showAutosavesCheckbox.keyShortcuts.add(KeyCharAndCode.ctrl('a')) { showAutosavesCheckbox.toggle() }
        descriptionLabel.setFontSize(ui.font(14)); descriptionLabel.wrap = true; descriptionLabel.color = ui.muted
        descriptionLabel.setText(""); errorLabel.isVisible = false
        savesList.name = "load-save-list"
        savesList.onSelected = ::selectSave
        savesList.onDoubleSelected = { file ->
            if (selectedPreview == null) pendingLoad = LoadGameSaveKey.of(file)
            else onLoadGame(file)
        }
        detailScroll.setScrollingDisabled(true, false); detailScroll.setOverscroll(false, false)
        detailContent.top(); detailContent.defaults().growX().minWidth(0f)
        initLayout()
        refreshSaves()
        if (cloudSaveSync.isSupported) {
            cloudSaveSync.addStatusListener(cloudStatusListener)
            cloudSaveSync.requestSync(CloudSyncTrigger.LoadScreen)
        }
    }

    override fun recreate() = LoadGameScreen(selectedSave?.path(), showingDetails)

    private fun initLayout() {
        val heading = Table().apply {
            add(ui.label("Load game", if (wide) 30 else 26)).growX().minWidth(0f).left().row()
            add(ui.label("Choose a save and continue your civilization", 13, ui.muted).apply { setEllipsis(true) })
                .growX().minWidth(0f).left().padTop(ui.px(3f))
        }
        val actions = Table().apply {
            val available = this@LoadGameScreen.stage.width - root.padLeft - root.padRight
            val actionWidth = if (cloudSaveSync.isSupported) (available - ui.px(12f)) / 2 else available
            if (narrow) for (button in listOf(cloudButton, importSaveButton)) {
                button.label.setEllipsis(false)
                button.label.wrap = true
                button.labelCell.growX().minWidth(0f)
            }
            if (cloudSaveSync.isSupported) add(cloudButton).minHeight(ui.px(44f)).padRight(ui.px(12f))
                .apply { if (narrow) width(actionWidth).minWidth(0f) }
            add(importSaveButton).minHeight(ui.px(44f)).apply { if (narrow) width(actionWidth).minWidth(0f) }
        }
        val header = Table().apply {
            add(closeButton).size(ui.px(44f)).padRight(ui.px(12f))
            add(heading).growX().minWidth(0f).left()
            if (narrow) { row(); add(actions).colspan(2).growX().minWidth(0f).padTop(ui.px(8f)) }
            else add(actions).padLeft(ui.px(16f))
        }
        root.add(header).growX().minWidth(0f).padBottom(ui.px(if (wide) 24f else 14f)).row()
        val listHeader = Table().apply {
            add(countLabel).growX().minWidth(0f).left().padRight(ui.px(8f))
            add(showAutosavesCheckbox).minHeight(ui.px(44f)).right()
        }
        savesPane.top().add(listHeader).growX().minWidth(0f).padBottom(ui.px(4f)).row()
        savesPane.add(listContent).grow().minWidth(0f).minHeight(0f)
        detailsPane.background = ui.surface(ui.panel, 16f)
        val inset = ui.px(if (wide) 28f else 20f)
        detailsPane.add(detailScroll).grow().minWidth(0f).minHeight(0f).pad(ui.px(16f), inset, 0f, inset).row()
        val footer = Table().apply {
            add(rightSideButton).growX().minWidth(0f).minHeight(ui.px(44f)).row()
            add(managementActions).padTop(ui.px(4f)).center()
        }
        detailsPane.add(footer).growX().minWidth(0f).pad(ui.px(10f), inset, ui.px(14f), inset)
        root.add(body).grow().minWidth(0f).minHeight(0f)
        layoutBody(); showDetails()
    }

    private fun layoutBody() {
        body.clear()
        if (narrow) body.add(if (showingDetails) detailsPane else savesPane).grow().minWidth(0f).minHeight(0f)
        else {
            val contentWidth = stage.width - root.padLeft - root.padRight - ui.px(20f)
            val left = contentWidth * if (wide) .545f else .495f
            body.add(savesPane).width(left).growY().minWidth(0f).minHeight(0f)
            body.add(detailsPane).width(contentWidth-left).growY().minWidth(0f).minHeight(0f).padLeft(ui.px(20f))
        }
        body.invalidateHierarchy()
    }

    private fun refreshSaves() {
        val generation = ++listGeneration
        val previous = selectedSave ?: initialSavePath?.let(::FileHandle)
        val autosaves = showAutosavesCheckbox.isChecked
        pendingLoad = null; rightSideButton.setLoadEnabled(false)
        countLabel.setText("Loading...".tr())
        Concurrency.run("LoadGameSaveList") {
            val result = runCatching { game.files.getSaves(true).sortedByDescending { it.lastModified() }.toList() }
            launchOnGLThread {
                if (disposed || generation != listGeneration) return@launchOnGLThread
                val allFiles = result.getOrElse { error ->
                    errorLabel.setText("Could not load game!".tr()); errorLabel.isVisible = true
                    Log.error("Could not list saves", error); emptyList()
                }
                val files = if (autosaves) allFiles else allFiles.filterNot { it.name().startsWith("Autosave") }
                countLabel.setText("Local saves [${files.size}]".tr())
                listContent.clear()
                restoringFiles = true; savesList.setFiles(files, previous); restoringFiles = false
                if (files.isEmpty()) {
                    selectedSave = null; selectedPreview = null; ++selectionGeneration
                    copySavedGameToClipboardButton.setLoadEnabled(false); deleteSaveButton.setLoadEnabled(false); updateReadButton()
                    listContent.add(emptySaves(allFiles.isNotEmpty())).grow().minWidth(0f).minHeight(0f)
                    showDetails()
                } else {
                    listContent.add(savesList).grow().minWidth(0f).minHeight(0f)
                    root.validate(); savesList.scrollToSelection()
                }
                Concurrency.run("LoadGameRowPreviews") {
                    for (file in files) {
                        if (disposed || generation != listGeneration) break
                        val key = LoadGameSaveKey.of(file)
                        val preview = previews.getOrPut(key) { LoadGamePreview.read(game.files, file) }
                        launchOnGLThread {
                            if (!disposed && generation == listGeneration && key == LoadGameSaveKey.of(file))
                                savesList.updatePreview(file, preview)
                        }
                    }
                }
            }
        }
    }

    private fun selectSave(file: FileHandle) {
        selectedSave = file; selectedPreview = null; pendingLoad = null
        descriptionLabel.setText(""); errorLabel.setText(""); errorLabel.isVisible = false
        copySavedGameToClipboardButton.setLoadEnabled(true); deleteSaveButton.setLoadEnabled(true)
        if (narrow && !restoringFiles) { showingDetails = true; layoutBody() }
        val key = LoadGameSaveKey.of(file)
        val generation = ++selectionGeneration
        val cached = previews[key]
        if (cached != null) { selectedPreview = cached; updateReadButton(); showDetails(); return }
        updateReadButton(); showDetails()
        Concurrency.run("LoadSelectedSavePreview") {
            val preview = LoadGamePreview.read(game.files, file)
            previews[key] = preview
            launchOnGLThread {
                if (disposed || !acceptsLoadGamePreview(key, selectedSave?.let(LoadGameSaveKey::of), generation, selectionGeneration)) return@launchOnGLThread
                selectedPreview = preview
                savesList.updatePreview(file, preview)
                updateReadButton(); showDetails()
                if (pendingLoad == key && preview.info != null) { pendingLoad = null; onLoadGame(file) }
            }
        }
    }

    private fun TextButton.setLoadEnabled(enabled: Boolean) {
        isDisabled = !enabled
        touchable = Touchable.enabled
    }

    private fun detailInnerWidth(): Float {
        val available = stage.width - root.padLeft - root.padRight
        val panelWidth = if (narrow) available else (available - ui.px(20f)) * if (wide) .455f else .505f
        return panelWidth - ui.px(if (wide) 56f else 40f)
    }

    private fun updateReadButton() {
        val blocked = selectedSave?.let(LoadGameSaveKey::of) == blockedSave && blockedSave != null
        val unreadable = selectedPreview?.error != null || blocked
        rightSideButton.setText((if (loadingGame) Constants.loading else if (unreadable) "Cannot load this save" else loadThisSave).tr())
        rightSideButton.setLoadEnabled(selectedPreview?.info != null && !unreadable && !loadingGame)
        rightSideButton.children.filterIsInstance<com.badlogic.gdx.scenes.scene2d.ui.Image>().forEach {
            it.color = if (rightSideButton.isDisabled) ui.muted else ui.ink
        }
    }

    private fun showDetails() {
        detailContent.clear()
        val file = selectedSave
        managementActions.clear()
        if (file != null) {
            managementActions.add(copySavedGameToClipboardButton).minHeight(ui.px(44f)).minWidth(0f)
            managementActions.add(Table().apply { background = ui.surface(ui.border, 0f) })
                .size(ui.px(1f), ui.px(14f)).pad(0f, ui.px(6f), 0f, ui.px(6f))
            managementActions.add(deleteSaveButton).minHeight(ui.px(44f)).minWidth(0f)
        }
        if (file == null) {
            val blank = Table().apply {
                add(SaveIcon.File.image(ui.muted)).size(ui.px(30f)).padBottom(ui.px(12f)).row()
                add(ui.label("View save details", 17)).padBottom(ui.px(8f)).row()
                add(ui.label("Select a save from the list", 14, ui.muted, true)).growX().minWidth(0f)
            }
            detailContent.add(blank).grow().minWidth(0f).row()
            detailContent.add(statusHolder).growX().minWidth(0f)
            return
        }
        val info = selectedPreview?.info
        val title = Table().apply {
            val icon = Table().apply { background = ui.surface(ui.raised); add(SaveIcon.Flag.image(ui.gold)).size(ui.px(25f)) }
            add(icon).size(ui.px(44f)).padRight(ui.px(12f)).top()
            val text = Table().apply {
                add(ui.label(if (info == null) file.name() else civilizationAndTurn(info), if (wide) 28 else 23, wrap = true).apply {
                    if (info == null) setText(file.name())
                }).width((detailInnerWidth()-ui.px(56f)).coerceAtLeast(ui.px(80f))).left().row()
                if (info != null) add(ui.label("", 13, ui.muted, true).apply { setText(file.name()) }).growX().minWidth(0f).padTop(ui.px(3f))
            }
            add(text).growX().minWidth(0f)
        }
        detailContent.add(title).growX().minWidth(0f).left().padBottom(ui.px(if (wide) 24f else 12f)).row()
        if (info == null) {
            detailContent.add(ui.label(if (selectedPreview?.error != null) "Could not load game!" else "Loading...", 14,
                if (selectedPreview?.error != null) ui.danger else ui.muted, true)).growX().minWidth(0f).left().row()
            if (selectedPreview?.error != null) detailContent.add(ui.label("Copy the save or import another file to recover.", 14, ui.muted, true))
                .growX().minWidth(0f).left().padTop(ui.px(12f)).row()
        } else {
            val grid = Table().apply { defaults().growX().minWidth(0f).top() }
            val singleColumn = detailInnerWidth() / ui.unit / game.settings.fontSizeMultiplier < 260f
            val fieldWidth = if (singleColumn) detailInnerWidth() else (detailInnerWidth()-ui.px(16f))/2
            fun field(label: String, value: String, width: Float = fieldWidth): Table = Table().apply {
                add(ui.label(label, if (wide) 14 else 12, ui.muted, true)).width(width).left().row()
                add(ui.label("", if (wide) 17 else 14, wrap = true).apply { setText(value) })
                    .width(width).left().padTop(ui.px(1f))
            }
            val fields = listOf(
                "Saved at" to Date(info.key.modified).formatDate(),
                "Save type" to (if (file.name().startsWith("Autosave")) "Automatic save" else "Manual save").tr(),
                "Difficulty" to info.difficulty.tr(),
                "Mods" to if (info.mods.isEmpty()) "None".tr() else info.mods.joinToString()
            )
            fields.forEachIndexed { index, (label, value) ->
                grid.add(field(label, value)).width(fieldWidth)
                    .padTop(if (index < (if (singleColumn) 1 else 2)) 0f else ui.px(if (wide) 20f else 8f))
                    .padRight(if (!singleColumn && index % 2 == 0) ui.px(16f) else 0f)
                if (singleColumn || index % 2 == 1) grid.row()
            }
            grid.add(field("Base ruleset", info.ruleset, detailInnerWidth())).colspan(if (singleColumn) 1 else 2)
                .padTop(ui.px(if (wide) 20f else 8f)).row()
            if (info.online) grid.add(ui.label("Online Multiplayer", 13, ui.muted, true))
                .colspan(if (singleColumn) 1 else 2).padTop(ui.px(8f)).growX().row()
            detailContent.add(grid).growX().minWidth(0f).left().row()
        }
        detailContent.add(statusHolder).growX().minWidth(0f).row()
        detailContent.add().growY()
        detailScroll.scrollY = 0f
    }

    private fun emptySaves(filtered: Boolean): Table = Table().apply {
        name = "load-empty"
        val symbol = Table().apply { background = ui.surface(ui.panel, 16f); add(SaveIcon.Archive.image(ui.muted)).size(ui.px(28f)) }
        add(symbol).size(ui.px(56f)).padBottom(ui.px(16f)).row()
        add(ui.label(if (filtered) "No manual saves" else "No saves yet", 22, wrap = true)).growX().minWidth(0f).padBottom(ui.px(12f)).row()
        add(ui.label(if (filtered) "Show autosaves or import a save." else "Import a save file or restore from iCloud.", 14, ui.muted, true)).growX().minWidth(0f).padBottom(ui.px(16f)).row()
        val actions = Table().apply {
            add(ui.button(if (filtered) "Show autosaves" else "Import save", if (filtered) SaveIcon.Clock else SaveIcon.Import, primary = true).apply {
                name = "load-empty-import"; onActivation { if (filtered) showAutosavesCheckbox.isChecked = true else ImportSaveMenu(this) }
            }).minHeight(ui.px(44f))
            if (cloudSaveSync.isSupported) {
                val stack = game.settings.fontSizeMultiplier > 1.2f || narrow
                if (stack) row()
                add(ui.button("Restore from iCloud", SaveIcon.Cloud, outline = true).apply {
                name = "load-empty-restore"; onActivation { openCloudSyncConfirmation(confirmRestoreSaves, "Restore saves from iCloud", CloudSyncTrigger.Restore) }
            }).minHeight(ui.px(44f)).padLeft(if (stack) 0f else ui.px(12f)).padTop(if (stack) ui.px(8f) else 0f)
            }
        }
        add(actions)
    }

    private fun openCloudMenu() {
        val popup = Popup(this)
        cloudStatusLabel.setText(cloudStatusText(cloudSaveSync.status))
        popup.add(cloudStatusLabel).width(minOf(ui.px(360f), stage.width-ui.px(64f))).pad(ui.px(12f)).row()
        fun action(caption: String, name: String, trigger: CloudSyncTrigger, question: String) {
            popup.add(ui.button(caption, SaveIcon.Cloud, ghost = true).apply {
                this.name = name
                onActivation { popup.close(); openCloudSyncConfirmation(question, caption, trigger) }
            }).growX().minHeight(ui.px(44f)).row()
        }
        action("Sync saves now", "load-cloud-sync", CloudSyncTrigger.Manual, confirmSyncSaves)
        action("Restore saves from iCloud", "load-cloud-restore", CloudSyncTrigger.Restore, confirmRestoreSaves)
        popup.addCloseButton(); popup.open()
    }

    private fun updateCloudStatus(status: CloudSaveStatus) {
        cloudStatusLabel.setText(cloudStatusText(status))
        val caption = when (status.state) {
            CloudSaveState.NoAccount -> "Not signed in"
            CloudSaveState.Syncing, CloudSaveState.Checking -> "Syncing..."
            CloudSaveState.Synced -> "Synced"
            CloudSaveState.Failed -> "Sync failed"
            CloudSaveState.WaitingForNetwork -> "Offline"
            CloudSaveState.Restricted -> "Restricted"
            CloudSaveState.AccountChanged -> "Account changed"
            else -> "Available"
        }
        cloudButton.setText("iCloud ${caption.tr()}")
    }

    private fun deleteSelectedSave() {
        val file = selectedSave ?: return
        val localOnly = cloudSaveSync.isSupported
        val question = if (localOnly) "Are you sure you want to delete this save from this device? It will remain in iCloud."
            else "Are you sure you want to delete this save?"
        ConfirmPopup(this, question, "Delete save") {
            val message = try {
                if (game.files.deleteSave(file)) {
                    previews.keys.removeAll { it.path == file.path() }
                    refreshSaves()
                    if (localOnly) "[${file.name()}] deleted from this device." else "[${file.name()}] deleted successfully."
                } else "Failed to delete [${file.name()}]."
            } catch (_: SecurityException) { "Insufficient permissions to delete [${file.name()}]." }
            catch (_: Exception) { "Failed to delete [${file.name()}]." }
            ToastPopup(message, this)
        }.open()
    }

    private fun handleException(ex: Exception, primaryText: String): Boolean {
        val (message, fixable) = getLoadExceptionMessage(ex, primaryText)
        if (!fixable) Log.error(primaryText, ex)
        errorLabel.setText(message); errorLabel.isVisible = true
        return fixable
    }

    override fun render(delta: Float) {
        val signature = "${descriptionLabel.text}|${errorLabel.isVisible}|${errorLabel.text}"
        if (signature != statusSignature) {
            statusSignature = signature; statusHolder.clear()
            if (descriptionLabel.text.isNotEmpty()) statusHolder.add(descriptionLabel).growX().minWidth(0f).row()
            if (errorLabel.isVisible) statusHolder.add(errorLabel).growX().minWidth(0f).padTop(ui.px(4f)).row()
        }
        super.render(delta)
    }

    private fun openCloudSyncConfirmation(question: String, confirmText: String, trigger: CloudSyncTrigger) {
        ConfirmPopup(this, question, confirmText) {
            cloudSaveSync.requestSync(trigger)
        }.apply {
            allChildren().filterIsInstance<Label>().forEach { it.setFontSize(ui.font(16)) }
        }.open()
    }

    private inner class ImportSaveMenu(anchor: TextButton) : AnimatedMenuPopup(stage, anchor, Align.bottomRight) {
        override fun createContentTable() = super.createContentTable()!!.apply {
            add(getButton(loadFromClipboardMenu, KeyboardBinding.None) {
                Concurrency.runOnGLThread { loadFromClipboard() }
            }.apply { label.setFontSize(ui.font(14)) }).row()
            add(getButton(importFromFile, KeyboardBinding.None) {
                Concurrency.runOnGLThread { loadFromCustomLocation() }
            }.apply { label.setFontSize(ui.font(14)) }).row()
        }
    }

    private fun cloudStatusText(status: CloudSaveStatus): String {
        val stateText = when (status.state) {
            CloudSaveState.Unsupported -> ""
            CloudSaveState.Checking -> "Checking iCloud availability..."
            CloudSaveState.Available -> "iCloud is available."
            CloudSaveState.NoAccount -> "Sign in to iCloud to sync saves."
            CloudSaveState.Restricted -> "iCloud access is restricted."
            CloudSaveState.AccountChanged -> "iCloud account changed. Tap Sync saves now to use the current account."
            CloudSaveState.WaitingForNetwork -> "Waiting to retry iCloud save sync."
            CloudSaveState.Syncing -> "Syncing saves with iCloud..."
            CloudSaveState.Failed -> "iCloud save sync failed."
            CloudSaveState.Synced -> "Saves are synced with iCloud."
        }.tr()
        if (status.lastSuccessfulSyncAt <= 0L) return stateText
        return "$stateText\n${"Last successful iCloud sync:".tr()} ${Date(status.lastSuccessfulSyncAt).formatDate()}"
    }

    override fun dispose() {
        disposed = true
        ++listGeneration; ++selectionGeneration
        if (cloudSaveSync.isSupported) cloudSaveSync.removeStatusListener(cloudStatusListener)
        super.dispose()
    }

    private fun onLoadGame(saveGameFile: FileHandle?) {
        if (saveGameFile == null || selectedPreview?.info == null || loadingGame || blockedSave == LoadGameSaveKey.of(saveGameFile)) return
        loadingGame = true; updateReadButton()
        val loadingPopup = LoadingPopup(this)
        Concurrency.run(loadGame) {
            try {
                // This is what can lead to ANRs - reading the file and setting the transients, that's why this is in another thread
                val loadedGame = game.files.loadGameFromFile(saveGameFile)
                game.loadGame(loadedGame, callFromLoadScreen = true)
            } catch (notAPlayer: UncivShowableException) {
                launchOnGLThread {
                    val (message) = getLoadExceptionMessage(notAPlayer)
                    loadingPopup.reuseWith(message, true)
                    blockedSave = LoadGameSaveKey.of(saveGameFile); updateReadButton()
                    handleLoadGameException(notAPlayer)
                }
            } catch (ex: Exception) {
                launchOnGLThread {
                    loadingPopup.close()
                    blockedSave = LoadGameSaveKey.of(saveGameFile); updateReadButton()
                    handleLoadGameException(ex)
                }
            } finally {
                launchOnGLThread { loadingGame = false; if (!disposed) updateReadButton() }
            }
        }
    }

    private fun loadFromClipboard() {
        if (!Gdx.app.clipboard.hasContents()) return
        importSaveButton.setText(Constants.working.tr())
        importSaveButton.setLoadEnabled(false)
        Concurrency.run(loadFromClipboard) {
            try {
                val clipboardContentsString = Gdx.app.clipboard.contents.trim()
                val loadedGame = UncivFiles.gameInfoFromString(clipboardContentsString)
                game.loadGame(loadedGame, callFromLoadScreen = true)
            } catch (ex: Exception) {
                launchOnGLThread { handleLoadGameException(ex, "Could not load game from clipboard!") }
            } finally {
                launchOnGLThread {
                    importSaveButton.setText(importSave.tr())
                    importSaveButton.setLoadEnabled(true)
                }
            }
        }
    }

    private fun loadFromCustomLocation() {
        errorLabel.isVisible = false
        importSaveButton.setText(Constants.loading.tr())
        importSaveButton.setLoadEnabled(false)
        fun revertButton() {
            importSaveButton.setText(importSave.tr())
            importSaveButton.setLoadEnabled(true)
        }
        Concurrency.run(loadFromCustomLocation) {
            game.files.loadGameFromCustomLocation(
                onLoaded = { loadedGame ->
                    Concurrency.run {
                        try {
                            game.loadGame(loadedGame, callFromLoadScreen = true)
                        } catch (ex: Exception) {
                            launchOnGLThread { handleLoadGameException(ex, "Could not load game from custom location!") }
                        } finally {
                            launchOnGLThread { revertButton() }
                        }
                    }
                },
                onError = { ex ->
                    if (ex !is PlatformSaverLoader.Cancelled)
                        handleLoadGameException(ex, "Could not load game from custom location!")
                    revertButton()
                }
            )
        }
    }

    private fun getCopyExistingSaveToClipboardButton(): TextButton {
        val copyButton = copyExistingSaveToClipboard.toTextButton()
        copyButton.onActivation {
            val file = selectedSave ?: return@onActivation
            Concurrency.run(copyExistingSaveToClipboard) {
                copySaveToClipboard(file)
            }
        }
        copyButton.setLoadEnabled(false)
        val ctrlC = KeyCharAndCode.ctrl('c')
        copyButton.keyShortcuts.add(ctrlC)
        copyButton.addTooltip(ctrlC)
        return copyButton
    }

    private fun CoroutineScope.copySaveToClipboard(file: FileHandle) {
        val gameText = try {
            file.readString()
        } catch (ex: Throwable) {
            val (errorText, isUserFixable) = getLoadExceptionMessage(ex, saveToClipboardErrorMessage)
            if (!isUserFixable)
                Log.error(saveToClipboardErrorMessage, ex)
            launchOnGLThread {
                ToastPopup(errorText, this@LoadGameScreen)
            }
            return
        }
        try {
            Gdx.app.clipboard.contents = if (gameText[0] == '{') FileConversions.zip(gameText) else gameText
            launchOnGLThread {
                ToastPopup("'[${file.name()}]' copied to clipboard!", this@LoadGameScreen)
            }
        } catch (ex: Throwable) {
            Log.error(saveToClipboardErrorMessage, ex)
            launchOnGLThread {
                ToastPopup(saveToClipboardErrorMessage, this@LoadGameScreen)
            }
        }
    }

    private fun handleLoadGameException(ex: Exception, primaryText: String = "Could not load game!") {
        val isUserFixable = handleException(ex, primaryText)
        if (!isUserFixable) {
            val cantLoadGamePopup = Popup(this@LoadGameScreen)
            cantLoadGamePopup.addGoodSizedLabel("It looks like your saved game can't be loaded!").row()
            cantLoadGamePopup.addGoodSizedLabel("If you could copy your game data (\"Copy saved game to clipboard\" - ").row()
            cantLoadGamePopup.addGoodSizedLabel("  paste it into a new GitHub issue)").row()
            cantLoadGamePopup.addGoodSizedLabel("I could maybe help you figure out what went wrong, since this isn't supposed to happen!").row()
            cantLoadGamePopup.addCloseButton()
            cantLoadGamePopup.open()
        }

        if ((ex is MissingModsException || ex is MissingNationException)
                && !game.platformCapabilities.onlineModManagement) {
            ToastPopup(ONLINE_MOD_MANAGEMENT_UNAVAILABLE, this)
            return
        }

        if (ex is MissingModsException) {
            loadMissingModsAsync(ex.missingMods)
        }
        if (ex is MissingNationException){
            redownloadUnupdatedMods(ex.modNames)
        }
    }

    /** If any nation is missing from a saved game, chances are that one of the mods needs to be redownloaded
     * Since we don't know which one, we check all mods that
     * A. Have at least one nation
     * B. Are outdated
     * */
    private fun redownloadUnupdatedMods(modNames: LinkedHashSet<String>) {
        descriptionLabel.setText("Downloading unupdated mods...")
        Concurrency.runOnNonDaemonThreadPool("redownloadUnupdatedMods") { 
            val modsToCheck = modNames.mapNotNull { RulesetCache[it] }
                .filter { it.nations.any() && it.modOptions.modUrl.isNotEmpty()  }
            
            val reposToUpdate = modsToCheck
                .mapNotNull { 
                    val repo = GithubAPI.Repo.parseUrl(it.modOptions.modUrl) ?: return@mapNotNull null
                    if (it.modOptions.lastUpdated == repo.pushed_at) return@mapNotNull null
                    repo
                }
            
            Concurrency.runOnGLThread {
                ToastPopup("Updating mods: $reposToUpdate", this@LoadGameScreen)
                descriptionLabel.setText("Downloading unupdated mods - 0/${reposToUpdate.size}")
            }

            for ((index, repo) in reposToUpdate.withIndex()) {
                repo.downloadAndExtract()
                Concurrency.runOnGLThread {
                    ToastPopup("Downloaded ${repo.name}", this@LoadGameScreen)
                    descriptionLabel.setText("Downloading unupdated mods - ${index+1}/${reposToUpdate.size}")
                }
            }
        }
    }

    private fun loadMissingModsAsync(missingMods: Iterable<String>) {
        descriptionLabel.setText(Constants.loading.tr())
        Concurrency.runOnNonDaemonThreadPool(downloadMissingMods) {
            try {
                loadMissingMods(missingMods,
                    onModDownloaded = {
                        val labelText = descriptionLabel.text // A Gdx CharArray that has StringBuilder-like methods
                        labelText.appendLine()
                        labelText.append("[$it] Downloaded!".tr())
                        launchOnGLThread { descriptionLabel.setText(labelText) }
                    },
                    onCompleted = {
                        launchOnGLThread {
                            RulesetCache.loadRulesets()
                            errorLabel.isVisible = false
                            blockedSave = null; updateReadButton()
                            ToastPopup("Missing mods are downloaded successfully.", this@LoadGameScreen)
                        }
                    }
                )
            } catch (ex: Exception) {
                launchOnGLThread {
                    handleLoadGameException(ex, "Could not load the missing mods!")
                }
            } finally {
                launchOnGLThread {
                    descriptionLabel.setText("")
                }
            }
        }
    }
}
