package com.unciv.ui.screens.modmanager

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.SerializationException
import com.unciv.UncivGame
import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.LocalModTransferServer
import com.unciv.logic.github.DownloadAndExtractState
import com.unciv.logic.github.Github
import com.unciv.logic.github.Github.repoNameToFolderName
import com.unciv.logic.github.GithubAPI
import com.unciv.logic.github.GithubAPI.downloadAndExtract
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.ActivationTypes
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.clearActivationActions
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.popups.options.OptionsScreen
import com.unciv.ui.popups.options.OptionsPopupPages
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.modmanager.ModManagementOptions.SortType
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.ONLINE_MOD_MANAGEMENT_UNAVAILABLE
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.io.IOException

/**
 * The Mod Management Screen - constructor for internal use by [resize]
 * @param previousInstalledMods - cached installed mod list.
 * @param previousOnlineMods - cached online mod list, if supplied and not empty, it will be displayed as is and no online query will be run.
 */
class ModManagementScreen private constructor(
    previousInstalledMods: HashMap<String, ModUIData>?,
    previousOnlineMods: HashMap<String, ModUIData>?,
    private var showingInstalled: Boolean = false,
    private var selectedModName: String? = null,
    private var showingDetails: Boolean = false,
    previousOptions: ModManagementOptions.State? = null
): BaseScreen(), RecreateOnResize {
    /** The Mod Management Screen - called only from [MainMenuScreen] */
    constructor() : this(null, null)

    companion object {
        // Tweakable constants
        /** For preview.png */
        const val maxAllowedPreviewImageSize = 200f
        /** Github queries use this limit */
        const val amountPerPage = 100

        fun cleanModName(modName: String): String = modName.replace("   ", " - ")
    }

    private class ModsScrollPane(widget: Actor?) : AutoScrollPane(widget, skin, "mods-scroll") {
        init {
            setupFadeScrollBars(3f, 3f) // Let them fade away, but more slowly than default (1, 1)
        }
    }

    private val singleColumn = stage.width < 820f * game.settings.fontSizeMultiplier
    private val root = Table()
    private val body = Table()
    private val listPane = Table()
    private val detailPane = Table()
    private val listHolder = Table()
    private val actionFooter = Table()
    private val closeButton = if (UiNavigation.isIos) UiNavigation.backButton() else ModManagementStyle.button("Close")
    private val rightSideButton = ModManagementStyle.button("Download", primary = true)
    private val discoverButton = ModManagementStyle.button("Discover mods")
    private val installedButton = ModManagementStyle.button("Installed")
    private val detailsBackButton = ModManagementStyle.button("Back to mod list")
    private val modDescriptionLabel = ModManagementStyle.label("", TextRole.Body, ModManagementStyle.muted)
    private val actionHint = ModManagementStyle.label("", TextRole.Supporting, ModManagementStyle.muted)
    private val detailWidth = if (singleColumn) stage.width - 32f else (stage.width - 48f) * 0.54f
    private val listWidth = if (singleColumn) stage.width - 32f else stage.width - 48f - detailWidth
    private val countLabel = ModManagementStyle.label("", TextRole.Supporting, ModManagementStyle.muted)
    private val sourceLabel = ModManagementStyle.label("", TextRole.Supporting, ModManagementStyle.muted)
    private val sortHolder = Table()
    private val queryStatus = Table()
    private var onlineLoading = false
    private var onlineFailed = false

    private val installedModsTable = Table().apply { top(); defaults().growX().padBottom(6f) }
    private val scrollInstalledMods = ModsScrollPane(installedModsTable)
    private val onlineModsTable = Table().apply { top(); defaults().growX().padBottom(6f) }
    private val scrollOnlineMods = ModsScrollPane(onlineModsTable)
    private val modActionTable = ModInfoAndActionPane(modDescriptionLabel, detailWidth - 40f)
    private val scrollActionTable = ModsScrollPane(modActionTable)
    private val optionsManager = ModManagementOptions(this, previousOptions)

    private var lastSelectedButton: ModDecoratedButton? = null
    private var lastSyncMarkedButton: ModDecoratedButton? = null
    private var restoringSelection = false
    private var selectedMod: GithubAPI.Repo? = null
    private val downloadsInProgress = HashMap<String, String>()

    // Enable re-sorting and syncing entries in 'installed' and 'repo search' ScrollPanes
    // Keep metadata and buttons in separate pools
    private val installedModInfo = previousInstalledMods ?: HashMap(RulesetCache.size)
    private val onlineModInfo = previousOnlineMods ?: game.files.loadModCache().associateByTo(HashMap()) { it.name }
    private val excludedModAuthors = game.files.loadExcludedModAuthors()
    private val modButtons: HashMap<ModUIData, ModDecoratedButton> = HashMap(100)

    // cleanup - background processing needs to be stopped on exit and memory freed
    private var runningSearchJob: Job? = null
    private var stopBackgroundTasks = false
    private var screenDisposed = false
    private var localModTransferServer: LocalModTransferServer? = null

    override fun dispose() {
        // make sure the worker threads will not continue trying their time-intensive job
        screenDisposed = true
        localModTransferServer?.close()
        localModTransferServer = null
        runningSearchJob?.cancel()
        stopBackgroundTasks = true
        super.dispose()
    }


    init {
        root.setFillParent(true)
        root.background = ModManagementStyle.fill(ModManagementStyle.background)
        root.pad(12f, 16f, 12f, 16f)
        stage.addActor(root)
        rightSideButton.isVisible = false
        closeButton.onActivation {
            if (singleColumn && showingDetails) {
                showingDetails = false
                refreshBody()
                return@onActivation
            }
            val tileSets = ImageGetter.getAvailableTilesets()
            if (game.settings.tileSet !in tileSets) game.settings.tileSet = tileSets.first()
            val screen = game.popScreen()
            if (screen is MainMenuScreen)
                screen.game.replaceCurrentScreen { MainMenuScreen() }
        }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)
        discoverButton.onClick { selectList(installed = false) }
        installedButton.onClick { selectList(installed = true) }
        detailsBackButton.onClick {
            showingDetails = false
            refreshBody()
        }
        initLayout()

        if (installedModInfo.isEmpty()) refreshInstalledModInfo()
        refreshInstalledModTable()
        refreshOnlineModTable()
        selectInitialMod()
        if (game.platformCapabilities.onlineModManagement) reloadOnlineMods()
    }

    override fun render(delta: Float) {
        val background = ModManagementStyle.background
        Gdx.gl.glClearColor(background.r, background.g, background.b, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        stage.act()
        stage.viewport.apply()
        stage.draw()
    }

    private fun initLayout() {
        val header = Table()
        val title = "Mods".toLabel(ModManagementStyle.text, fontSize = UiTypography.size(TextRole.PageTitle, legacySize = 28))
        val addButton = ModManagementStyle.button("Add mods")
        addButton.label.wrap = false
        addButton.add(ModIcon.Plus.image(ModManagementStyle.text)).size(24f).padLeft(8f)
        addButton.onClick { openAddMods() }
        val addButtonWidth = maxOf(160f, addButton.prefWidth)
        val headerControlHeight = maxOf(ModManagementStyle.touchHeight, addButton.prefHeight, closeButton.prefHeight)
        val tabs = Table().apply {
            background = ModManagementStyle.rounded(ModManagementStyle.surface)
            pad(4f)
            add(discoverButton).width(if (singleColumn) 180f else 192f).minHeight(ModManagementStyle.touchHeight).padRight(8f)
            add(installedButton).width(if (singleColumn) 180f else 192f).minHeight(ModManagementStyle.touchHeight)
        }
        header.add(closeButton).minWidth(100f).height(headerControlHeight).padRight(16f)
        header.add(title).left().expandX().padRight(16f)
        if (singleColumn) {
            header.add(addButton).width(addButtonWidth).height(headerControlHeight)
            header.row()
            header.add(tabs).colspan(3).growX().padTop(12f)
        } else {
            header.add(tabs).padRight(16f)
            header.add(addButton).width(addButtonWidth).height(headerControlHeight)
        }
        root.add(header).growX().padBottom(12f).row()
        root.add(body).grow().minHeight(0f)

        listPane.top()
        val search = optionsManager.searchField
        search.style = com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(search.style).apply {
            background = ModManagementStyle.rounded(ModManagementStyle.surface)
            fontColor = ModManagementStyle.text
            messageFontColor = ModManagementStyle.muted
        }
        search.onChange {
            refreshInstalledModTable()
            refreshOnlineModTable()
        }
        val filterButton = ModManagementStyle.button("Sort and Filter")
        // The visible glyph is compact; its full translated action remains available to accessibility/tooltips.
        filterButton.name = "mod-filter"
        filterButton.labelCell.clearActor()
        filterButton.add(ModIcon.Filter.image()).size(28f)
        filterButton.onClick { optionsManager.openPopup() }
        val searchRow = Table()
        searchRow.add(search).growX().minWidth(0f).height(ModManagementStyle.touchHeight).padRight(8f)
        searchRow.add(filterButton).size(ModManagementStyle.touchHeight).padRight(8f)
        searchRow.add(sortHolder).width(if (singleColumn) 180f else 160f).minWidth(0f).minHeight(ModManagementStyle.touchHeight)
        listPane.add(searchRow).growX().padBottom(12f).row()
        val summary = Table()
        summary.add(countLabel).growX().minWidth(0f).left().padRight(12f)
        summary.add(sourceLabel).width(minOf(180f * game.settings.fontSizeMultiplier, listWidth * .4f)).minWidth(0f).right()
        listPane.add(summary).growX().padBottom(8f).row()
        listPane.add(queryStatus).growX().row()
        listPane.add(listHolder).grow().minHeight(0f)

        for (scroll in listOf(scrollInstalledMods, scrollOnlineMods, scrollActionTable)) {
            scroll.setScrollingDisabled(true, false)
            scroll.setOverscroll(false, false)
        }
        detailPane.background = ModManagementStyle.rounded(ModManagementStyle.surface)
        detailPane.pad(16f, 20f, 14f, 20f)
        if (singleColumn && !UiNavigation.isIos) detailPane.add(detailsBackButton).left().minHeight(58f).padBottom(8f).row()
        detailPane.add(scrollActionTable).grow().minHeight(0f).row()
        actionFooter.add(rightSideButton).growX().minWidth(0f).minHeight(ModManagementStyle.touchHeight).row()
        actionFooter.add(actionHint).growX().minWidth(0f).padTop(8f)
        detailPane.add(actionFooter).growX().padTop(12f)
        modActionTable.add(ModManagementStyle.label("Select a mod to view its details", TextRole.Body, ModManagementStyle.muted))
            .width(detailWidth - 40f).padTop(30f)
        refreshBody()
    }

    private fun refreshBody() {
        body.clear()
        listHolder.clear()
        listHolder.add(if (showingInstalled) scrollInstalledMods else scrollOnlineMods).grow().minWidth(0f).minHeight(0f)
        discoverButton.style.up = ModManagementStyle.control(if (showingInstalled) ModManagementStyle.surface else ModManagementStyle.raised)
        refreshSortPicker()
        refreshListSummary()
        installedButton.style.up = ModManagementStyle.control(if (showingInstalled) ModManagementStyle.raised else ModManagementStyle.surface)
        if (ExpeditionBlueTheme.enabled) {
            discoverButton.isChecked = !showingInstalled
            installedButton.isChecked = showingInstalled
        }
        if (singleColumn) {
            body.add(if (showingDetails) detailPane else listPane).grow().minWidth(0f).minHeight(0f)
        } else {
            body.add(listPane).width(listWidth).growY().minHeight(0f).padRight(16f)
            body.add(detailPane).width(detailWidth).growY().minHeight(0f)
        }
    }

    private fun selectList(installed: Boolean) {
        showingInstalled = installed
        showingDetails = false
        refreshBody()
        selectInitialMod()
        if (singleColumn) {
            showingDetails = false
            refreshBody()
        }
    }

    private fun selectInitialMod() {
        val info = if (showingInstalled) installedModInfo else onlineModInfo
        val table = if (showingInstalled) installedModsTable else onlineModsTable
        val visible = info.values.filter { it.matchesFilter(optionsManager.getFilter()) &&
            (showingInstalled || it.author() !in excludedModAuthors) }
        val selection = visible.firstOrNull { it.name == selectedModName }
            ?: table.children.filterIsInstance<ModDecoratedButton>().firstOrNull()?.let { button ->
                visible.firstOrNull { modButtons[it] === button }
            }
        if (selection == null) {
            selectedModName = null
            selectedMod = null
            rightSideButton.isVisible = false
            actionHint.setText("")
            modActionTable.clear()
            modActionTable.add(ModManagementStyle.label("Select a mod to view its details", TextRole.Body, ModManagementStyle.muted))
                .width(detailWidth - 40f).padTop(30f)
            return
        }
        val previousDetails = showingDetails
        val previousScroll = scrollActionTable.scrollY
        val sameSelection = selectedModName == selection.name
        val button = getCachedModButton(selection)
        restoringSelection = true
        try {
            if (showingInstalled) installedButtonAction(selection, button)
            else onlineButtonAction(selection.repo!!, button)
        } finally { restoringSelection = false }
        showingDetails = previousDetails
        if (sameSelection) scrollActionTable.scrollY = previousScroll
        refreshListSummary()
    }

    internal fun refreshSortPicker() {
        sortHolder.clear()
        sortHolder.add(optionsManager.inlineSort(showingInstalled)).growX().minWidth(0f).minHeight(ModManagementStyle.touchHeight)
    }

    private fun refreshListSummary() {
        val info = if (showingInstalled) installedModInfo else onlineModInfo
        val count = info.values.count { it.matchesFilter(optionsManager.getFilter()) &&
            (showingInstalled || it.author() !in excludedModAuthors) }
        countLabel.setText(optionsManager.category.label.tr() + " · " + "[${count}] mods".tr())
        sourceLabel.setText((if (showingInstalled) "Current mods" else "Downloadable mods").tr())
        queryStatus.clear()
        if (!showingInstalled && (onlineLoading || onlineFailed)) {
            queryStatus.add(ModManagementStyle.label(if (onlineFailed) "Could not download mod list" else "Loading...", TextRole.Body,
                if (onlineFailed) ModManagementStyle.danger else ModManagementStyle.muted)).growX().minWidth(0f)
            if (onlineFailed) {
                val retry = ModManagementStyle.button("Retry")
                retry.onClick { reloadOnlineMods() }
                queryStatus.add(retry).width(minOf(132f * game.settings.fontSizeMultiplier, listWidth * .32f))
                    .minHeight(ModManagementStyle.touchHeight).padLeft(8f)
            }
        }
    }

    private fun addEmptyState(table: Table) {
        if (onlineLoading && table === onlineModsTable) return
        val empty = Table().apply {
            add(ModManagementStyle.label("No mods found", TextRole.PageTitle)).growX().minWidth(0f).padTop(32f).padBottom(16f).row()
            val clear = ModManagementStyle.button("Clear filters")
            clear.onClick { optionsManager.clearFilter() }
            add(clear).width(minOf(220f * game.settings.fontSizeMultiplier, listWidth))
                .minHeight(ModManagementStyle.touchHeight)
        }
        table.add(empty).growX().minWidth(0f).row()
    }

    private fun openAddMods() {
        val popup = Popup(this)
        popup.addTitleWithCloseButton("Add mods".toLabel(fontSize = UiTypography.size(TextRole.PageTitle, legacySize = 24)))
        val link = getDownloadFromUrlButton { popup.close() }.also {
            it.isEnabled = game.platformCapabilities.onlineModManagement
            ModManagementStyle.styleButton(it)
        }
        popup.add(link).width(minOf(stage.width - 80f, 520f)).minHeight(ModManagementStyle.touchHeight).padBottom(12f).row()
        if (game.platformCapabilities.localModTransfer) {
            val receive = ModManagementStyle.button("Receive Mod")
            receive.onClick { popup.close(); openLocalModReceiver() }
            popup.add(receive).growX().minHeight(ModManagementStyle.touchHeight).row()
        }
        popup.open()
    }

    private fun revealDetails(name: String) {
        selectedModName = name
        scrollActionTable.scrollY = 0f
        if (!restoringSelection) {
            showingDetails = true
            if (singleColumn) refreshBody()
        }
    }

    private fun reloadOnlineMods() {
        onlineFailed = false
        onlineLoading = true
        refreshOnlineModTable()
        tryDownloadPage(1)
    }

    /** background worker: querying GitHub for Mods (repos with 'unciv-mod' in its topics)
     *
     *  calls itself for the next page of search results
     */
    private fun tryDownloadPage(pageNum: Int) {
        runningSearchJob = Concurrency.run("GitHubSearch") {
            val repoSearch: GithubAPI.RepoSearch?
            try {
                repoSearch = Github.tryGetGithubReposWithTopic(pageNum, amountPerPage)
            } catch (ex: Exception) {
                Log.error("Could not download mod list", ex)
                launchOnGLThread {
                    if (!screenDisposed) { onlineLoading = false; onlineFailed = true; refreshOnlineModTable() }
                }
                runningSearchJob = null
                return@run
            }

            if (!isActive) return@run
            if (repoSearch == null) {
                launchOnGLThread {
                    if (!screenDisposed) { onlineLoading = false; onlineFailed = true; refreshOnlineModTable() }
                }
                return@run
            }

            launchOnGLThread { addModInfoFromRepoSearch(repoSearch, pageNum) }
            runningSearchJob = null
        }
    }

    private fun addModInfoFromRepoSearch(repoSearch: GithubAPI.RepoSearch, pageNum: Int) {
        for (repo in repoSearch.items) {
            if (stopBackgroundTasks) return
            repo.name = repo.name.repoNameToFolderName()

            val installedMod = RulesetCache.values.firstOrNull { it.name == repo.name }
            val isUpdatedVersionOfInstalledMod = installedMod?.modOptions?.let {
                it.lastUpdated != "" && it.lastUpdated != repo.pushed_at
            } == true

            if (installedMod != null) {

                if (isUpdatedVersionOfInstalledMod) {
                    val modInfo = installedModInfo[repo.name]!!
                    modInfo.hasUpdate = true
                    modButtons[modInfo]?.updateIndicators()
                }

                if (installedMod.modOptions.author.isEmpty()) {
                    try {
                        Github.rewriteModOptions(repo, installedMod.folderLocation!!)
                    } catch (ex: SerializationException) {
                        Log.error("Error while adding mod info from repo search:", ex)
                        return
                    } catch (ex: Exception) {
                        Log.error("Error while adding mod info from repo search:", ex)
                        return
                    }
                    installedMod.modOptions.author = repo.owner.login
                    installedMod.modOptions.modSize = repo.size
                    installedMod.modOptions.topics = repo.topics
                }
            }

            val mod = ModUIData(repo, isUpdatedVersionOfInstalledMod)
            onlineModInfo[repo.name] = mod
            modButtons.remove(mod) // Remove *cached* mod button since we have NEW DATA
            if (mod.matchesFilter(optionsManager.getFilter()) && mod.author() !in excludedModAuthors) {
                onlineModsTable.add(getCachedModButton(mod)).growX().minWidth(0f).row()
            }
        }

        Concurrency.run("Cache mod list"){
            game.files.saveModCache(onlineModInfo.values.toList())
        }

        // Now the tasks after the 'page' of search results has been fully processed
        // The search has reached the last page!
        val hasMorePages = GithubAPI.hasMoreSearchPages(pageNum, amountPerPage, repoSearch)
        if (!hasMorePages) {
            // Check: It is also not impossible we missed a mod - just inform user
            if (repoSearch.incomplete_results) {
                markOnlineQueryIncomplete()
            }
        }

        onlineLoading = hasMorePages
        refreshOnlineModTable()
        if (!showingInstalled && selectedModName == null) selectInitialMod()
        onlineModsTable.pack()
        // Shouldn't actor.parent.actor = actor be a no-op? No, it has side effects we need.
        // See [commit for #3317](https://github.com/yairm210/Unciv/commit/315a55f972b8defe22e76d4a2d811c6e6b607e57)
        scrollOnlineMods.actor = onlineModsTable

        // continue search unless last page was reached
        if (hasMorePages && !stopBackgroundTasks)
            tryDownloadPage(pageNum + 1)
    }

    private fun markOnlineQueryIncomplete() {
        val retryLabel = ModManagementStyle.label("Online query result is incomplete", TextRole.Body, ModManagementStyle.danger)
        retryLabel.touchable = Touchable.enabled
        retryLabel.onClick {
            reloadOnlineMods()
        }
        onlineModsTable.add(retryLabel)
    }

    private fun syncOnlineSelected(modName: String, button: ModDecoratedButton) {
        syncSelected(modName, button, installedModInfo, scrollInstalledMods)
    }
    private fun syncInstalledSelected(modName: String, button: ModDecoratedButton) {
        syncSelected(modName, button, onlineModInfo, scrollOnlineMods)
    }
    private fun syncSelected(modName: String, button: ModDecoratedButton, modNameToData: HashMap<String, ModUIData>, scroll: ScrollPane) {
        // manage selection color for user selection
        lastSelectedButton?.color = Color.WHITE
        button.color = ExpeditionBlueTheme.color(Color.BLUE, ExpeditionBlueTheme.gold)
        lastSelectedButton = button
        if (lastSelectedButton != lastSyncMarkedButton)
            lastSyncMarkedButton?.color = Color.WHITE
        lastSyncMarkedButton = null
        // look for sync-able same mod in other list
        val buttonInOtherList = modButtons[modNameToData[modName]] ?: return
        // scroll into view - we know the containing Tables all have cell default padding 10f
        scroll.scrollTo(0f, buttonInOtherList.y - 10f, scroll.actor.width, buttonInOtherList.height + 20f, true, false)
        // and color it so it's easier to find. ROYAL and SLATE too dark.
        buttonInOtherList.color = ExpeditionBlueTheme.color(Color.valueOf("7499ab"), ExpeditionBlueTheme.muted)  // about halfway between royal and sky
        lastSyncMarkedButton = buttonInOtherList
    }


    /** Create the special "Download from URL" button */
    private fun getDownloadFromUrlButton(beforeOpen: () -> Unit = {}): TextButton {
        val downloadButton = "Download mod from URL".toTextButton()
        downloadButton.onClick {
            beforeOpen()
            val popup = Popup(this)
            val columns = if (UiNavigation.isIos) 2 else 1
            popup.addGoodSizedLabel("Please enter the mod repository -or- archive zip -or- branch -or- release -or- commit url:").colspan(columns).row()
            val textField = UncivTextField("").apply { maxLength = 666 }
            popup.add(textField).width(stage.width / 2).colspan(columns).row()
            val pasteLinkButton = "Paste from clipboard".toTextButton()
            pasteLinkButton.onClick {
                textField.text = Gdx.app.clipboard.contents
            }
            popup.add(pasteLinkButton).colspan(columns).row()
            lateinit var actualDownloadButton: TextButton
            val downloadAction: () -> Unit = {
                actualDownloadButton.setStartingDownload()
                Concurrency.run {
                    val repo = GithubAPI.Repo.parseUrl(textField.text)
                    if (repo == null) {
                        Concurrency.runOnGLThread {
                            ToastPopup("«RED»{Invalid link!}«»", this@ModManagementScreen)
                            actualDownloadButton.setText("Download".tr())
                            actualDownloadButton.enable()
                        }
                    } else {
                        downloadMod(repo, { state, progress ->
                            actualDownloadButton.setText(state.message(progress).tr())
                        }) { success ->
                            if (success) popup.close()
                            else { actualDownloadButton.setText("Download".tr()); actualDownloadButton.enable() }
                        }
                    }
                }
            }
            if (UiNavigation.isIos) {
                popup.addCloseButton(com.unciv.Constants.cancel).growX()
                actualDownloadButton = popup.addButton("Download", action = downloadAction).growX().actor
            } else {
                actualDownloadButton = "Download".toTextButton().apply { onClick(downloadAction) }
                popup.add(actualDownloadButton).row()
                popup.addCloseButton()
            }
            popup.open()
        }
        return downloadButton
    }

    private fun openLocalModReceiver() {
        localModTransferServer?.close()
        val popup = Popup(this)
        val receiver = try {
            LocalModTransferServer(game.files.getModsFolder()) { modName ->
                Concurrency.runOnGLThread("ModTransferInstalled") {
                    if (screenDisposed) return@runOnGLThread
                    val toast = ToastPopup("[$modName] received!", this@ModManagementScreen)
                    reloadCachesAfterModChange(delete = false, modName) {
                        toast.close()
                        ToastPopup(
                            "{[$modName] was received, but is defective!}\n{For more information, see Options-Locate mod errors.}",
                            this@ModManagementScreen,
                            4000L,
                        )
                    }
                    if (RulesetCache[modName]?.modOptions?.hasUnique(UniqueType.ModIsAudioVisualOnly) == true)
                        game.settings.visualMods.add(modName)
                    updateInstalledModUIData(modName)
                    refreshInstalledModTable()
                    popup.close()
                }
            }
        } catch (ex: Exception) {
            ToastPopup("Could not start Mod receiver: [${ex.message ?: "Unknown error"}]", this)
            return
        }
        localModTransferServer = receiver
        popup.closeListeners += {
            receiver.close()
            if (localModTransferServer === receiver) localModTransferServer = null
        }
        val closeButton = popup.addCloseButton().actor
        if (!UiNavigation.isIos) {
            closeButton.remove()
            popup.add(Table().apply {
                add().expandX()
                add(closeButton)
            }).row()
        }
        popup.addGoodSizedLabel("Open this address on your computer:",size = UiTypography.size(TextRole.SectionTitle, legacySize = 20)).row()
        val rawLabelStyle = Label.LabelStyle(BaseScreen.skin.get(Label.LabelStyle::class.java)).apply {
            font = Fonts.font
        }
        val urlToken = "UNCIV_RECEIVER_URL_TOKEN"
        val codeToken = "UNCIV_ACCESS_CODE_TOKEN"
        popup.add(Label(receiver.uploadUrl, rawLabelStyle).apply {
            setFontScale(UiTypography.size(TextRole.Supporting, legacySize = 18) / Fonts.ORIGINAL_FONT_SIZE)
            setAlignment(Align.center)
            wrap = true
        }).width(stage.width * 0.78f).row()
        val accessCodeLabel = "Access code: [$codeToken]".tr().replace(codeToken, receiver.accessCode)
        popup.add(Label(accessCodeLabel, rawLabelStyle).apply {
            setFontScale(UiTypography.size(TextRole.SectionTitle) / Fonts.ORIGINAL_FONT_SIZE)
            setAlignment(Align.center)
            wrap = true
        }).width(stage.width / 2).row()
        popup.addGoodSizedLabel("Keep the phone and computer on the same Wi-Fi network. Allow local network access if iOS asks.",size = UiTypography.size(TextRole.Body, legacySize = 18)).row()
        popup.addGoodSizedLabel("The receiver stops after one upload or when you close this window.",size = UiTypography.size(TextRole.Body, legacySize = 18)).row()
        popup.addButton("Copy address") {
            Gdx.app.clipboard.contents = receiver.uploadUrl
            ToastPopup("Address copied", this)
        }
        popup.addButton("Copy address + code") {
            Gdx.app.clipboard.contents = ("Receiver URL: [$urlToken]\nAccess code: [$codeToken]")
                .tr()
                .replace(urlToken, receiver.uploadUrl)
                .replace(codeToken, receiver.accessCode)
            ToastPopup("Address and access code copied", this)
        }.row()
        popup.open()
    }

    /** Used as onClick handler for the online Mod list buttons */
    private fun onlineButtonAction(repo: GithubAPI.Repo, button: ModDecoratedButton) {
        revealDetails(repo.name)
        syncOnlineSelected(repo.name, button)
        showModDescription(repo.name)

        if (!repo.hasUpdatedSize) {
            // Setting this later would mean a failed query is repeated on the next mod click,
            // and click-spamming would launch several github queries.
            repo.hasUpdatedSize = true
            Concurrency.run("GitHubParser") {
                try {
                    val repoSize = Github.getRepoSize(repo)
                    if (repoSize > -1) {
                        launchOnGLThread {
                            repo.size = repoSize
                            if (selectedMod == repo) modActionTable.updateSize(repoSize)
                            onlineModInfo[repo.name]?.takeIf { it.repo === repo }?.let { info ->
                                modButtons[info]?.updateUIData(info)
                            }
                        }
                    }
                } catch (_: IOException) {
                    /* Parsing of mod size failed, do nothing */
                }
            }
        }

        rightSideButton.isVisible = true
        actionHint.setText("Choose this mod in the new game settings.".tr())
        val installed = installedModInfo[repo.name]
        if (installed != null && !installed.hasUpdate) {
            ModManagementStyle.styleButton(rightSideButton)
            rightSideButton.setText("Installed".tr())
            rightSideButton.isDisabled = true
            rightSideButton.clearActivationActions(ActivationTypes.Tap)
        } else configureDownloadAction(repo, if (installed?.hasUpdate == true) "Update mod" else "Download")

        selectedMod = repo
        modActionTable.update(repo)
    }

    private fun TextButton.setStartingDownload() {
        setText("Downloading...".tr())
        isDisabled = true
        touchable = Touchable.disabled
    }
    /** Download and install a mod in the background, called both from the right-bottom button and the URL entry popup */
    private fun downloadMod(repo: GithubAPI.Repo, updateProgressPercent: ((DownloadAndExtractState, Int?)->Unit)? = null, postAction: (Boolean) -> Unit = {}) {
        Concurrency.run("DownloadMod") { // to avoid ANRs - we've learnt our lesson from previous download-related actions
            try {
                val modFolder =
                    repo.downloadAndExtract(updateProgressPercent)
                        ?: throw Exception("Exception during GitHub download")    // downloadAndExtract returns null for 404 errors and the like -> display something!
                launchOnGLThread {
                    val repoName = modFolder.name()  // repo.name still has the replaced "-"'s
                    val toast = ToastPopup("[$repoName] Downloaded!", this@ModManagementScreen)
                    reloadCachesAfterModChange(delete = false, modFolder.name()) {
                        toast.close()
                        val msg = "{[$repoName] was downloaded, but is defective!}" +
                            "\n{For more information, see Options-Locate mod errors.}"
                        ToastPopup(msg, this@ModManagementScreen, 4000L)
                    }

                    if (RulesetCache[repoName]?.modOptions?.hasUnique(UniqueType.ModIsAudioVisualOnly) == true)
                        game.settings.visualMods.add(repoName)
                    updateInstalledModUIData(repoName)
                    refreshInstalledModTable()
                    if (selectedModName == repoName) {
                        lastSelectedButton?.let { syncOnlineSelected(repoName, it) }
                        showModDescription(repoName)
                    }
                    unMarkUpdatedMod(repoName)
                    postAction(true)
                }
            } catch (ex: UncivShowableException) {
                Log.error("Could not download $repo", ex)
                launchOnGLThread {
                    ToastPopup(ex.message, this@ModManagementScreen)
                    postAction(false)
                }
            } catch (ex: Exception) {
                Log.error("Could not download $repo", ex)
                launchOnGLThread {
                    ToastPopup("Could not download [${repo.name}]", this@ModManagementScreen)
                    postAction(false)
                }
            }
        }
    }

    /** Our data on the Mod needs refreshing description after download or update */
    private fun updateInstalledModUIData(modName: String) {
        val ruleset = RulesetCache[modName]
            ?: return  // Bail if download was not actually successful?
        // When someone marks a Mod as 'permanent audiovisual', then deletes it, then redownloads, that
        // 'permanent audiovisual' will still be valid - re-evaluate here or remove the setting in the delete code.
        val isVisual = game.settings.visualMods.contains(modName)
        val newModUIData = ModUIData(ruleset, isVisual)
        installedModInfo[modName] = newModUIData
        // The ModUIData in the actual button is now out of sync, but can be indexed using the new instance
        modButtons[newModUIData]?.run {
            updateUIData(newModUIData)
            // The listeners have also captured a now outdated ModUIData
            setModButtonOnClick(this, newModUIData)
            // Simulate click to update the ModInfoAndActionPane
            if (showingInstalled && selectedModName == modName) installedButtonAction(newModUIData, this)
        }
    }

    /** Remove the visual indicators for an 'updated' mod after re-downloading it.
     *  (" - Updated" on the button text in the online mod list and the icon beside the installed mod's button)
     *  It should be up to date now (unless the repo's date is in the future relative to system time)
     *
     *  (called under postRunnable posted by background thread)
     */
    private fun unMarkUpdatedMod(name: String) {
        installedModInfo[name]?.run {
            hasUpdate = false
            modButtons[this]?.updateIndicators()
        }
        onlineModInfo[name]?.run {
            hasUpdate = false
            modButtons[this]?.setText(cleanModName(name))
        }
        if (optionsManager.sortInstalled == SortType.Status)
            refreshInstalledModTable()
        if (optionsManager.sortOnline == SortType.Status)
            refreshOnlineModTable()
    }

    /** Rebuild the right-hand column for clicks on installed mods
     *  Display single mod metadata, offer additional actions (delete is elsewhere)
    */
    private fun refreshInstalledModActions(mod: Ruleset) {
        selectedMod = null
        // show mod information first - this starts by clearing modActionTable
        modActionTable.update(mod)

        // The mod may have been deleted by the user while an update download was in progress
        val modInfo = installedModInfo[mod.name] ?: return

        // offer 'permanent visual mod' toggle
        val isVisualMod = game.settings.visualMods.contains(mod.name)
        if (modInfo.isVisual != isVisualMod) {
            modInfo.isVisual = isVisualMod
            modButtons[modInfo]?.updateIndicators()
        }

        modActionTable.addVisualCheckBox(isVisualMod) { checked ->
            if (checked)
                game.settings.visualMods.add(mod.name)
            else
                game.settings.visualMods.remove(mod.name)
            game.settings.save()
            ImageGetter.reloadImages()
            refreshInstalledModActions(mod)
            if (optionsManager.sortInstalled == SortType.Status)
                refreshInstalledModTable()
        }

        val checkModButton = ModManagementStyle.button("Check mod")
        checkModButton.onClick {
            if (ExpeditionBlueTheme.enabled)
                game.pushScreen { OptionsScreen(this, OptionsPopupPages.ModCheck, subSelect = mod.name) }
            else OptionsPopup(this, OptionsPopupPages.ModCheck, subSelect = mod.name).open()
        }
        modActionTable.addSecondaryAction(checkModButton)

        if (mod.folderLocation != null) {
            val delete = ModManagementStyle.button("Delete")
            delete.style.fontColor = ModManagementStyle.danger
            delete.onClick {
                ConfirmPopup(this, "Are you SURE you want to delete this mod?", "Delete [${cleanModName(mod.name)}]") {
                    deleteMod(mod)
                    selectInitialMod()
                }.open()
            }
            modActionTable.addSecondaryAction(delete)
        }
    }

    /** A completion belongs to the downloaded mod, never whichever row was selected later. */
    private fun configureDownloadAction(repo: GithubAPI.Repo, caption: String) {
        ModManagementStyle.styleButton(rightSideButton, primary = true)
        rightSideButton.setText(caption.tr())
        rightSideButton.enable()
        rightSideButton.clearActivationActions(ActivationTypes.Tap)
        val activeProgress = downloadsInProgress[repo.name]
        if (activeProgress != null) {
            rightSideButton.setStartingDownload()
            rightSideButton.setText(activeProgress)
            return
        }
        rightSideButton.onClick {
            downloadsInProgress[repo.name] = "Downloading...".tr()
            rightSideButton.setStartingDownload()
            downloadMod(repo, { state, progress ->
                Concurrency.runOnGLThread {
                    val text = state.message(progress).tr()
                    downloadsInProgress[repo.name] = text
                    if (selectedModName == repo.name && !screenDisposed) rightSideButton.setText(text)
                }
            }) { success ->
                downloadsInProgress.remove(repo.name)
                if (!screenDisposed && selectedModName == repo.name) {
                    if (success) selectInitialMod()
                    else configureDownloadAction(repo, caption)
                }
            }
        }
    }

    /** Rebuild the metadata on installed mods */
    private fun refreshInstalledModInfo() {
        installedModInfo.clear()
        for (mod in RulesetCache.values.asSequence().filter { it.name != "" }) {
            installedModInfo[mod.name] = ModUIData(mod, mod.name in game.settings.visualMods)
        }
    }

    private fun getCachedModButton(mod: ModUIData) = modButtons.getOrPut(mod) {
        val newButton = ModDecoratedButton(mod)
        setModButtonOnClick(newButton, mod)
        newButton
    }
    private fun setModButtonOnClick(button: ModDecoratedButton, mod: ModUIData) {
        if (mod.isInstalled) button.onClick { installedButtonAction(mod, button) }
        else button.onClick { onlineButtonAction(mod.repo!!, button) }
    }

    /** Rebuild the left-hand column containing all installed mods */
    internal fun refreshInstalledModTable() {
        installedButton.setText(("Installed".tr() + "  " + installedModInfo.size))
        installedModsTable.clear()
        val filter = optionsManager.getFilter()
        for (mod in installedModInfo.values.sortedWith(optionsManager.sortInstalled.comparator)) {
            if (!mod.matchesFilter(filter)) continue
            installedModsTable.add(getCachedModButton(mod)).growX().minWidth(0f).row()
        }
        if (installedModsTable.children.isEmpty) addEmptyState(installedModsTable)
        refreshListSummary()
        if (showingInstalled) selectInitialMod()
    }

    private fun installedButtonAction(mod: ModUIData, button: ModDecoratedButton) {
        rightSideButton.isVisible = true
        revealDetails(mod.name)
        actionHint.setText("Choose this mod in the new game settings.".tr())

        syncInstalledSelected(mod.name, button)
        refreshInstalledModActions(mod.ruleset!!)
        showModDescription(mod.name)
        val repo = onlineModInfo[mod.name]?.repo
        if (mod.hasUpdate && repo != null) configureDownloadAction(repo, "Update mod")
        else {
            ModManagementStyle.styleButton(rightSideButton)
            rightSideButton.setText("Installed".tr())
            rightSideButton.isDisabled = true
            rightSideButton.clearActivationActions(ActivationTypes.Tap)
        }
    }

    /** Delete a Mod, refresh ruleset cache and update installed mod table */
    private fun deleteMod(mod: Ruleset) {
        mod.folderLocation!!.deleteDirectory()
        reloadCachesAfterModChange(delete = true, mod.name)
        installedModInfo.remove(mod.name)
        unMarkUpdatedMod(mod.name)
        refreshInstalledModTable()
    }

    private fun reloadCachesAfterModChange(delete: Boolean, modName: String, onError: (()->Unit)? = null) {
        if (delete) {
            RulesetCache.remove(modName)
        } else {
            val errorLines = RulesetCache.reloadSingleRuleset(modName)
            if (errorLines.isNotEmpty()) onError?.invoke()
        }

        TileSetCache.loadTileSetConfigs()
        ImageGetter.reloadImages()
        UncivGame.Current.translations.tryReadTranslationForCurrentLanguage()
    }

    internal fun refreshOnlineModTable() {
//        if (runningSearchJob != null) {
//            ToastPopup("Sorting and filtering needs to wait until the online query finishes", this)
//            return  // cowardice: prevent concurrent modification, avoid a manager layer
//        }

        onlineModsTable.clear()
        if (!game.platformCapabilities.onlineModManagement) {
            onlineModsTable.add(ONLINE_MOD_MANAGEMENT_UNAVAILABLE.toLabel(fontColor = Color.GRAY, role = TextRole.Body)).row()
            onlineModsTable.pack()
            scrollOnlineMods.actor = onlineModsTable
            return
        }

        val filter = optionsManager.getFilter()
        // Important: sortedMods holds references to the original values, so the referenced buttons stay valid.
        // We update y and height here, we do not replace the ModUIData instances do the referenced buttons stay valid.
        val sortedMods = onlineModInfo.values.asSequence().sortedWith(optionsManager.sortOnline.comparator)
        for (mod in sortedMods) {
            if (!mod.matchesFilter(filter) || mod.author() in excludedModAuthors) continue
            onlineModsTable.add(getCachedModButton(mod)).growX().minWidth(0f).row()
        }

        onlineModsTable.pack()
        scrollOnlineMods.actor = onlineModsTable
        if (onlineModsTable.children.isEmpty) addEmptyState(onlineModsTable)
        refreshListSummary()
        if (!showingInstalled) selectInitialMod()
    }

    /** Updates the wrapped description inside the selected Mod detail pane. */
    private fun showModDescription(modName: String) {
        val onlineModDescription = onlineModInfo[modName]?.description ?: "" // shows github info
        val installedModDescription = installedModInfo[modName]?.description ?: "" // shows ruleset info
        val separator = if (onlineModDescription.isEmpty() || installedModDescription.isEmpty()) "" else "\n"
        val starsSuffix = onlineModInfo[modName]?.repo?.let { "\n[${it.stargazers_count}]${Fonts.star}".tr() }.orEmpty()
        val description = if (starsSuffix.isEmpty()) onlineModDescription else onlineModDescription.removeSuffix(starsSuffix)
        modDescriptionLabel.setText(description + separator + installedModDescription)
    }

    override fun recreate(): BaseScreen = ModManagementScreen(
        installedModInfo, onlineModInfo, showingInstalled, selectedModName, showingDetails, optionsManager.snapshot()
    )

}
