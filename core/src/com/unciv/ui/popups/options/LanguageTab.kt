package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.getAscendant
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageKeyShortcuts
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageTables
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UncivTextField

internal class LanguageTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    private val languageGrid = Table()
    private val search = UncivTextField("Search languages").apply {
        name = "options-language-search"
        messageText = "Search languages".tr()
        setProgrammaticChangeEvents(true)
    }
    private val noResults by lazy {
        "Nothing found!".toLabel(OptionsPopupStyle.muted, OptionsPopupStyle.fontSize(17f)).apply { wrap = true }
    }
    private val languageTables by lazy {
        if (!optionsPopup.useSidebar) this.addLanguageTables(optionsPopup.tabs.prefWidth * 0.9f - 10f)
        else {
            val source = Table()
            val tables = source.addLanguageTables(optionsPopup.tabs.prefWidth - 48f)
            add("Current language".toLabel(OptionsPopupStyle.muted, OptionsPopupStyle.fontSize(14f))).left().pad(OptionsPopupStyle.points(8f)).row()
            add((com.unciv.models.metadata.LocaleCode.find(settings.language)?.nativeName ?: settings.language)
                .toLabel(OptionsPopupStyle.accent, OptionsPopupStyle.fontSize(18f))).left().pad(OptionsPopupStyle.points(8f)).row()
            search.style = TextField.TextFieldStyle(search.style).apply {
                font = optionsPopup.contentFont
                messageFont = optionsPopup.contentFont
                messageFontColor = OptionsPopupStyle.muted
                background = OptionsPopupStyle.surface(OptionsPopupStyle.sectionBackground)
                focusedBackground = background
            }
            val searchRow = Table().apply {
                add(search).minWidth(0f).growX().height(OptionsPopupStyle.points(44f))
                add("Clear search".toTextButton().apply {
                    name = "options-language-clear"
                    onClick { search.text = "" }
                }).minWidth(OptionsPopupStyle.points(44f)).minHeight(OptionsPopupStyle.points(44f)).padLeft(OptionsPopupStyle.points(8f))
            }
            add(searchRow).growX().padBottom(OptionsPopupStyle.points(16f)).row()
            for (language in tables) {
                language.pad(OptionsPopupStyle.points(12f))
                language.defaults().pad(OptionsPopupStyle.points(4f))
                language.getCell(language.children.first()).size(OptionsPopupStyle.points(26f))
                for (label in language.children.filterIsInstance<Label>()) {
                    label.wrap = true
                    language.getCell(label).minWidth(0f).growX()
                }
                OptionsPopupStyle.enlargeText(language, optionsPopup.contentFont)
            }
            add(languageGrid).growX().row()
            add(source.cells.first().actor).growX().pad(12f).row()
            tables
        }
    }
    private var chosenLanguage = settings.language

    init {
        pad(0f)
        defaults().pad(0f)
    }

    override fun lateInitialize() {
        for (langTable in languageTables) {
            langTable.onClick {
                chosenLanguage = langTable.language
                updateSelection()
            }
        }
        addLanguageKeyShortcuts(languageTables, getSelection = { chosenLanguage }) {
            if (optionsPopup.useSidebar && search.hasKeyboardFocus()) return@addLanguageKeyShortcuts
            chosenLanguage = it
            val pager = this.getAscendant<TabbedPager>()
                ?: return@addLanguageKeyShortcuts
            activated(pager.activePage, "", pager)
        }
        super.lateInitialize()
        if (optionsPopup.useSidebar) search.onChange { refreshLanguageGrid() }
    }

    private fun selectLanguage() {
        settings.language = chosenLanguage
        settings.updateLocaleFromLanguage()
        game.translations.tryReadTranslationForCurrentLanguage()
        reloadWorldAndOptions()
    }

    private fun updateSelection() {
        languageTables.forEach {
            it.update(chosenLanguage)
            if (optionsPopup.useSidebar) {
                val surface = OptionsPopupStyle.surface(OptionsPopupStyle.sectionBackground)
                it.background = if (it.language == chosenLanguage)
                    OptionsPopupStyle.outlinedSurface(OptionsPopupStyle.sectionBackground, OptionsPopupStyle.accent) else surface
            }
        }
        if (chosenLanguage != settings.language)
            selectLanguage()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        updateSelection()
        if (optionsPopup.useSidebar) {
            search.text = ""
            refreshLanguageGrid()
        }
        val selectedTable = languageTables.firstOrNull { it.language == chosenLanguage }
            ?: return
        pager.pageScrollTo(selectedTable, true)
    }

    private fun refreshLanguageGrid() {
        val query = search.text.trim().lowercase()
        val matches = languageTables.filter {
            val locale = LocaleCode.find(it.language)
            query.isEmpty() || listOf(it.language.replace('_', ' '), locale?.nativeName.orEmpty(), locale?.languageTag.orEmpty())
                .any { name -> name.lowercase().contains(query) }
        }
        val columns = (optionsPopup.tabs.prefWidth / OptionsPopupStyle.points(250f)).toInt().coerceIn(1, 3)
        val width = (optionsPopup.tabs.prefWidth - OptionsPopupStyle.points(16f) * (columns - 1)) / columns
        languageGrid.clear()
        if (matches.isEmpty()) languageGrid.add(noResults).minWidth(0f).growX().pad(OptionsPopupStyle.points(16f)).row()
        else matches.forEachIndexed { index, language ->
            languageGrid.add(language).width(width).minHeight(OptionsPopupStyle.points(72f)).fillX()
                .padRight(if ((index + 1) % columns == 0) 0f else OptionsPopupStyle.points(16f)).padBottom(OptionsPopupStyle.points(12f))
            if ((index + 1) % columns == 0) languageGrid.row()
        }
        languageGrid.invalidateHierarchy()
    }
}
