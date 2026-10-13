package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UiNavigation
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
    private val screen: BaseScreen,
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
    private val pageTitle = "".toLabel(ExpeditionBlueTheme.text, 26).apply { wrap = true }
    private val pageDescription = "".toLabel(ExpeditionBlueTheme.muted, 15).apply { wrap = true }
    private var frameHeight = 0f
    private lateinit var headerCell: Cell<Table>
    private lateinit var headingCell: Cell<Table>
    private lateinit var header: Table
    private lateinit var heading: Table
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
        clickBehindToClose = screen !is OptionsScreen

        if (settings.addCompletedTutorialTask("Open the options table"))
            ((screen as? OptionsScreen)?.sourceScreen as? WorldScreen ?: screen as? WorldScreen)?.shouldUpdate = true

        innerTable.pad(0f)

        val tabMaxWidth: Float
        val tabMaxHeight: Float
        screen.run {
            sidebarWidth = if (stage.width < 700f) 132f else 174f
            tabMaxWidth = if (useSidebar) stage.width - sidebarWidth - 1f
                else if (isPortrait()) stage.width - 10f else 0.8f * stage.width
            tabMinWidth = if (useSidebar) tabMaxWidth else 0.6f * stage.width
            tabMaxHeight = if (useSidebar) stage.height - 130f else 0.8f * stage.height
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
        innerTable.background = null
        tabs.background = null
        innerTable.defaults().pad(0f)
        header = Table().apply {
            pad(6f, 12f, 6f, 18f)
            add(UiNavigation.backButton().apply {
                style = com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle(style).apply {
                    up = BaseScreen.skin.newDrawable("Rectangle", Color.CLEAR)
                    down = BaseScreen.skin.newDrawable("Rectangle", OptionsPopupStyle.selectedNavigation)
                    over = down
                }
                onClick { close() }
            }).padRight(14f)
            add("Options".toLabel(ExpeditionBlueTheme.text, 24)).left().growX()
        }
        headerCell = add(header).colspan(3).growX()
        headerCell.row()

        navigation.top().pad(8f, 6f, 8f, 10f)
        for ((index, page) in visiblePages.withIndex()) {
            val button = IconTextButton(page.label, page.getIcon(settings.language), 18).apply {
                style = Button.ButtonStyle(style).apply {
                    up = BaseScreen.skin.newDrawable("RoundedEdgeRectangle", Color.CLEAR).apply { minHeight = 0f }
                    checked = OptionsPopupStyle.selectedNavigationDrawable()
                    down = checked
                    over = checked
                }
                label.wrap = true
                labelCell.minWidth(0f).growX().left()
                iconCell.size(22f).padLeft(10f).padRight(10f)
                onClick { tabs.selectPage(index); isChecked = true }
            }
            navigationButtons.add(button)
            navigation.add(button).width(sidebarWidth - 16f).minHeight(UiNavigation.minimumTouchSize()).padBottom(4f).row()
        }
        val sidebar = AutoScrollPane(navigation).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        heading = Table().apply {
            add(pageTitle).minWidth(0f).left().pad(10f, 22f, 2f, 12f).growX().row()
            add(pageDescription).minWidth(0f).left().pad(0f, 22f, 4f, 12f).growX().row()
        }
        val content = Table().apply {
            headingCell = add(heading).growX()
            row()
            add(tabs).grow()
        }
        add(sidebar).width(sidebarWidth).growY()
        add(com.badlogic.gdx.scenes.scene2d.ui.Image(BaseScreen.skin.newDrawable("Rectangle",
            ExpeditionBlueTheme.border.cpy().apply { a = 0.35f }))).width(1f).growY().padTop(10f).padBottom(12f)
        add(content).grow().row()
        pageTitle.width = tabs.prefWidth - 44f
        pageDescription.width = tabs.prefWidth - 44f
        frameHeight = header.prefHeight + heading.prefHeight
        tabs.resizePageArea(tabs.prefWidth, (stageToShowOn.height - frameHeight).coerceAtLeast(80f))
        tabs.onSelection { index, caption, _ ->
            pageTitle.setText(caption.tr())
            pageDescription.setText(visiblePages[index].description.tr())
            navigationButtons.forEachIndexed { i, button ->
                button.isChecked = i == index
                button.label.color = if (i == index) ExpeditionBlueTheme.gold else ExpeditionBlueTheme.muted
            }
        }
    }

    internal fun mountOnScreen() {
        // Reuse the settings table, without attaching a Popup or its modal input layer to the stage.
        innerTable.remove()
        stageToShowOn.addActor(innerTable)
        isVisible = true
        layoutScreen(Rectangle(0f, 0f, stageToShowOn.width, stageToShowOn.height))
    }

    internal fun layoutScreen(visibleArea: Rectangle) {
        val editing = visibleArea.height < stageToShowOn.height * 0.65f
        headingCell.setActor(if (editing) null else heading)
        val contentWidth = stageToShowOn.width - sidebarWidth - 1f
        pageTitle.width = contentWidth - 44f
        pageDescription.width = contentWidth - 44f
        heading.invalidateHierarchy()
        val headingHeight = if (editing) 0f else heading.prefHeight
        tabs.resizePageArea(contentWidth,
            (visibleArea.height - header.prefHeight - headingHeight).coerceAtLeast(60f))
        innerTable.setBounds(0f, visibleArea.y, stageToShowOn.width, visibleArea.height)
        innerTable.validate()
        tabs.validate()
        stageToShowOn.keyboardFocus?.takeIf { it.isDescendantOf(tabs) }?.let { tabs.pageScrollTo(it) }
    }

    override fun onVisibleAreaChanged(visibleArea: com.badlogic.gdx.math.Rectangle) {
        if (useSidebar) {
            val editing = visibleArea.height < stageToShowOn.height * 0.65f
            headerCell.setActor(if (editing) null else header)
            headingCell.setActor(if (editing) null else heading)
            tabs.resizePageArea(stageToShowOn.width * 0.9f - sidebarWidth,
                (visibleArea.height * 0.9f - if (editing) 0f else frameHeight).coerceAtLeast(60f))
        } else if (com.badlogic.gdx.Gdx.app.type == com.badlogic.gdx.Application.ApplicationType.iOS) {
            tabs.resizePageArea(
                if (stageToShowOn.height > stageToShowOn.width) stageToShowOn.width - 10f else stageToShowOn.width * 0.8f,
                visibleArea.height * 0.8f
            )
        }
        super.onVisibleAreaChanged(visibleArea)
        if (useSidebar) {
            validate()
            innerTable.validate()
            tabs.validate()
            stageToShowOn.keyboardFocus?.takeIf { it.isDescendantOf(tabs) }?.let { tabs.pageScrollTo(it) }
        }
    }

    override fun close() {
        if (screen is OptionsScreen) {
            screen.close()
            return
        }
        finishEditing()
        onClose() // activate the passed 'on close' callback
        super.close()
    }

    internal fun finishEditing() {
        game.musicController.onChange(null)
        tabs.selectPage(-1, false)
        settings.save()
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
