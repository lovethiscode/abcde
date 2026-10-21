package com.unciv.ui.screens.espionage

import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.SplitPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.Align
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.skins.ExpeditionBlueTheme
import com.unciv.models.translations.tr
import com.unciv.ui.components.SmallButtonStyle
import com.unciv.ui.components.extensions.addCapitalIndicator
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.getCloseButton
import com.unciv.ui.components.extensions.scrollTo
import com.unciv.ui.components.extensions.setSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.components.widgets.AutoScrollPane
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AnimatedMenuPopup
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.OverviewUi
import com.unciv.ui.screens.worldscreen.WorldScreen
import yairm210.purity.annotations.Pure
import kotlin.collections.iterator

/** On this screen, possible locations of spies are a [com.unciv.logic.city.City] or the Spy Hideout which we represent as `null` */
private typealias Location = City?

/** Screen used for moving spies between cities */
class EspionageScreen(val civInfo: Civilization, val worldScreen: WorldScreen) : BaseScreen() {
    private val collator = UncivGame.Current.settings.getCollatorFromLocale()

    private val spySelectionTable = Table()
    private val spyScrollPane = AutoScrollPane(spySelectionTable)
    private val locationSelectionTable = Table()
    private val locationScrollPane = AutoScrollPane(locationSelectionTable)
    private val spyCount = OverviewUi.plainLabel("", TextRole.Supporting, OverviewUi.accent)
    private val locationCount = OverviewUi.plainLabel("", TextRole.Supporting, OverviewUi.accent)
    private val spyPanel = taskPanel("Spy", spyCount, spyScrollPane)
    private val locationPanel = taskPanel("City", locationCount, locationScrollPane)
    private val middlePanes = SplitPane(spyPanel, locationPanel, false, skin)
    private val root = Table()

    private var selectedSpyButton: TextButton? = null
    private var selectedSpy: Spy? = null

    // if the value == null, this means the Spy Hideout.
    private val spyActionButtons = hashMapOf<SpyLocationActionButton, Location>()
    private val moveSpyButtons = hashMapOf<Spy, TextButton>()

    private val locationLabels = mutableMapOf<String?, Actor>()
    private val closeButton: Group

    // With dynamic button text, the layout "flutters" when selecting/deselecting a Spy - so control the minWidth:
    // Which one is fatter is obvious in English, but in other languages not so much
    private val moveButtonWidth = "Move".toTextButton().prefWidth.coerceAtLeast(Constants.cancel.toTextButton().prefWidth)

    /** Readability shortcut */
    private val manager get() = civInfo.espionageManager

    override val screenClearColor: Color get() = OverviewUi.background

    private val isPortrait: Boolean // To detect when a resize needs re-layout

    init {
        spySelectionTable.defaults().fillX().minWidth(0f)
        spySelectionTable.pad(OverviewUi.pt(8f)).top()
        locationSelectionTable.defaults().fillX().minWidth(0f)
        locationSelectionTable.pad(OverviewUi.pt(8f)).top()

        update()

        middlePanes.minSplitAmount = 0.25f
        middlePanes.maxSplitAmount = 0.75f
        isPortrait = if (isPortrait()) {
            middlePanes.splitAmount = 0.33f
            middlePanes.isVertical = true
            true
        } else {
            middlePanes.splitAmount = getPrefSplitAmount()
            false
        }
        fun navigateBack() {
            civInfo.cache.updateViewableTiles()
            game.popScreen()
        }
        closeButton = if (UiNavigation.isIos) UiNavigation.backButton("Map").apply { onActivation { navigateBack() } }
            else getCloseButton { navigateBack() }
        closeButton.keyShortcuts.add(KeyCharAndCode.BACK)

        val header = Table().apply {
            if (UiNavigation.isIos)
                add(closeButton).minSize(OverviewUi.touch).padRight(OverviewUi.pt(12f))
            add(OverviewUi.plainLabel("Espionage", TextRole.PageTitle).apply { wrap = true })
                .growX().minWidth(0f).left()
            if (!UiNavigation.isIos)
                add(closeButton).minSize(OverviewUi.touch).padLeft(OverviewUi.pt(12f))
        }
        root.apply {
            setFillParent(true)
            background = OverviewUi.surface(OverviewUi.background)
            pad(OverviewUi.pt(16f))
            add(header).growX().minHeight(OverviewUi.touch).padBottom(OverviewUi.pt(12f)).row()
            add(middlePanes).grow().minWidth(0f).minHeight(0f)
        }
        stage.addActor(root)
        globalShortcuts.add(KeyboardBinding.Civilopedia) { openCivilopedia("Tutorial/Espionage") }
    }

