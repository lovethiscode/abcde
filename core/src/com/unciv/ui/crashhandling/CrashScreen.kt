package com.unciv.ui.crashhandling

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.RulesetCache
import com.unciv.ui.components.extensions.addBorder
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.popups.Popup
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.utils.Log
import java.io.PrintWriter
import java.io.StringWriter

/** Screen to crash to when an otherwise unhandled exception or error is thrown. */
//todo We may be in a critical low-memory situation. Using a lot ot String concatenation and trimIndent
//     could make the display fail when a more efficient StringBuilder approach might still succeed.
class CrashScreen(val exception: Throwable) : BaseScreen() {

    private companion object {
        fun Throwable.stringify(): String {
            val out = StringWriter()
            this.printStackTrace(PrintWriter(out))
            return out.toString()
        }
    }

    /** Qualified class name of the game screen that was active at the construction of this instance, or an error note. */
    private val lastScreenType = try {
        UncivGame.Current.screen!!::class.qualifiedName.toString()
    } catch (e: Throwable) {
        "Could not get screen type: $e"
    }

    /** Text shown on screen. Deliberately excludes the save game/mods data (see [fullText]) - rendering
     *  that as part of a Label can itself OOM when we're already low on memory. */
    val displayedText = formatReport(exception.stringify())

    /** Full report including save game/mods data, only ever used for the Copy button and issue tracker. */
    private val fullText by lazy { displayedText + tryGetSaveMods() + tryGetSaveGame() }

    var copied = false
        private set

    /** @return The last active save game serialized as a compressed string if any, or an informational note otherwise. */
    private fun tryGetSaveGame(): String {
        val gameInfo = UncivGame.getGameInfoOrNull() ?: return ""
        return "\n**Save Data:**\n<details><summary>Show Saved Game</summary>\n\n```\n" +
            try {
                UncivFiles.gameInfoToString(gameInfo, forceZip = true)
            } catch (e: Throwable) {
                "No save data: $e" // In theory .toString() could still error here.
            } + "\n```\n</details>\n"
    }

    /** @return Mods from the last active save game if any, or an informational note otherwise. */
    private fun tryGetSaveMods(): String {
        val sb = StringBuilder(160)  // capacity: Just some guess
        val gameInfo = UncivGame.getGameInfoOrNull()
        if (gameInfo != null) {
            sb.append("\n**Save Mods:**\n```\n")
            try { // Also from old CrashController().buildReport(), also could still error at .toString().
                sb.append(gameInfo.gameParameters.getModsAndBaseRuleset().toString())
            } catch (e: Throwable) {
                sb.append("No mod data: $e")
            }
            sb.append("\n```\n")
        }
        val visualMods = UncivGame.Current.settings.visualMods
        if (visualMods.isEmpty())
            return sb.toString()
        sb.append("**Permanent audiovisual Mods**:\n```\n")
        sb.append(visualMods.toString())
        sb.append("\n```\n")
        return sb.toString()
    }


    /**
     * @param message Error text. Probably exception traceback.
     * @return Message with application, platform, and game state metadata.
     * */
    private fun formatReport(message: String): String {
        val indent = " ".repeat(4)
        val baseIndent = indent.repeat(3) // To be even with the template string.
        val subIndent = baseIndent + indent // TO be one level more than the template string.
        /** We only need the indent after any new lines in each substitution itself. So this prepends to all lines, and then removes from the start. */
        fun String.prependIndentToOnlyNewLines(indent: String) = this.prependIndent(indent).removePrefix(indent)
        val version = UncivGame.Current.displayBuildNumber
            ?.let { UncivGame.VERSION.toNiceString(it) }
            ?: UncivGame.VERSION.toNiceString()
        /// The $lastScreenType substitution is the only one completely under the control of this class— Everything else can, in theory, have new lines in it due to containing strings or custom .toString behaviour with new lines (which… I think Table.toString or something actually does). So normalize indentation for basically everything.
        return """
            **Platform:** ${Gdx.app.type.toString().prependIndentToOnlyNewLines(subIndent)}
            **Version:** ${version.prependIndentToOnlyNewLines(subIndent)}
            **Source:** ${UncivGame.Current.sourceCodeUrl.prependIndentToOnlyNewLines(subIndent)}
            **Rulesets:** ${RulesetCache.keys.toString().prependIndentToOnlyNewLines(subIndent)}
            **Last Screen:** `$lastScreenType`
            **A-star pathing enabled:** ${UncivGame.Current.settings.useAStarPathfinding}

            --------------------------------

            ${Log.getSystemInfo().prependIndentToOnlyNewLines(baseIndent)}

            --------------------------------


            **Message:**
            ```
            ${message.prependIndentToOnlyNewLines(baseIndent)}
            ```
            """.trimIndent()
    }

    init {
        System.gc() // If we previously died to an OOM error, this decreases the chances that the *crash screen* will OOM as well
        if (UiNavigation.isIos && UncivGame.getGameInfoOrNull()?.let { it.campaign == null && it.endless == null } != false)
            showOrdinaryError()
        else stage.addActor(makeLayoutTable())
    }

