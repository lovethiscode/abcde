package com.unciv.ui.screens.endless

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.logic.challenges.ChallengeTelemetry
import com.unciv.logic.endless.EndlessCatalog
import com.unciv.logic.endless.EndlessState
import com.unciv.logic.endless.EndlessStore
import com.unciv.models.skins.ExpeditionBlueTheme as Theme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.popups.LoadingPopup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.RecreateOnResize
import com.unciv.utils.Concurrency

/** A new defense's draft stays on this screen; saved defenses are display-only. */
class EndlessConfigScreen(
    private val replacingAttempt: String? = null,
    private val savedState: EndlessState? = null,
    private var pace: String = "casual"
) : BaseScreen(), RecreateOnResize {
    private val layoutUnit = if (UiNavigation.isAndroid) UiTypography.unitsPerPoint(stage) else 1f
    private val layoutWidth get() = stage.width / layoutUnit
    private val layoutHeight get() = stage.height / layoutUnit
    private val touch get() = if (UiNavigation.isAndroid) 48f else UiNavigation.minimumTouchSize()
    private fun screenFont(role: TextRole, legacySize: Int = role.legacySize) =
        UiTypography.size(role, if (UiNavigation.isAndroid) 1f else UiTypography.unitsPerPoint(stage), legacySize)
    private val root = Table()

    init {
        if (UiNavigation.isAndroid) globalShortcuts.add(com.unciv.ui.components.input.KeyCharAndCode.BACK) { game.popScreen() }
        root.setFillParent(!UiNavigation.isAndroid)
        if (UiNavigation.isAndroid) {
            root.setTransform(true)
            root.setSize(layoutWidth, layoutHeight)
            root.setScale(layoutUnit)
        }
        root.background = ImageGetter.getWhiteDotDrawable().tint(Theme.background)
        stage.addActor(root)
        build()
    }

    private fun build() {
        root.clearChildren()
        val width = (layoutWidth - 44f).coerceAtMost(680f)
        val inner = width - 36f
        root.add(Table().apply {
            add(UiNavigation.backButton(unit = if (UiNavigation.isAndroid) 1f else touch / 44f).apply { onActivation { game.popScreen() } }).minHeight(touch)
            add().expandX()
        }).growX().pad(12f, 18f, 10f, 18f).row()
        val content = Table().apply {
            background = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Theme.panel)
            pad(18f); top(); defaults().left().spaceBottom(12f)
            fun text(value: String, muted: Boolean = false, size: TextRole = TextRole.Body) {
                add(value.toLabel(if (muted) Theme.muted else Theme.text, screenFont(size)).apply { wrap = true }).width(inner).row()
            }
            fun action(value: String, id: String, primary: Boolean = false, run: () -> Unit) {
                add(value.toTextButton().apply {
                    name = id; label.setFontSize(screenFont(TextRole.Control, legacySize = 20))
                    if (primary) style = TextButton.TextButtonStyle(style).apply {
                        up = ImageGetter.getWhiteDotDrawable().tint(Theme.gold)
                        down = ImageGetter.getWhiteDotDrawable().tint(Theme.muted)
                        fontColor = Theme.primaryInk
                    }
                    onActivation { run() }
                }).width(inner).minHeight(maxOf(48f, touch)).row()
            }
            text(if (savedState == null) "New defense" else "Current defense", size = TextRole.PageTitle)
            text("Settings cannot be changed after the defense starts.", muted = true)
            text("Defense pace", size = TextRole.SectionTitle)
            val selected = savedState?.stageState?.pace ?: pace
            if (savedState != null) {
                text(if (savedState.stageState == null) "Legacy rules" else if (selected == "wild") "Wild" else "Casual")
            } else {
                for (option in listOf("casual", "wild")) {
                    val title = (if (option == "wild") "Wild" else "Casual").tr() + if (option == pace) " ✓" else ""
                    action(title, "ui-endless-config-pace:$option", option == pace) { pace = option; build() }
                }
            }
            if (savedState == null || savedState.stageState != null) {
                text(if (selected == "wild") "Wild: no rest; Gold or reinforcement." else "Casual: 3 full rest rounds; Gold, repairs or reinforcement.", muted = true)
            }
            if (savedState == null) action("Start new defense", "ui-endless-config-start", true) {
                if (replacingAttempt == null) start()
                else ConfirmPopup(this@EndlessConfigScreen, "Replace the current defense?", "Start new defense") { start() }.open()
            }
        }
        val contentMaxHeight = layoutHeight - root.prefHeight - 14f
        root.add(AutoScrollPane(content).apply { name = "ui-endless-config-scroll"; setScrollingDisabled(true, false) })
            .width(width).maxHeight(contentMaxHeight).fillY().minHeight(0f).top().pad(0f, 22f, 14f, 22f)
        root.top()
    }

    private fun start() {
        if (savedState != null) return
        val loading = LoadingPopup(this)
        Concurrency.runOnGLThread {
            try {
                game.files.autosaves.autoSaveJob?.join()
                val attempt = EndlessCatalog.newAttempt(kotlin.random.Random.nextLong(), pace)
                EndlessStore(game.files).start(attempt, replacingAttempt)
                game.loadGame(attempt)
                ChallengeTelemetry.report("start", attempt)
            } catch (ex: Exception) {
                if (ex !is kotlinx.coroutines.CancellationException) ChallengeTelemetry.error("endless", "start_failed")
                com.unciv.utils.Log.error("Cannot start endless defense", ex)
                ToastPopup(ex.message ?: "Could not start defense", this@EndlessConfigScreen)
            } finally { loading.close() }
        }
    }

    override fun recreate(): BaseScreen = EndlessConfigScreen(replacingAttempt, savedState, pace)
}