    private fun taskPanel(title: String, count: Actor, content: Actor) = Table().apply {
        background = OverviewUi.rounded(OverviewUi.panel, 12f)
        pad(OverviewUi.pt(8f))
        add(OverviewUi.plainLabel(title, TextRole.SectionTitle)).growX().minWidth(0f).left()
        add(count).right().row()
        add(content).colspan(2).grow().minWidth(0f).minHeight(0f).padTop(OverviewUi.pt(6f))
    }

    private fun getPrefSplitAmount(): Float {
        val handleWidth = middlePanes.style.handle.minWidth
        val freeSpace = stage.width - handleWidth - spyPanel.prefWidth - locationPanel.prefWidth
        val x = spyPanel.prefWidth + freeSpace.coerceAtLeast(handleWidth) / 2
        return (x / stage.width).coerceIn(0.3f, 0.7f)
    }

    // Not using `RecreateOnResize` because this one is fine with resizes, exept we want to flip SplitPane arrangement for portrait
    override fun resize(width: Int, height: Int) {
        super.resize(width, height)
        if (isPortrait() == isPortrait) return
        game.replaceCurrentScreen { EspionageScreen(civInfo, worldScreen) }
    }

    //region Update
    private fun update() {
        updateSpyList()
        updateLocationList()
    }

    private fun updateSpyList() {
        spySelectionTable.clear()
        moveSpyButtons.clear()
        spyCount.setText(manager.spyList.size.toString())
        // Show spies in insertion order = order of seniority
        for (spy in manager.spyList) {
            addSpyToSelectionTable(spy)
        }
    }

    private fun addSpyToSelectionTable(spy: Spy) {
        val key = spy.getCityOrNull()?.id
        val label = spy.getLocationName().toLabel(hideIcons = true, role = TextRole.Body)
        label.onActivation {
            val actor = locationLabels[key]
            if (actor != null) locationScrollPane.scrollTo(actor, true)
        }
        val actionString = if (spy.action.showTurns)
            "[${spy.action.displayString}] ${spy.turnsRemainingForAction}${Fonts.turn}"
        else spy.action.displayString
        val moveSpyButton = "Move".toTextButton()
        moveSpyButton.onClick {
            onSpyClicked(moveSpyButton, spy)
        }
        moveSpyButton.onRightClick {
            onSpyRightClicked(spy)
        }
        if (!worldScreen.canChangeState || !spy.isAlive() || civInfo.isDefeated()) {
            // Spectators aren't allowed to move the spies of the Civs they are viewing
            moveSpyButton.disable()
        } else {
            AnimatedMenuPopup.Helpers.addIndicator(moveSpyButton, 0.5f)
        }
        moveSpyButtons[spy] = moveSpyButton

        val identity = Table().apply {
            add(getSpyIcon(spy)).size(OverviewUi.pt(40f)).padRight(OverviewUi.pt(10f))
            add(Table().apply {
                add(spy.name.toLabel(hideIcons = true, role = TextRole.SectionTitle)).growX().left().row()
                add(("Rank".tr() + " " + spy.getEffectiveRank()).toLabel(
                    hideIcons = true, role = TextRole.Supporting
                ))
                    .growX().left()
            }).growX().minWidth(0f)
        }
        val status = Table().apply {
            add(label).growX().minWidth(0f).left().row()
            add(actionString.toLabel(TextRole.Supporting)).growX().minWidth(0f).left()
        }
        val card = Table().apply {
            background = OverviewUi.rounded(OverviewUi.background, 9f)
            pad(OverviewUi.pt(8f))
            add(identity).growX().minWidth(0f)
            add(status).growX().minWidth(0f).padLeft(OverviewUi.pt(12f))
            add(moveSpyButton).minWidth(maxOf(moveButtonWidth, OverviewUi.pt(84f)))
                .minHeight(OverviewUi.touch).padLeft(OverviewUi.pt(10f))
        }
        spySelectionTable.add(card).growX().minWidth(0f).padBottom(OverviewUi.pt(8f)).row()
    }

