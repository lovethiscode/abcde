package com.unciv.ui.screens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.tr
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.scrollTo
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.LanguageTable
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageKeyShortcuts
import com.unciv.ui.components.widgets.LanguageTable.Companion.addLanguageTables
import com.unciv.ui.popups.options.OptionsPopup
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.overviewscreen.OverviewUi
import com.unciv.ui.screens.pickerscreens.PickerScreen

/** A [PickerScreen] to select a language, used once on the initial run after a fresh install.
 *  After that, [OptionsPopup] provides the functionality.
 *  Reusable code is in [LanguageTable] and [addLanguageTables].
 */
class LanguagePickerScreen : PickerScreen() {
    private var chosenLanguage: String

    private val languageTables: ArrayList<LanguageTable>

    fun update() {
        languageTables.forEach { it.update(chosenLanguage) }
    }

    init {
        chosenLanguage = LocaleCode.getSystemLanguage()

        closeButton.isVisible = false
        pickerPane.setNavigationContent(
            "Pick language".toLabel(TextRole.PageTitle),
            includeBackButton = false
        )
        bottomTable.background = OverviewUi.surface(OverviewUi.panel)

        val contentWidth = OverviewUi.pt(620f)
        val languageList = Table().apply {
            background = OverviewUi.rounded(OverviewUi.panel, 12f)
            pad(OverviewUi.pt(8f))
        }
        languageTables = languageList.addLanguageTables(contentWidth - OverviewUi.pt(16f))
        languageTables.forEach {
            it.useTaskStyle = true
            it.update(chosenLanguage)
        }
        topTable.add(languageList).growX().minWidth(0f).maxWidth(contentWidth).pad(OverviewUi.pt(16f))

        for (languageTable in languageTables) {
            languageTable.onClick {
                onChoice(languageTable.language)
            }
        }

        topTable.addLanguageKeyShortcuts(languageTables, { chosenLanguage }) { language ->
            onChoice(language)
            val selectedTable = languageTables.firstOrNull { it.language == language }
                ?: return@addLanguageKeyShortcuts
            scrollPane.scrollTo(selectedTable, true)
        }

        rightSideButton.setText("Pick language".tr())
        rightSideButton.name = "ui-language-confirm"
        rightSideButton.labelCell.minWidth(OverviewUi.pt(132f))
        rightSideButton.onActivation {
            pickLanguage()
        }
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        if (chosenLanguage.isNotEmpty()) onChoice()
    }

    private fun onChoice(choice: String) {
        chosenLanguage = choice
        onChoice()
    }
    private fun onChoice() {
        rightSideButton.enable()
        update()
    }

    private fun pickLanguage() {
        game.settings.language = chosenLanguage
        game.settings.updateLocaleFromLanguage()
        game.settings.isFreshlyCreated = false     // mark so the picker isn't called next launch
        game.settings.save()

        game.translations.tryReadTranslationForCurrentLanguage()
        game.replaceCurrentScreen{ MainMenuScreen() }
    }
}
