package com.unciv.ui.screens.pickerscreens

import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.VerticalGroup
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.isEnabled
import com.unciv.ui.components.extensions.toTextButton
import com.badlogic.gdx.utils.Align

class PickerPane(
    disableScroll: Boolean = false,
) : Table() {
    /** The navigation button in the fixed header on iOS, otherwise lower left of [bottomTable], see [PickerScreen.setDefaultCloseAction].
     *  Note if you don't use that helper, you'll need to do both click and keyboard support yourself. */
    val closeButton = if (UiNavigation.isIos) UiNavigation.backButton() else Constants.close.toTextButton()
    private val navigationHeader = Table()
    /** A scrollable wrapped Label you can use to show descriptions in the [bottomTable], starts empty */
    val descriptionLabel = DescriptionLabel()
    /** Wraps descriptionLabel - a subclass may replace the content, but beware: zero padding around this */
    internal val descriptionScroll: AutoScrollPane
    /** A wrapper containing [rightSideButton]. You can add buttons, they will be arranged vertically */
    val rightSideGroup = VerticalGroup()
    /** A button on the lower right of [bottomTable] you can use for a "OK"-type action, starts disabled */
    val rightSideButton = "".toTextButton(if (ExpeditionBlueTheme.enabled)
        BaseScreen.skin.get("positive", TextButtonStyle::class.java) else null)

    private val screenSplit = 0.85f
    private val maxBottomTableHeight = 150f     // about 7 lines of normal text

    /**
     * The table displaying the choices from which to pick (usually).
     * Also the element which most of the screen real estate is devoted to displaying.
     */
    val topTable = Table()
    /** Holds the [Close button][closeButton], a [description label][descriptionLabel] and an [action button][rightSideButton] */
    val bottomTable = Table()
    /** A ScrollPane scrolling [topTable], disabled by the disableScroll parameter */
    val scrollPane = AutoScrollPane(topTable)
    /** A fixed SplitPane holds [scrollPane] and [bottomTable] */
    val splitPane = SplitPane(scrollPane, bottomTable, true, BaseScreen.skin)
    private var sideDescriptionTitle: Label? = null
    private var horizontalDescription = false

    init {
        if (UiNavigation.isIos) {
            navigationHeader.add(closeButton).minSize(UiNavigation.minimumTouchSize()).pad(8f)
            navigationHeader.add().expandX()
            add(navigationHeader).growX().row()
        } else bottomTable.add(closeButton).pad(10f)

        descriptionLabel.wrap = true
        val descriptionWithPad = Table()
        descriptionWithPad.add(descriptionLabel).pad(10f).grow()
        descriptionScroll = AutoScrollPane(descriptionWithPad, BaseScreen.skin)
        bottomTable.add(descriptionScroll).grow()

        rightSideButton.disable()
        rightSideGroup.addActor(rightSideButton)

        bottomTable.add(rightSideGroup).pad(10f).right()

        scrollPane.setScrollingDisabled(disableScroll, disableScroll)  // lock scrollPane
        if (disableScroll) scrollPane.clearListeners()  // remove focus capture of AutoScrollPane too
        add(splitPane).expand().fill()
    }

    override fun layout() {
        val showSideDescription = sideDescriptionTitle != null && width > height
        if (showSideDescription != horizontalDescription) {
            horizontalDescription = showSideDescription
            splitPane.setVertical(!showSideDescription)
            bottomTable.clear()
            rightSideGroup.expand(showSideDescription).fill(if (showSideDescription) 1f else 0f)
            rightSideButton.label.wrap = showSideDescription
            if (showSideDescription) {
                bottomTable.pad(12f)
                bottomTable.add(sideDescriptionTitle).minWidth(0f).growX().pad(10f).row()
                bottomTable.add(descriptionScroll).minWidth(0f).grow().row()
                bottomTable.add(rightSideGroup).minWidth(0f).growX().pad(10f)
                descriptionScroll.setScrollingDisabled(true, false)
            } else {
                bottomTable.pad(0f)
                bottomTable.add(descriptionScroll).grow()
                bottomTable.add(rightSideGroup).pad(10f).right()
            }
        }
        if (showSideDescription)
            splitPane.splitAmount = 1f - (width * .26f).coerceIn(240f, 320f) / width
        if (UiNavigation.isIos) {
            val hasNavigation = closeButton.parent === navigationHeader && closeButton.isVisible
            navigationHeader.isVisible = hasNavigation
            getCell(navigationHeader).height(if (hasNavigation) maxOf(closeButton.height + 16f, navigationHeader.prefHeight) else 0f)
        }
        super.layout()
        if (!showSideDescription) {
            bottomTable.height = bottomTable.height.coerceAtMost(maxBottomTableHeight)
            splitPane.splitAmount = (scrollPane.height / (scrollPane.height + bottomTable.height)).coerceAtLeast(screenSplit)
        }
    }

    /** Technology descriptions use a full-height panel in landscape and the usual footer in portrait. */
    fun useSideDescription(title: Label) {
        sideDescriptionTitle = title.apply { wrap = true; setAlignment(Align.left) }
        descriptionLabel.setAlignment(Align.topLeft)
        invalidateHierarchy()
    }

    fun setNavigationContent(content: Actor) {
        if (!UiNavigation.isIos) return
        navigationHeader.clear()
        navigationHeader.add(closeButton).minSize(UiNavigation.minimumTouchSize()).pad(8f)
        navigationHeader.add(content).growX().minWidth(0f).left().pad(8f)
        invalidateHierarchy()
    }

    fun useCloseButtonAsAction() {
        if (!UiNavigation.isIos) return
        UiNavigation.styleTextButton(closeButton)
        navigationHeader.clear()
        bottomTable.clear()
        bottomTable.add(closeButton).minSize(UiNavigation.minimumTouchSize()).pad(10f)
        bottomTable.add(descriptionScroll).grow()
        bottomTable.add(rightSideGroup).pad(10f).right()
    }

    /** Enables the [rightSideButton]. See [pick] for a way to set the text. */
    fun setRightSideButtonEnabled(enabled: Boolean) {
        rightSideButton.isEnabled = enabled
    }

    /** Sets the text of the [rightSideButton] (does not autotranslate) and enables it if it's the player's turn */
    fun pick(rightButtonText: String) {
        if (GUI.isMyTurn()) rightSideButton.enable()
        rightSideButton.setText(rightButtonText)
    }

    /** This Label adjusts the Y scroll of its ascendant ScrollPane when its text is set:
     *  - short texts stay top-aligned with [closeButton]
     *  - taller texts scroll some of the wrapper top padding out
     *  - still taller texts scroll most but not all of the top padding out
     *
     *  This also ensures vertical scroll is reset when changing description
     */
    inner class DescriptionLabel : Label("", BaseScreen.skin) {
        override fun setText(newText: CharSequence?) {
            super.setText(newText)
            descriptionScroll.validate()
            descriptionScroll.scrollY = (prefHeight + 10f - descriptionScroll.height).coerceIn(0f, 8f)
            descriptionScroll.updateVisualScroll()
        }
    }

    companion object {
        /** Icon size used in [getPickerOptionButton]. */
        const val pickerOptionIconSize = 30f
        /** Return a button for picker screens that display a list of big buttons with icons and labels. */
        fun getPickerOptionButton(icon: Actor, label: String): Button {
            return IconTextButton(label, icon).apply {
                iconCell.size(pickerOptionIconSize).pad(10f)
                labelCell.pad(10f)
            }
        }
    }
}