    private fun showOrdinaryError() {
        object : Popup(this@CrashScreen, ordinaryShell = true) {
            init {
                addDialogTitle("The game cannot continue", "Unrecoverable error", dismissible = false)
                addGoodSizedLabel("You can copy the error report, then close and reopen the game.").row()
                fun details(title: String, text: String) {
                    val expander = ExpanderTab(title, fontSize = UiTypography.size(TextRole.Control, popupUnit),
                        startsOutOpened = false, defaultPad = 0f, headerPad = 12f * popupUnit,
                        expanderWidth = 1f,
                        onChange = {
                            refreshOrdinaryPopupLayout()
                            getScrollPane()?.apply {
                                validate()
                                scrollX = 0f
                                scrollY = 0f
                                updateVisualScroll()
                            }
                        }) { content ->
                        content.add(text.toLabel(TextRole.Body, unit = popupUnit).apply { wrap = true })
                            .width(object : com.badlogic.gdx.scenes.scene2d.ui.Value() {
                                override fun get(context: Actor?) = (popupContentWidth - 24f * popupUnit).coerceAtLeast(1f)
                            }).minWidth(0f).growX()
                    }
                    // ExpanderTab caches its initial content width; permit later rotation to shrink it.
                    expander.getCell(expander.header).minWidth(0f)
                    add(expander).minWidth(0f).growX().row()
                }
                details("View error details", displayedText)
                details("Try to recover", "Reopen the game and try loading your latest save. If you use mods, try disabling them.")
                addButton("Copy error report") { copyErrorReport() }
                addButton("Open Issue Tracker") { openIssueTracker() }
                // Do not reuse the Enter key that may have triggered this fatal error.
                addOKButton("Close Unciv") { Gdx.app.exit() }.actor.keyShortcuts.clear()
                open(true)
            }
        }
    }

    private fun copyErrorReport() {
        try {
            Gdx.app.clipboard.contents = fullText
            copied = true
            ToastPopup("Error report copied.", this)
        } catch (ex: Exception) {
            Log.debug("Could not copy to clipboard", ex)
            ToastPopup("Could not copy to clipboard!", this)
        }
    }

    private fun openIssueTracker() {
        if (copied) Gdx.net.openURI("${Constants.uncivRepoURL}issues")
        else ToastPopup("Please copy the error report first.", this)
    }

    /** @return A Table containing the layout of the whole screen. */
    private fun makeLayoutTable(): Table {
        val layoutTable = Table().also {
            it.width = stage.width
            it.height = stage.height
        }
        layoutTable.add(makeTitleLabel())
            .padBottom(15f)
            .width(stage.width)
            .row()
        layoutTable.add(makeErrorScroll())
            .maxWidth(stage.width * 0.7f)
            .maxHeight(stage.height * 0.5f)
            .minHeight(stage.height * 0.2f)
            .row()
        layoutTable.add(makeInstructionLabel())
            .padTop(15f)
            .width(stage.width)
            .row()
        layoutTable.add(makeActionButtonsTable())
            .padTop(10f)
        return layoutTable
    }

    /** @return Label for title at top of screen. */
    private fun makeTitleLabel() =
        "An unrecoverable error has occurred in Unciv:".toLabel(fontSize = UiTypography.size(TextRole.PageTitle))
            .apply {
                wrap = true
                setAlignment(Align.center)
            }

    /** @return Actor that displays a scrollable view of the error report text. */
    private fun makeErrorScroll(): Actor {
        val errorLabel = Label(displayedText, skin).apply {
            setFontSize(UiTypography.size(TextRole.Body, legacySize = 15))
        }
        val errorTable = Table()
        errorTable.add(errorLabel)
            .pad(10f)
        return AutoScrollPane(errorTable)
            .addBorder(4f, Color.DARK_GRAY)
    }

    /** @return Label to give the user more information and context below the error report. */
    private fun makeInstructionLabel() =
        "{If this keeps happening, you can try disabling mods.}\n{You can also report this on the issue tracker.}".toLabel()
            .apply {
                wrap = true
                setAlignment(Align.center)
            }

    /** @return Table that displays decision buttons for the bottom of the screen. */
    private fun makeActionButtonsTable(): Table {
        val copyButton = IconTextButton("Copy", fontSize = Constants.headingFontSize, labelFontSize = UiTypography.size(TextRole.Control))
            .onClick {
                copyErrorReport()
            }
        val reportButton = IconTextButton("Open Issue Tracker", ImageGetter.getImage("OtherIcons/Link"),
            Constants.headingFontSize
        , labelFontSize = UiTypography.size(TextRole.Control))
            .onClick {
                openIssueTracker()
            }
        val closeButton = IconTextButton("Close Unciv", fontSize = Constants.headingFontSize, labelFontSize = UiTypography.size(TextRole.Control))
            .onClick { Gdx.app.exit() }

        val buttonsTable = Table()
        buttonsTable.add(copyButton)
            .pad(10f)
        buttonsTable.add(reportButton)
            .pad(10f)
            .also {
                if (isCrampedPortrait()) {
                    it.row()
                    buttonsTable.add()
                }
            }
        buttonsTable.add(closeButton)
            .pad(10f)

        return buttonsTable
    }
}
