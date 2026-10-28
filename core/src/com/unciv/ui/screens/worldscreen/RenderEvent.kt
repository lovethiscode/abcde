package com.unciv.ui.screens.worldscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.EventChoice
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.UncivTooltip.Companion.addTooltip
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.MarkupRenderer

/** Renders an [Event] for [AlertPopup] or a floating tutorial task on [WorldScreen] */
class RenderEvent(
    event: Event,
    val worldScreen: WorldScreen,
    val unit: MapUnit? = null,
    /** Width the texts wrap to - an upper limit rather than a size: [WrappableLabel.optimizePrefWidth]
     *  can bring a label below it, and a child that cannot wrap at all makes the result wider.
     *
     *  It is fixed at construction because it is baked into the children there, and their preferred
     *  width is settled from that moment on: [WrappableLabel.getPrefWidth] is the minimum of the
     *  measured width, the `expectedWidth` handed to the constructor, and the value
     *  [WrappableLabel.optimizePrefWidth] computes once - from that same `expectedWidth`, and
     *  clamped to it. Laying an existing instance out again therefore cannot widen or narrow it -
     *  the Table would follow, it recomputes its columns from zero on every run, but the labels
     *  keep reporting the width they were built for. To change this width, build a new one. */
    val labelWidth: Float = worldScreen.stage.width * 0.5f,
    val floatingTutorial: Boolean = false,
    /** Use the short landscape teaching-card composition: title and instructions beside the image. */
    val compactFloatingTutorial: Boolean = false,
    /** Ordinary mobile modal: the Popup owns the fixed title and viewport. */
    val ordinaryDialog: Boolean = false,
    val onChoice: (EventChoice) -> Unit
) : Table() {
    private val gameInfo get() = worldScreen.gameInfo

    val isValid: Boolean

    //todo check generated translations

    init {
        // labelWidth is the available viewport, not a minimum content width plus cell insets.
        defaults().fillX().center().pad(if (floatingTutorial || ordinaryDialog) 0f else 5f)

        val gameContext = GameContext(gameInfo.currentPlayerCiv, unit = unit)
        val choices = event.getMatchingChoices(gameContext)
        isValid = choices != null
        if (isValid) {
            if (event.text.isNotEmpty() && !floatingTutorial && !ordinaryDialog) {
                add(WrappableLabel(event.text, labelWidth).apply {
                    wrap = true
                    setAlignment(Align.center)
                    optimizePrefWidth()
                }).row()
            }
            if (event.civilopediaText.isNotEmpty()) {
                val titleIndex = if (floatingTutorial && event.text.isBlank())
                    event.civilopediaText.indexOfFirst { it.text.isNotBlank() } else -1
                val bodyLines = event.civilopediaText.filterIndexed { index, _ -> index != titleIndex }
                if (compactFloatingTutorial && event.name.startsWith("Tutorial Task: [")) {
                    addCompactTutorial(event, titleIndex, bodyLines)
                } else {
                    // Built-in task instructions lead; the original illustration remains below them.
                    val lines = if (floatingTutorial && event.name.startsWith("Tutorial Task: ["))
                        bodyLines.sortedBy { it.extraImage.isNotBlank() } else bodyLines
                    add(if (floatingTutorial || ordinaryDialog) MarkupRenderer.render(lines, labelWidth,
                        padding = 0f, fitToWidth = true, useUiTypography = true,
                        unit = UiTypography.unitsPerPoint(worldScreen.stage), linkAction = ::openCivilopedia)
                        else event.renderCivilopediaText(labelWidth, ::openCivilopedia)).row()
                }
            }

            for (choice in choices!!) addChoice(choice)
        }
    }

    private fun addCompactTutorial(event: Event, titleIndex: Int, bodyLines: List<FormattedLine>) {
        val unit = UiTypography.unitsPerPoint(worldScreen.stage)
        val illustration = bodyLines.firstOrNull { it.extraImage.isNotBlank() }
        val textLines = bodyLines.filter { it.extraImage.isBlank() }
        val illustrationWidth = if (illustration == null) 0f else minOf(72f * unit, labelWidth * 0.28f)
        val gap = if (illustration == null) 0f else 10f * unit
        val copyWidth = (labelWidth - illustrationWidth - gap).coerceAtLeast(1f)
        val title = when {
            event.text.isNotBlank() -> event.text
            titleIndex >= 0 -> event.civilopediaText[titleIndex].text
            else -> "Tutorial"
        }
        val copy = Table().apply {
            defaults().left()
            add(title.toLabel(role = TextRole.SectionTitle).apply {
                wrap = true
                setAlignment(Align.left)
            }).width(copyWidth).row()
            if (textLines.isNotEmpty()) add(MarkupRenderer.render(textLines, copyWidth,
                padding = 0f, fitToWidth = true, useUiTypography = true, unit = unit,
                linkAction = ::openCivilopedia)).width(copyWidth).padTop(4f * unit).row()
        }
        val row = Table().apply {
            add(copy).width(copyWidth).growY().left()
            if (illustration != null) {
                add(MarkupRenderer.render(listOf(illustration), illustrationWidth,
                    padding = 0f, fitToWidth = true, useUiTypography = true, unit = unit,
                    linkAction = ::openCivilopedia))
                    .width(illustrationWidth).padLeft(gap).center()
            }
        }
        add(row).width(labelWidth).row()
    }

    private fun addChoice(choice: EventChoice) {
        if (!ordinaryDialog) addSeparator()

        val button = choice.text.toTextButton()
        button.onActivation {
            onChoice(choice)
            choice.triggerChoice(gameInfo.currentPlayerCiv, unit)
        }
        val key = KeyCharAndCode.parse(choice.keyShortcut)
        if (key != KeyCharAndCode.UNKNOWN) {
            button.keyShortcuts.add(key)
            button.addTooltip(key)
        }
        add(button).apply {
            if (floatingTutorial || ordinaryDialog) {
                val unit = UiTypography.unitsPerPoint(worldScreen.stage)
                button.label.setFontSize(UiTypography.size(TextRole.Control, unit))
                button.label.wrap = true
                if (ordinaryDialog && com.unciv.models.skins.ExpeditionBlueTheme.enabled)
                    button.style = com.unciv.ui.popups.OrdinaryPopupStyle(unit).button()
                width(labelWidth).minHeight((if (com.unciv.ui.components.widgets.UiNavigation.isAndroid) 48f else 44f) * unit)
                if (ordinaryDialog) padTop(8f * unit).padBottom(4f * unit)
            }
        }.row()

        val lines = (
            choice.civilopediaText.asSequence()
                + choice.uniqueObjects.filter { it.isTriggerable || it.type == UniqueType.Comment }
                    .filterNot { it.isHiddenToUsers() }
                    .map { FormattedLine(it) }
            ).asIterable()
        add(MarkupRenderer.render(lines, labelWidth, padding = if (floatingTutorial || ordinaryDialog) 0f else 2.5f, fitToWidth = floatingTutorial || ordinaryDialog,
            useUiTypography = floatingTutorial || ordinaryDialog, unit = UiTypography.unitsPerPoint(worldScreen.stage),
            linkAction = ::openCivilopedia)).row()
    }

    private fun openCivilopedia(link: String) = worldScreen.openCivilopedia(link)
}