    private fun updateLocationList() {
        locationSelectionTable.clear()
        spyActionButtons.clear()
        locationLabels.clear()

        @Suppress("DEPRECATION") // The replacement doesn't allow chaining filters
        val sortedLocations =
            sequenceOf<Location>(null) + // First add the hideout to the table
            civInfo.gameInfo.getCities() // Then add all cities
            .filter { civInfo.hasExplored(it.getCenterTile()) }
            .sortedWith(
                compareBy<City> {
                    it.civ != civInfo
                }.thenBy {
                    it.civ.isCityState
                }.thenBy(collator) {
                    it.civ.civName.tr(hideIcons = true)
                }.thenBy(collator) {
                    it.name.tr(hideIcons = true)
                }
            )
        val locations = sortedLocations.toList()
        locationCount.setText(locations.size.toString())
        for (location in locations) {
            addLocationToSelectionTable(location)
        }
    }

    private fun addLocationToSelectionTable(location: Location) {
        val key = location?.id
        val label = location.getLocationName().toLabel(hideIcons = true, role = TextRole.Body)
        locationLabels[key] = label
        if (location != null) {
            label.onClick {
                worldScreen.game.popScreen() // If a detour to this screen (i.e. not directly from worldScreen) is made possible, use resetToWorldScreen instead
                worldScreen.mapHolder.setCenterPosition(location.location.toHexCoord())
            }
        }
        val spies = location.getSpies()

        val spy = spies.firstOrNull()
        val actionButton = if (location != null && location.civ.isCityState && spy != null && spy.canDoCoup())
            CoupButton(location, spy.action == SpyAction.Coup)
        else MoveToLocationButton(location)
        val context = location?.civ?.civName?.toLabel(hideIcons = true, role = TextRole.Supporting)
        val card = Table().apply {
            background = OverviewUi.rounded(OverviewUi.background, 9f)
            pad(OverviewUi.pt(8f))
            add(location.getIcon()).size(OverviewUi.pt(36f)).padRight(OverviewUi.pt(10f))
            add(Table().apply {
                add(label).growX().minWidth(0f).left().row()
                if (context != null) add(context).growX().minWidth(0f).left()
            }).growX().minWidth(0f)
            add(getSpyIcons(spies)).padLeft(OverviewUi.pt(8f))
            add(actionButton).minSize(OverviewUi.touch).padLeft(OverviewUi.pt(8f))
        }
        locationSelectionTable.add(card).growX().minWidth(0f).padBottom(OverviewUi.pt(8f)).row()
    }

    private fun getSpyIcon(spy: Spy) = Table().apply {
        add(ImageGetter.getImage("OtherIcons/Spy_White").apply {
            color = Color.WHITE
        }).size(30f)
        fun getColor(rank: Int): Color = when (rank) {
            1 -> ExpeditionBlueTheme.color(Color.BROWN, Color.valueOf("C99562"))
            2 -> Color.LIGHT_GRAY
            else -> Color.GOLD
        }

        val displayRank = spy.getEffectiveRank()

        // If we have 10 or more ranks, display them with a bigger star
        if (displayRank >= 10) {
            val star = ImageGetter.getImage("OtherIcons/Star")
            star.color = getColor(displayRank / 10)
            add(star).size(20f).pad(3f)
        }

        val color = getColor(displayRank)
        val starTable = Table()
        // Create a grid of up to 9 stars
        repeat(displayRank % 10) {
            val star = ImageGetter.getImage("OtherIcons/Star")
            star.color = color
            starTable.add(star).size(8f).pad(1f)
            if (it % 3 == 2)
                starTable.row()
        }
        add(starTable).center().padLeft(-4f)

        // Spectators aren't allowed to move the spies of the Civs they are viewing
        if (worldScreen.canChangeState && spy.isAlive() && !civInfo.isDefeated()) {
            onClick {
                onSpyClicked(moveSpyButtons[spy]!!, spy)
            }
            onRightClick {
                onSpyRightClicked(spy)
            }
        }
    }

    private fun getSpyIcons(spies: Iterable<Spy>) = Table().apply {
        defaults().space(0f, 2f, 0f, 2f)
        for (spy in spies)
            add(getSpyIcon(spy))
    }

    /** Get the name of a location: City name or Spy Hideout for (`[this]==null`) */
    private fun Location.getLocationName() =
        this?.name ?: "Spy Hideout"

    /** Get the spies at a location: Spies assigned to a City or Spies in the Hideout (`[this]==null`) */
    private fun Location.getSpies() =
        if (this == null) manager.getIdleSpies() else manager.getSpiesInCity(this)

