package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/**
 * The Options (Settings) Popup
 * @param screen The caller - note if this is a [WorldScreen] or [MainMenuScreen] they will be rebuilt when major options change.
 */
//region Fields
class OptionsPopup(
    screen: BaseScreen,
    private val selectPage: OptionsPopupPages = defaultPage,
    withDebug: Boolean = false,
    private val onClose: () -> Unit = {},
    private val subSelect: String? = null
) : Popup(screen.stage, /** [TabbedPager] handles scrolling */ scrollable = Scrollability.None), OptionsPopupHelpers {

    val game = screen.game
    val settings = screen.game.settings
    val tabs: TabbedPager
    internal val useSidebar = ExpeditionBlueTheme.enabled
    private val navigation = Table()
    private val navigationButtons = ArrayList<IconTextButton>()
    private val pageTitle = "".toLabel(ExpeditionBlueTheme.gold, 26).apply { wrap = true }
    private val pageDescription = "".toLabel(ExpeditionBlueTheme.muted, 15).apply { wrap = true }
    private var frameHeight = 0f
    private var sidebarWidth = 0f
    // Pager indices follow the visible tabs, not the full enum order.
    private val visiblePages = OptionsPopupPages.entries.filter { it.visible(withDebug) }
    private val pageIndex = HashMap<OptionsPopupPages, OptionsPopupTab>()
    override val activePage get() = visiblePages[tabs.activePage]
    override val rightWidgetMinWidth: Float
        get() = if (useSidebar) (tabs.prefWidth * 0.42f).coerceIn(130f, 240f) else if (stageToShowOn.width < 600f) 200f else 240f
    internal val tabMinWidth: Float

    //endregion

    companion object {
        val defaultPage = OptionsPopupPages.Gameplay
    }

    init {
        clickBehindToClose = true

        if (settings.addCompletedTutorialTask("Open the options table"))
            (screen as? WorldScreen)?.shouldUpdate = true

        innerTable.pad(0f)

        val tabMaxWidth: Float
        val tabMaxHeight: Float
        screen.run {
            sidebarWidth = if (stage.width < 700f) 132f else 174f
            tabMaxWidth = if (useSidebar) stage.width * 0.94f - sidebarWidth
                else if (isPortrait()) stage.width - 10f else 0.8f * stage.width
            tabMinWidth = if (useSidebar) tabMaxWidth else 0.6f * stage.width
            tabMaxHeight = if (useSidebar) stage.height * 0.9f - 120f else 0.8f * stage.height
        }
        // Since all pages now initialize their content late, on activation, we can't measure their preferred size anymore -> use tabMaxHeight for tabMinHeight
        // That's not really bad, the tabs are long enough so some will always need scrolling even on the largest UI size setting.
        tabs = TabbedPager(
            tabMinWidth, tabMaxWidth, tabMaxHeight, tabMaxHeight,
            headerFontSize = 21, backgroundColor = Color.CLEAR, capacity = OptionsPopupPages.entries.size,
            showHeader = !useSidebar
        )
        if (!useSidebar) add(tabs).pad(0f).grow().row()

        for (page in visiblePages) {
            val content = page.getContent(this)
            tabs.addPage(page.label, content, page.getIcon(settings.language), 24f)
            pageIndex[page] = content
        }

        if (useSidebar) buildSidebarLayout()
        else tabs.decorateHeader(getCloseButton { close() })

        pack() // Needed to show the background.
        center(screen.stage)
    }

    private fun buildSidebarLayout() {
        innerTable.background = ExpeditionBlueTheme.outlinedControl(
            BaseScreen.skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.background),
            BaseScreen.skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.border))
        tabs.background = null
        val header = Table().apply {
            pad(10f, 18f, 10f, 12f)
            add("Options".toLabel(ExpeditionBlueTheme.gold, 24)).left().growX()
            add("Unciv4iOS".toLabel(ExpeditionBlueTheme.muted, 14)).padRight(18f)
            add(getCloseButton { close() })
        }
        add(header).colspan(2).growX().row()

        navigation.top().pad(8f)
        navigation.background = BaseScreen.skin.newDrawable("Rectangle", ExpeditionBlueTheme.panel)
        for ((index, page) in visiblePages.withIndex()) {
            val button = IconTextButton(page.label, page.getIcon(settings.language), 18).apply {
                style = Button.ButtonStyle(style).apply {
                    up = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Color.CLEAR)
                    checked = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.raised)
                }
                label.wrap = true
                labelCell.minWidth(0f).growX().left()
                iconCell.size(22f).padRight(10f)
                onClick { tabs.selectPage(index); isChecked = true }
            }
            navigationButtons.add(button)
            navigation.add(button).width(sidebarWidth - 16f).minHeight(42f).padBottom(2f).row()
        }
        val sidebar = AutoScrollPane(navigation).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val content = Table().apply {
            add(pageTitle).minWidth(0f).left().pad(10f, 22f, 2f, 12f).growX().row()
            add(pageDescription).minWidth(0f).left().pad(0f, 22f, 4f, 12f).growX().row()
            add(tabs).grow()
        }
        add(sidebar).width(sidebarWidth).growY()
        add(content).grow().row()
        val footer = Table().apply {
            pad(8f, 18f, 10f, 12f)
            add("Settings are saved when you close this window.".toLabel(ExpeditionBlueTheme.muted, 14).apply { wrap = true })
                .minWidth(0f).left().growX()
            add("Done".toTextButton(style = BaseScreen.skin.get("positive", TextButton.TextButtonStyle::class.java)).onClick { close() }).minWidth(112f).minHeight(44f)
        }
        add(footer).colspan(2).growX().row()
        frameHeight = header.prefHeight + footer.prefHeight + pageTitle.prefHeight + pageDescription.prefHeight + 24f
        tabs.resizePageArea(tabs.prefWidth, (stageToShowOn.height * 0.9f - frameHeight).coerceAtLeast(80f))
        tabs.onSelection { index, caption, _ ->
            pageTitle.setText(caption.tr())
            pageDescription.setText(visiblePages[index].description.tr())
            navigationButtons.forEachIndexed { i, button ->
                button.isChecked = i == index
                button.label.color = if (i == index) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.text
            }
        }
    }

    override fun onVisibleAreaChanged(visibleArea: com.badlogic.gdx.math.Rectangle) {
        if (useSidebar) {
            tabs.resizePageArea(stageToShowOn.width * 0.94f - sidebarWidth,
                (visibleArea.height * 0.9f - frameHeight).coerceAtLeast(80f))
        } else if (com.badlogic.gdx.Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.iOS) {
            tabs.resizePageArea(
                if (stageToShowOn.height > stageToShowOn.width) stageToShowOn.width - 10f else stageToShowOn.width * 0.8f,
                visibleArea.height * 0.8f
            )
        }
        super.onVisibleAreaChanged(visibleArea)
    }

    override fun close() {
        game.musicController.onChange(null)
        center(stageToShowOn)
        tabs.selectPage(-1, false)
        settings.save()
        onClose() // activate the passed 'on close' callback
        super.close()
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (!visible) return
        if (tabs.activePage >= 0) return
        val targetPage = if (selectPage in pageIndex) selectPage else defaultPage
        pageIndex[targetPage]?.subSelect(subSelect)
        tabs.selectPage(visiblePages.indexOf(targetPage))
    }
}
