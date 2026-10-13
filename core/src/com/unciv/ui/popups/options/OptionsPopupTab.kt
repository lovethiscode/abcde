package com.unciv.ui.popups.options

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.ui.components.widgets.ExpanderTab
import com.unciv.ui.components.widgets.TabbedPager
import com.unciv.ui.screens.basescreen.BaseScreen.Companion.skin

/**
 *  Common base for the page content Tables.
 *
 *  Provides the [OptionsPopupHelpers] functions and adds some shortcut accessors.
 *
 *  Shortcuts: [settings], [game], [activePage], [selectPage], [replacePage]
 *
 *  Sets table padding to 10 and cell default padding to 5.
 */
internal abstract class OptionsPopupTab(
    internal val optionsPopup: OptionsPopup,
) : Table(skin), TabbedPager.IPageExtensions, OptionsPopupHelpers {
    override val rightWidgetMinWidth by optionsPopup::rightWidgetMinWidth
    override val activePage by optionsPopup::activePage

    val settings by optionsPopup::settings
    val game by optionsPopup::game

    fun selectPage(name: String) = optionsPopup.tabs.selectPage(name)
    fun replacePage(factory: (OptionsPopup) -> OptionsPopupTab) =
        optionsPopup.tabs.replacePage(optionsPopup.tabs.activePage, factory(optionsPopup))

    private var isInitialized = false

    init {
        pad(10f)
        defaults().pad(5f)
        top()
    }

    protected fun section(title: String, opened: Boolean = true, block: Table.() -> Unit) {
        if (!optionsPopup.useSidebar) {
            addHeader(title)
            block()
            return
        }
        val card = ExpanderTab(title, fontSize = 18, startsOutOpened = opened,
            defaultPad = 8f, headerPad = 12f, expanderWidth = 1f) {
            it.defaults().pad(4f).growX()
            it.block()
        }
        card.background = ExpeditionBlueTheme.outlinedControl(
            skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.panel),
            skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.border))
        card.header.background = skin.newDrawable("RoundedEdgeRectangle", ExpeditionBlueTheme.raised)
        add(card).colspan(2).growX().padBottom(8f).row()
    }

    open fun lateInitialize() {
        isInitialized = true
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (!isInitialized) lateInitialize()
    }

    open fun subSelect(select: String?) {}
}
