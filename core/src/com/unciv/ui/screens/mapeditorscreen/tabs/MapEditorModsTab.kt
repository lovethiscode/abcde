package com.unciv.ui.screens.mapeditorscreen.tabs

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.Constants
import com.unciv.logic.map.MapParameters
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.TranslatedSelectBox
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onChange
import com.unciv.ui.components.input.onClick
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mapeditorscreen.MapEditorScreen
import com.unciv.logic.map.tile.TileNormalizer
import com.unciv.ui.screens.newgamescreen.ModCheckboxTable

class MapEditorModsTab(
    private val editorScreen: MapEditorScreen
): Table(BaseScreen.skin), TabbedPager.IPageExtensions {
    // Read through a getter: pasting parameters replaces the mod set instance in newMapParameters
    private val mods get() = editorScreen.newMapParameters.mods
    private var modsTable: ModCheckboxTable
    private val modsTableCell: Cell<ModCheckboxTable>
    private val applyButton = "Change map ruleset".toTextButton()
    private val revertButton = "Revert to map ruleset".toTextButton()
    private val baseRulesetSelectBox: TranslatedSelectBox

    init {
        val rulesetName = editorScreen.newMapParameters.baseRuleset
        // Out dirty flag `modsTabNeedsRefresh` will be true on first activation,
        // so this will be replaced and can now be minimal
        modsTable = ModCheckboxTable(linkedSetOf(), rulesetName, editorScreen, false) {}

        val baseRulesets = RulesetCache.getSortedBaseRulesets()
        baseRulesetSelectBox = TranslatedSelectBox(baseRulesets, rulesetName)
        baseRulesetSelectBox.onChange {
            val newBaseRuleset = baseRulesetSelectBox.selected.value
            editorScreen.newMapParameters.baseRuleset = newBaseRuleset
            modsTable.setBaseRuleset(newBaseRuleset)
            modsTable.disableAllCheckboxes()
            enableApplyButton()
        }

        top()
        pad(5f)

        add(Table().apply {
            add("{Base Ruleset}:".toLabel(TextRole.Body))
            add(baseRulesetSelectBox).fillX()
        }).fillX().padBottom(10f).row()

        add(Table().apply {
            add(applyButton).padRight(10f)
            add(revertButton)
        }).fillX().pad(10f).row()

        modsTableCell = add(modsTable)
        row()

        applyButton.onClick(this::applyControls)
        applyButton.addTooltip("Change the map to use the ruleset selected on this page", 21f, targetAlign = Align.bottom)

        revertButton.onClick(this::revertControls)
        revertButton.addTooltip("Reset the controls to reflect the current map ruleset", 21f, targetAlign = Align.bottom)
    }

    private fun enableApplyButton() {
        val currentParameters = editorScreen.tileMap.mapParameters
        val enabled =
            currentParameters.mods != mods ||
            currentParameters.baseRuleset != baseRulesetSelectBox.selected.value
        applyButton.isEnabled = enabled
        revertButton.isEnabled = enabled
    }

    private fun revertControls() = updateControlsFrom(editorScreen.tileMap.mapParameters)

    /** Rebuild the controls to reflect [MapEditorScreen.newMapParameters] - e.g. after parameters were pasted */
    fun updateControls() = updateControlsFrom(editorScreen.newMapParameters)

    private fun updateControlsFrom(parameters: MapParameters) {
        // baseRulesetSelectBox.onChange clears the mod set, so keep a copy until the table is rebuilt
        val savedMods = LinkedHashSet(parameters.mods)
        baseRulesetSelectBox.setSelected(parameters.baseRuleset)
        mods.clear()
        mods.addAll(savedMods)  // clone "into" editorScreen.newMapParameters.mods
        modsTable = ModCheckboxTable(mods, parameters.baseRuleset, editorScreen, false) {
            enableApplyButton()
        }
        modsTableCell.setActor(modsTable)
        enableApplyButton()
    }

    private fun applyControls() {
        val newRuleset = RulesetCache.getComplexRuleset(mods, editorScreen.newMapParameters.baseRuleset)
        val incompatibilities = getIncompatibilities(newRuleset)
        if (incompatibilities.isEmpty()) {
            editorScreen.applyRuleset(newRuleset, editorScreen.newMapParameters.baseRuleset, mods)
            enableApplyButton()
        } else {
            AskFitMapToRulesetPopup(editorScreen, incompatibilities) {
                fitMapToRuleset(newRuleset)
                editorScreen.applyRuleset(newRuleset, editorScreen.newMapParameters.baseRuleset, mods)
                enableApplyButton()
            }
        }
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        enableApplyButton()
        if (!editorScreen.modsTabNeedsRefresh) return
        editorScreen.modsTabNeedsRefresh = false
        revertControls()
    }

    private fun getIncompatibilities(newRuleset: Ruleset): List<String> {
        val incompatibilities = HashSet<String>()
        for (tile in editorScreen.tileMap.values) {
            incompatibilities += tile.getRulesetIncompatibility(newRuleset)
        }
        incompatibilities.remove("")
        return incompatibilities.sorted()
    }

    private class AskFitMapToRulesetPopup(
        editorScreen: BaseScreen,
        incompatibilities: List<String>,
        onOK: () -> Unit
    ): Popup(editorScreen) {
        init {
            if (UiNavigation.isIos) addDialogTitle("Change map to fit selected ruleset?", "Map editor")
            val incompatibilityTable = Table().apply {
                for (inc in incompatibilities)
                    add(inc.toLabel(TextRole.Body, unit = popupUnit).apply { wrap = true })
                        .growX().minWidth(0f).padBottom(8f * popupUnit).row()
            }
            if (UiNavigation.isIos) {
                add("The following map objects are not available in the selected ruleset."
                    .toLabel(TextRole.Body, unit = popupUnit).apply {
                        wrap = true
                        setAlignment(Align.left)
                    }).width(popupContentWidth).growX().minWidth(0f).row()
                add("Incompatible map objects".toLabel(TextRole.SectionTitle, unit = popupUnit).apply {
                    setAlignment(Align.left)
                }).width(popupContentWidth).growX().minWidth(0f).padTop(16f * popupUnit).row()
                add(AutoScrollPane(incompatibilityTable).apply {
                    setScrollingDisabled(true, false)
                    setOverscroll(false, false)
                    style = com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle(style).apply {
                        vScroll = null
                        vScrollKnob = null
                    }
                }).width(popupContentWidth).maxHeight(stageToShowOn.height * 0.42f).row()
            } else {
                add(AutoScrollPane(incompatibilityTable)).colspan(2)
                    .maxHeight(stageToShowOn.height * 0.8f).row()
                addGoodSizedLabel("Change map to fit selected ruleset?", UiTypography.size(TextRole.PageTitle, legacySize = 24))
                    .colspan(2).row()
            }
            if (UiNavigation.isIos) {
                addCloseButton(Constants.cancel, com.unciv.ui.components.input.KeyCharAndCode('n'))
                addOKButton("Change map", com.unciv.ui.components.input.KeyCharAndCode('y'), action = onOK)
            } else {
                addButton(Constants.yes, 'y') {
                    onOK()
                    close()
                }
                addButton(Constants.no, 'n') { close() }
            }
            equalizeLastTwoButtonWidths()
            open(true)
        }
    }

    private fun fitMapToRuleset(newRuleset: Ruleset) {
        for (tile in editorScreen.tileMap.values)
            TileNormalizer.normalizeToRuleset(tile, newRuleset)
    }
}
