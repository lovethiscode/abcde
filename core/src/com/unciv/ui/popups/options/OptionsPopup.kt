package com.unciv.ui.popups.options

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.extensions.center
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.mainmenuscreen.MainMenuScreen
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Display

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
    private val pageTitle = "".toLabel(OptionsPopupStyle.text, OptionsPopupStyle.fontSize(22f)).apply { wrap = true }
    private val pageDescription = "".toLabel(OptionsPopupStyle.muted, OptionsPopupStyle.fontSize(14f)).apply { wrap = true }
    private val contentFontDelegate = lazy {
            Fonts.fontImplementation.getBitmapFont().apply { data.setScale(OptionsPopupStyle.fontSize(17f) / Fonts.ORIGINAL_FONT_SIZE) }
    }
    internal val contentFont get() = contentFontDelegate.value
    private var visibleHeight = 0f
    private lateinit var headingCell: Cell<Table>
    private lateinit var header: Table
    private lateinit var heading: Table
    private var sidebarWidth = 0f
    // Pager indices follow the visible tabs, not the full enum order.
    private val visiblePages = OptionsPopupPages.entries.filter { it.visible(withDebug) }
    private val pageIndex = HashMap<OptionsPopupPages, OptionsPopupTab>()
    override val activePage get() = visiblePages[tabs.activePage]
    override val rightWidgetMinWidth: Float
        get() = if (useSidebar) (tabs.prefWidth * 0.32f).coerceIn(OptionsPopupStyle.points(120f), OptionsPopupStyle.points(220f))
        else if (stageToShowOn.width < 600f) 200f else 240f
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
            val safe = Display.getSafeArea(com.badlogic.gdx.Gdx.graphics.width, com.badlogic.gdx.Gdx.graphics.height)
            val preferredSidebar = OptionsPopupStyle.points(if (minOf(safe.width, safe.height) >= 600) 240f else 200f)
            sidebarWidth = preferredSidebar.coerceAtMost(stage.width * 0.4f)
            tabMaxWidth = if (useSidebar) stage.width - sidebarWidth - 1f - OptionsPopupStyle.points(40f)
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
        innerTable.background = skin.newDrawable("Rectangle", OptionsPopupStyle.background)
        tabs.background = null
        innerTable.defaults().pad(0f)
        val sidebarHeader = Table(skin).apply {
            pad(OptionsPopupStyle.points(4f), OptionsPopupStyle.points(12f), OptionsPopupStyle.points(4f), OptionsPopupStyle.points(12f))
            val back = Button(skin).apply {
                style = Button.ButtonStyle(style).apply {
                    up = OptionsPopupStyle.surface(OptionsPopupStyle.sectionBackground)
                    down = OptionsPopupStyle.selectedNavigationDrawable()
                    over = down
                }
                name = "ui-options-back"
                add(ImageGetter.getImage("OtherIcons/BackArrow").apply { color = OptionsPopupStyle.text }).size(OptionsPopupStyle.points(20f))
                onClick { close() }
            }
            add(back).size(OptionsPopupStyle.points(44f)).padRight(OptionsPopupStyle.points(10f))
            add("Options".toLabel(OptionsPopupStyle.text, OptionsPopupStyle.fontSize(22f)).apply { wrap = true })
                .minWidth(0f).growX().left()
        }

        navigation.top().pad(0f, OptionsPopupStyle.points(12f), OptionsPopupStyle.points(8f), OptionsPopupStyle.points(12f))
        for ((index, page) in visiblePages.withIndex()) {
            val button = IconTextButton(page.label, page.getIcon(settings.language), OptionsPopupStyle.fontSize(17f)).apply {
                style = Button.ButtonStyle(style).apply {
                    up = OptionsPopupStyle.surface(Color.CLEAR)
                    checked = OptionsPopupStyle.selectedNavigationDrawable()
                    down = checked
                    over = checked
                }
                label.wrap = true
                labelCell.minWidth(0f).growX().left()
                pad(OptionsPopupStyle.points(6f), OptionsPopupStyle.points(12f), OptionsPopupStyle.points(6f), OptionsPopupStyle.points(12f))
                iconCell.size(OptionsPopupStyle.points(22f)).padLeft(0f).padRight(OptionsPopupStyle.points(12f))
                onClick { tabs.selectPage(index); isChecked = true }
            }
            navigationButtons.add(button)
            navigation.add(button).width(sidebarWidth - OptionsPopupStyle.points(24f))
                .minHeight(UiNavigation.minimumTouchSize()).padBottom(OptionsPopupStyle.points(8f)).row()
        }
        val sidebarScroll = AutoScrollPane(navigation).apply {
            setScrollingDisabled(true, false)
            setOverscroll(false, false)
        }
        val sidebar = Table(BaseScreen.skin).apply {
            background = BaseScreen.skin.newDrawable("Rectangle", OptionsPopupStyle.sidebar)
            add(sidebarHeader).growX().row()
            add(sidebarScroll).minHeight(0f).grow().row()
        }
        heading = Table().apply {
            add(pageTitle).minWidth(0f).left().growX().row()
            add(pageDescription).minWidth(0f).left().padTop(OptionsPopupStyle.points(4f)).growX().row()
        }
        header = Table().apply {
            pad(OptionsPopupStyle.points(10f), 0f, OptionsPopupStyle.points(12f), 0f)
            headingCell = add(heading).minWidth(0f).growX().left()
            val complete = "Done".toTextButton(OptionsPopupStyle.completeButtonStyle()).apply {
                name = "ui-options-complete"
                label.setFontSize(OptionsPopupStyle.fontSize(17f))
                pad(OptionsPopupStyle.points(8f), OptionsPopupStyle.points(20f), OptionsPopupStyle.points(8f), OptionsPopupStyle.points(20f))
                onClick { close() }
            }
            add(complete).minHeight(UiNavigation.minimumTouchSize()).minWidth(OptionsPopupStyle.points(72f))
                .padLeft(OptionsPopupStyle.points(16f))
        }
        val content = Table().apply {
            pad(0f, OptionsPopupStyle.points(20f), 0f, OptionsPopupStyle.points(20f))
            add(header).growX().row()
            add(tabs).grow()
        }
        add(sidebar).width(sidebarWidth).growY()
        add(com.badlogic.gdx.scenes.scene2d.ui.Image(BaseScreen.skin.newDrawable("Rectangle",
            OptionsPopupStyle.divider.cpy().apply { a = 0.6f }))).width(OptionsPopupStyle.points(0.5f)).growY()
        add(content).grow().row()
        pageDescription.width = tabs.prefWidth - OptionsPopupStyle.points(100f)
        tabs.resizePageArea(tabs.prefWidth, (stageToShowOn.height - header.prefHeight).coerceAtLeast(80f))
        tabs.onSelection { index, _, _ ->
            pageTitle.setText(visiblePages[index].label.tr())
            pageDescription.setText(visiblePages[index].description.tr())
            navigationButtons.forEachIndexed { i, button ->
                button.isChecked = i == index
                button.label.color = if (i == index) OptionsPopupStyle.accent else OptionsPopupStyle.muted
                button.icon?.color = button.label.color
            }
            heading.invalidateHierarchy()
            header.invalidateHierarchy()
            tabs.resizePageArea(tabs.prefWidth,
                ((visibleHeight.takeIf { it > 0f } ?: stageToShowOn.height) - header.prefHeight)
                    .coerceAtLeast(OptionsPopupStyle.points(44f)))
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
        visibleHeight = visibleArea.height
        val editing = visibleArea.height < stageToShowOn.height * 0.65f
        headingCell.setActor(if (editing) null else heading)
        val contentWidth = stageToShowOn.width - sidebarWidth - OptionsPopupStyle.points(0.5f) - OptionsPopupStyle.points(40f)
        pageDescription.width = (contentWidth - OptionsPopupStyle.points(100f)).coerceAtLeast(1f)
        heading.invalidateHierarchy()
        tabs.resizePageArea(contentWidth,
            (visibleArea.height - header.prefHeight).coerceAtLeast(OptionsPopupStyle.points(44f)))
        innerTable.setBounds(0f, visibleArea.y, stageToShowOn.width, visibleArea.height)
        innerTable.validate()
        tabs.validate()
        stageToShowOn.keyboardFocus?.takeIf { it.isDescendantOf(tabs) }?.let { tabs.pageScrollTo(it) }
    }

    override fun onVisibleAreaChanged(visibleArea: com.badlogic.gdx.math.Rectangle) {
        if (useSidebar) {
            visibleHeight = visibleArea.height * 0.9f
            val editing = visibleArea.height < stageToShowOn.height * 0.65f
            headingCell.setActor(if (editing) null else heading)
            tabs.resizePageArea(stageToShowOn.width * 0.9f - sidebarWidth - OptionsPopupStyle.points(40f),
                (visibleArea.height * 0.9f - header.prefHeight).coerceAtLeast(OptionsPopupStyle.points(44f)))
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
        disposeContentFont()
    }

    internal fun finishEditing() {
        game.musicController.onChange(null)
        tabs.selectPage(-1, false)
        settings.save()
    }

    internal fun disposeContentFont() {
        if (contentFontDelegate.isInitialized()) contentFont.dispose()
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
