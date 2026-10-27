package com.unciv.ui.screens.civilopediascreen

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.stats.INamed
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.TutorialController
import com.unciv.utils.Concurrency
import com.unciv.utils.launchOnGLThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import com.badlogic.gdx.utils.Array as GdxArray

class CivilopediaSearchPopup(
    private val pediaScreen: CivilopediaScreen,
    private val linkAction: (String) -> Unit
) : Popup(pediaScreen, maxSizePercentage = if (pediaScreen.usesPages) 1f else .9f,
    ordinaryShell = pediaScreen.usesPages) {
    private var ruleset = pediaScreen.ruleset
    private val searchText = UncivTextField("")   // Always focused, "hint" never seen
    private val modSelect = ModSelectBox()
    private lateinit var resultExpander: ExpanderTab
    private val resultCell: Cell<Actor?>
    private val searchButton: TextButton

    private var searchJob: Job? = null
    private var checkLine: (String) -> Boolean = { _ -> false }
    private var focusOnOpen = true
    private var submittedText: String? = null
    private var submittedScope: String? = null
    private var restoredScrollY: Float? = null
    private var restoredInputState: SearchState? = null

    internal class SearchState(val text: String, val scope: String, val submittedText: String?,
                                    val submittedScope: String?, val expanded: Boolean, val scrollY: Float,
                                    val editing: Boolean, val cursor: Int, val selectionStart: Int, val selectionLength: Int)
    internal fun snapshot() = SearchState(searchText.text, modSelect.selected.key, submittedText, submittedScope,
        !::resultExpander.isInitialized || resultExpander.isOpen, getScrollPane()?.scrollY ?: 0f,
        keyboardFocus === searchText, searchText.cursorPosition, searchText.selectionStart, searchText.selection.length)
    internal fun restore(state: SearchState) {
        searchText.text = state.text
        modSelect.items.firstOrNull { it.key == state.submittedScope }?.let { modSelect.selected = it }
        state.submittedText?.let { startSearch(it) }
        modSelect.items.firstOrNull { it.key == state.scope }?.let { modSelect.selected = it }
        if (::resultExpander.isInitialized) resultExpander.isOpen = state.expanded
        restoredScrollY = state.scrollY
        restoredInputState = state
    }
    internal fun openResults() {
        focusOnOpen = restoredInputState?.editing ?: false
        open(true)
        focusOnOpen = true
    }
    internal fun cancelSearch() { searchJob?.cancel() }

    init {
        searchText.maxLength = 100

        if (pediaScreen.usesPages) {
            name = "civilopedia-search-page"
            background = ImageGetter.getWhiteDotDrawable().tint(ExpeditionBlueTheme.background)
            innerTable.background = background
            innerTable.top()
            getCell(innerTable).grow()
            addTitleWithCloseButton()
            val width = if (ordinaryPresentation) popupContentWidth
                else (pediaScreen.stage.width - 80f).coerceAtLeast(240f)
            val touch = UiNavigation.minimumTouchSize()
            val fields = Table().apply { defaults().left().padBottom(10f) }
            fields.add("Search text:".toLabel(fontColor = ExpeditionBlueTheme.muted, role = TextRole.Body)).row()
            fields.add(searchText).width(width).minHeight(touch).growX().row()
            fields.add("Mod filter:".toLabel(fontColor = ExpeditionBlueTheme.muted, role = TextRole.Body)).padTop(8f).row()
            fields.add(modSelect).width(width).minHeight(touch).growX().row()
            searchButton = addButton("Search!", KeyCharAndCode.RETURN,
                BaseScreen.skin.get("positive", TextButton.TextButtonStyle::class.java)) {
                startSearch(searchText.text)
            }.actor.apply {
                name = "civilopedia-run-search"
            }
            add(fields).growX().row()
            resultCell = add().width(width).growX()
            row()
            bottomTable.pad(0f)
            clickBehindToClose = false
        } else {
            add("Search text:".toLabel(TextRole.Body))
            add(searchText).growX().row()
            add("Mod filter:".toLabel(TextRole.Body))
            add(modSelect).growX().row()
            resultCell = add().colspan(2).growX()
            row()

            if (UiNavigation.isMobile) addCloseButton(com.unciv.Constants.cancel).growX()
            searchButton = addButton("Search!", KeyCharAndCode.RETURN) {
                startSearch(searchText.text)
            }.actor
            if (!UiNavigation.isMobile) addCloseButton()
        }
        showListeners.add {
            val restored = restoredInputState
            restoredInputState = null
            if (focusOnOpen) {
                keyboardFocus = searchText
                when {
                    restored == null -> searchText.selectAll()
                    restored.selectionLength > 0 -> searchText.setSelection(restored.selectionStart,
                        restored.selectionStart + restored.selectionLength)
                    else -> searchText.cursorPosition = restored.cursor
                }
            } else {
                keyboardFocus = null
                Gdx.input.setOnscreenKeyboardVisible(false)
            }
        }
        closeListeners.add {
            if (isSearchRunning()) searchJob!!.cancel()
            if (pediaScreen.usesPages) keyboardFocus = null
        }
    }

    private fun isSearchRunning() = searchJob?.isActive == true

    private fun startSearch(text: String) {
        searchButton.disable()
        if (pediaScreen.usesPages) keyboardFocus = null
        Gdx.input.setOnscreenKeyboardVisible(false)  // UncivTextField's FocusListener will give it back when needed

        @Suppress("LiftReturnOrAssignment")
        if (text.isEmpty()) {
            checkLine = { true }
        } else if (".*" in text || '\\' in text || '|' in text) {
            try {
                val regex = Regex(text, RegexOption.IGNORE_CASE)
                checkLine = { regex.containsMatchIn(it) }
            } catch (ex: Exception) {
                ToastPopup("Invalid regular expression", pediaScreen, 4000)
                searchButton.enable()
                return
            }
        } else {
            val words = text.split(' ').toSet()
            checkLine = { line -> words.all { line.contains(it, ignoreCase = true) } }
        }

        ruleset = modSelect.selectedRuleset()
        submittedText = text
        submittedScope = modSelect.selected.key

        if (::resultExpander.isInitialized) {
            resultExpander.innerTable.clear()
        } else {
            resultExpander = ExpanderTab("Results") {}
            resultCell.setActor(resultExpander)
            resultExpander.innerTable.defaults().growX().pad(2f)
        }

        searchJob = Concurrency.run("PediaSearch") {
            searchLoop()
        }
        searchJob!!.invokeOnCompletion {
            searchJob = null
            Concurrency.runOnGLThread {
                finishSearch()
            }
        }
    }

    private fun CoroutineScope.searchLoop() {
        val gameInfo = UncivGame.getGameInfoOrNull()
        for (category in CivilopediaCategories.entries) {
            if (!isActive) break
            if (!ruleset.modOptions.isBaseRuleset && category == CivilopediaCategories.Tutorial)
                continue  // Search tutorials only when the mod filter is a base ruleset
            for (entry in category.getCategoryIterator(ruleset, gameInfo)) {
                if (!isActive) break
                if (entry !is INamed) continue
                if (!ruleset.modOptions.isBaseRuleset) {
                    val sort = entry.getSortGroup(ruleset)
                    if (category == CivilopediaCategories.UnitType && sort < 2)
                        continue  // Search "Domain:" entries only when the mod filter is a base ruleset
                    if (category == CivilopediaCategories.Belief && sort == BeliefType.None.ordinal)
                        continue  // Search "Beliefs" and "Religions" from `getCivilopediaBeliefsEntry`/`getCivilopediaReligionEntry` only when the mod filter is a base ruleset
                }
                if (entry is IHasUniques && entry.isHiddenFromCivilopedia(gameInfo, ruleset)) continue
                searchEntry(entry)
            }
        }
    }

    private fun CoroutineScope.searchEntry(entry: ICivilopediaText) {
        val scope = sequence {
            entry.getCivilopediaTextHeader()?.let { yield(it) }
            yieldAll(entry.civilopediaText)
            yieldAll(entry.getCivilopediaTextLines(ruleset))
        }
        for (line in scope) {
            if (!isActive) break
            val lineText = line.text.tr(hideIcons = true)
            if (!checkLine(lineText)) continue
            addResult(entry)
            break
        }
    }

    private fun CoroutineScope.addResult(entry: ICivilopediaText) {
        launchOnGLThread {
            val actor = entry.getIconName().toLabel(alignment = Align.left, role = TextRole.Body)
            val link = entry.makeLink()
            if (pediaScreen.usesPages) {
                actor.wrap = true
                resultExpander.innerTable.add(actor).width(if (ordinaryPresentation) popupContentWidth - 24f * popupUnit
                    else pediaScreen.stage.width - 130f)
                    .minHeight(UiNavigation.minimumTouchSize()).fillY().left().pad(8f, 4f, 8f, 4f).row()
            } else resultExpander.innerTable.add(actor).row()
            actor.onClick {
                linkAction(link)
                close()
            }
        }
    }

    private fun finishSearch() {
        searchButton.enable()
        if (resultExpander.innerTable.cells.isEmpty) {
            val nothingFound = FormattedLine("Nothing found!", color = "#f53", header = 3, centered = true)
                .render(0f)
            resultExpander.innerTable.add(nothingFound)
        }
        restoredScrollY?.let { y ->
            validate()
            getScrollPane()?.apply {
                layout()
                scrollY = y
                updateVisualScroll()
            }
            restoredScrollY = null
        }
    }

    class ModSelectEntry(val key: String, val translate: Boolean = false) {
        override fun toString() = if (translate) key.tr() else key
    }

    private inner class ModSelectBox : SelectBox<ModSelectEntry>(BaseScreen.skin) {
        init {
            val mods = pediaScreen.ruleset.mods
            val entries = GdxArray<ModSelectEntry>(mods.size + 1)
            entries.add(ModSelectEntry("-Combined-", true))
            // This intersect is needed when pedia was called from the MainMenuScreen with an easter egg ruleset active -
            // they are not in the cache and have their elements not marked with originRuleset anyway.
            for (mod in mods.intersect(RulesetCache.keys)) entries.add(ModSelectEntry(mod))
            items = entries
            selectedIndex = 0
        }

        fun selectedRuleset(): Ruleset =
            if (selectedIndex == 0) pediaScreen.ruleset
            else RulesetCache[selected.key]!!  // `!!` guarded by the intersect above
    }
}