    /** Get an icon for a location:
     *  City as Nation icon with optional Capital indicator, or the Hideout (`[this]==null`) */
    private fun Location.getIcon(): Actor {
        if (this == null)
            return ImageGetter.getImage("OtherIcons/Hideout").apply { setSize(33f) }

        val icon = ImageGetter.getNationPortrait(civ.nation, 30f)
        if (isCapital() && !civ.isCityState)
            icon.addCapitalIndicator()
        return icon
    }
    //endregion

    //region Interaction helpers
    private fun onSpyClicked(moveSpyButton: TextButton, spy: Spy) {
        if (selectedSpyButton == moveSpyButton) {
            resetSelection()
            return
        }
        resetSelection()
        selectedSpyButton = moveSpyButton
        selectedSpy = spy
        selectedSpyButton!!.label.setText(Constants.cancel.tr())
        for ((button, location) in spyActionButtons) {
            if (location == spy.getCityOrNull()) {
                button.isVisible = true
                button.setDirection(Align.right)
            } else {
                button.isVisible = location == null // hideout
                    || !location.espionage.hasSpyOf(civInfo)
                button.setDirection(Align.left)
            }
        }
    }

    private fun onSpyRightClicked(spy: Spy) {
        worldScreen.bottomUnitTable.selectSpy(worldScreen.selectedGameView.getSpyView(spy))
        worldScreen.game.popScreen()
        worldScreen.shouldUpdate = true
    }

    private fun resetSelection() {
        selectedSpy = null
        selectedSpyButton?.label?.setText("Move".tr())
        selectedSpyButton = null
        for ((button, _) in spyActionButtons)
            button.isVisible = false
    }
    //endregion

    //region Spy Movement Button classes
    private abstract inner class SpyLocationActionButton(protected val location: Location) : Button(SmallButtonStyle()) {
        init {
            onClick(::clickHandler)
            spyActionButtons[this] = location
            isVisible = false
        }
        protected abstract fun clickHandler()
        open fun setDirection(align: Int) {}
    }

    private inner class MoveToLocationButton(location: Location) : SpyLocationActionButton(location) {
        val arrow = ImageGetter.getArrowImage(Align.left)

        init {
            arrow.setSize(24f)
            add(arrow).size(24f)
            arrow.setOrigin(Align.center)
            arrow.color = Color.WHITE
        }

        private fun move() {
            selectedSpy!!.moveTo(location)
            resetSelection()
            update()
        }

        override fun clickHandler() {
            if (location != null
                && location.civ.civName != civInfo.civName
                && location.civ.isMajorCiv()
                && location.civ.knows(civInfo) // ensures the !! below won't crash
                && location.civ.getDiplomacyManager(civInfo)!!.hasFlag(DiplomacyFlags.AgreedToNotSendSpies)) {
                ConfirmPopup(
                    this@EspionageScreen,
                    // The promise isn't broken when you move the spy - only when they catch you stealing a tech
                    "This may result in breaking your promise to [${location.civ.civName}]",
                    "Move",
                    action = ::move
                ).open(force = true)
            } else {
                move()
            }
        }

        override fun setDirection(align: Int) {
            arrow.rotation = if (align == Align.right) 0f else 180f
            isDisabled = align == Align.right
        }
    }

    private inner class CoupButton(city: City, private val isCurrentAction: Boolean) : SpyLocationActionButton(city) {
        val fist = ImageGetter.getStatIcon("Resistance")

        init {
            fist.setSize(24f)
            add(fist).size(24f)
            fist.setOrigin(Align.center)
            fist.color = if (isCurrentAction) Color.WHITE else Color.DARK_GRAY
        }

        override fun clickHandler() {
            val spy = selectedSpy!!
            if (!isCurrentAction) {
                ConfirmPopup(
                    this@EspionageScreen,
                    "Do you want to stage a coup in [${location!!.civ.civName}] with a " +
                        "[${(selectedSpy!!.getCoupChanceOfSuccess(false) * 100f).toInt()}]% " +
                        "chance of success?",
                    "Stage Coup"
                ) {
                    spy.setAction(SpyAction.Coup, 1)
                    fist.color = Color.DARK_GRAY
                    update()
                }.open()
            } else {
                spy.setAction(SpyAction.CounterIntelligence, 10)
                fist.color = Color.WHITE
                update()
            }
        }
    }
    //endregion
}
