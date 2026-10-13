package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.getAscendant
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageKeyShortcuts
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageTables
import com.unciv.ui.components.widgets.TabbedPager

internal class LanguageTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    private val languageTables by lazy {
        if (!optionsPopup.useSidebar) this.addLanguageTables(optionsPopup.tabs.prefWidth * 0.9f - 10f)
        else {
            val source = Table()
            val tables = source.addLanguageTables(optionsPopup.tabs.prefWidth - 48f)
            add("Current language".toLabel(ExpeditionBlueTheme.muted, 16)).left().pad(8f).row()
            add((com.unciv.models.metadata.LocaleCode.find(settings.language)?.nativeName ?: settings.language)
                .toLabel(ExpeditionBlueTheme.gold, 24)).left().pad(8f).row()
            val grid = Table()
            val columns = if (optionsPopup.tabs.prefWidth >= 550f) 2 else 1
            for ((index, language) in tables.withIndex()) {
                language.pad(8f)
                language.defaults().pad(6f)
                for (label in language.children.filterIsInstance<Label>()) {
                    label.wrap = true
                    language.getCell(label).minWidth(0f).growX()
                }
                grid.add(language).width((optionsPopup.tabs.prefWidth - 44f) / columns)
                    .minHeight(68f).fillX().pad(4f)
                if ((index + 1) % columns == 0) grid.row()
            }
            add(grid).growX().row()
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
            chosenLanguage = it
            val pager = this.getAscendant<TabbedPager>()
                ?: return@addLanguageKeyShortcuts
            activated(pager.activePage, "", pager)
        }
        super.lateInitialize()
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
                val surface = skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.panel)
                it.background = if (it.language == chosenLanguage) ExpeditionBlueTheme.outlinedControl(surface,
                    skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.gold)) else surface
            }
        }
        if (chosenLanguage != settings.language)
            selectLanguage()
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        super.activated(index, caption, pager)
        updateSelection()
        val selectedTable = languageTables.firstOrNull { it.language == chosenLanguage }
            ?: return
        pager.pageScrollTo(selectedTable, true)
    }
}
