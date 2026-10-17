package com.unciv.ui.popups.options

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.scenes.scene2d.ui.Table
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
        pad(if (optionsPopup.useSidebar) 0f else 10f)
        defaults().pad(if (optionsPopup.useSidebar) 0f else 5f)
        top()
    }

    protected fun section(title: String, opened: Boolean = true, block: Table.() -> Unit) {
        if (!optionsPopup.useSidebar) {
            addHeader(title)
            block()
            return
        }
        val card = ExpanderTab(title, fontSize = OptionsPopupStyle.fontSize(TextRole.Body), startsOutOpened = opened,
            defaultPad = OptionsPopupStyle.points(4f), topPad = 0f,
            headerPad = OptionsPopupStyle.points(12f), expanderWidth = 1f) {
            it.defaults().pad(OptionsPopupStyle.points(4f)).expand(0, 0).fill(false, false)
            it.block()
            OptionsPopupStyle.enlargeText(it, optionsPopup.contentFont)
        }
        card.background = OptionsPopupStyle.surface(OptionsPopupStyle.sectionBackground)
        card.header.background = null
        card.getCell(card.header).minHeight(OptionsPopupStyle.points(48f))
        card.headerIcon.color = OptionsPopupStyle.muted
        card.header.getCell(card.headerIcon).size(OptionsPopupStyle.points(14f))
        add(card).colspan(2).growX().padBottom(OptionsPopupStyle.points(16f)).row()
    }

    open fun lateInitialize() {
        if (optionsPopup.useSidebar) OptionsPopupStyle.enlargeText(this, optionsPopup.contentFont)
        isInitialized = true
    }

    override fun activated(index: Int, caption: String, pager: TabbedPager) {
        if (!isInitialized) lateInitialize()
    }

    open fun subSelect(select: String?) {}
}
