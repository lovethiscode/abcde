package com.unciv.ui.screens.pickerscreens

import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.UncivGame
import com.unciv.logic.civilization.Civilization
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onDoubleClick
import com.unciv.ui.images.ImageGetter

class DiplomaticVotePickerScreen(private val votingCiv: Civilization, private val initialChoice: String? = null, private val initiallySelected: Boolean = false) : OrdinaryGamePopupScreen(), com.unciv.ui.screens.basescreen.RecreateOnResize {
    private var chosenCiv: String? = initialChoice
    private var hasSelection = initiallySelected
    private val ordinary = com.unciv.ui.components.widgets.UiNavigation.isIos && votingCiv.gameInfo.campaign == null && votingCiv.gameInfo.endless == null

    init {
        setDefaultCloseAction()
        rightSideButton.setText("Choose a civ to vote for".tr())

        descriptionLabel.setText("Choose who should become the world leader and win a Diplomatic Victory!".tr())

        val choosableCivs = votingCiv.diplomacyFunctions.getKnownCivsSorted(false)
        for (civ in choosableCivs) {
            addButton(civ.civName, "Vote for [${civ.civName}]", civ.civID,
                ImageGetter.getNationPortrait(
                    civ.nation,
                    PickerPane.pickerOptionIconSize
                )
            )
        }
        addButton("Abstain", "Abstain", null,
            ImageGetter.getImage("OtherIcons/Stop").apply {
                setSize(PickerPane.pickerOptionIconSize, PickerPane.pickerOptionIconSize)
            }
        )

        rightSideButton.onClick(UncivSound.Chimes, ::voteAndClose)
        if (ordinary) presentGameDialog(object : com.unciv.ui.popups.Popup(this@DiplomaticVotePickerScreen) {
            init {
                val unOwner = votingCiv.victoryManager.getUNBuildingAndOwnerNames().second
                val votes = if (unOwner == votingCiv.civID) 2 else 1
                addDialogTitle("Elect a world leader", "Votes available: [$votes]", dismissible = false)
                addGoodSizedLabel("Choose who should become the world leader and win a Diplomatic Victory!").row()
                val cards = ArrayList<com.badlogic.gdx.scenes.scene2d.ui.TextButton>()
                val confirm = addButton("Choose a civ to vote for") { voteAndClose() }.actor.apply { isDisabled = !hasSelection }
                styleOrdinaryButton(confirm, primary = true)
                for ((caption, choice) in choosableCivs.map { it.civName to it.civID } + ("Abstain" to null)) {
                    val card = com.badlogic.gdx.scenes.scene2d.ui.TextButton(caption.tr(), skin)
                    card.isChecked = hasSelection && choice == chosenCiv
                    cards += card
                    card.onClick {
                        cards.forEach { it.isChecked = it === card }
                        chosenCiv = choice; hasSelection = true
                        confirm.isDisabled = false
                        confirm.setText((if (choice == null) "Confirm abstention" else "Vote for [$caption]").tr())
                    }
                    addChoiceCard(card, if (choice == null) "Do not vote for any civilization." else "Vote for [$caption]")
                    if (card.isChecked) confirm.setText((if (choice == null) "Confirm abstention" else "Vote for [$caption]").tr())
                }
                addGoodSizedLabel("Minimum votes for electing a world leader: [${votingCiv.victoryManager.votesNeededForDiplomaticVictory()}]",
                    com.unciv.ui.components.fonts.UiTypography.size(com.unciv.ui.components.fonts.TextRole.Supporting, popupUnit)).row()
                val back = addCloseButton("Return to map", action = { game.popScreen() }).actor
                bottomTable.clearChildren()
                bottomTable.add(back); bottomTable.add(confirm)
            }
        })
    }

    override fun recreate(): com.unciv.ui.screens.basescreen.BaseScreen = DiplomaticVotePickerScreen(votingCiv, chosenCiv, hasSelection)

    private fun voteAndClose() {
        votingCiv.diplomaticVoteForCiv(chosenCiv)
        UncivGame.Current.popScreen()
    }

    private fun addButton(caption: String, pickText: String, choice: String?, icon: Actor) {
        val button = PickerPane.getPickerOptionButton(icon, caption)
        button.onClick {
            chosenCiv = choice
            pick(pickText.tr())
        }
        button.onDoubleClick(UncivSound.Chimes) {
            chosenCiv = choice
            voteAndClose()
        }
        topTable.add(button).fillX().pad(10f).row()
    }
}
