package com.unciv.ui.screens.worldscreen

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.models.skins.ExpeditionBlueTheme
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Touchable
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.Container
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.models.UncivSound
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.colorFromRGB
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.images.IconTextButton
import com.unciv.ui.components.widgets.UiNavigation
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.diplomacyscreen.DiplomacyScreen
import com.unciv.ui.screens.espionage.EspionageScreen
import com.unciv.ui.screens.pickerscreens.PolicyPickerScreen
import com.unciv.ui.screens.pickerscreens.TechButton
import com.unciv.ui.screens.pickerscreens.TechPickerScreen
import com.unciv.ui.screens.worldscreen.UndoHandler.Companion.canUndo
import com.unciv.ui.screens.worldscreen.UndoHandler.Companion.restoreUndoCheckpoint


/** A holder for Tech, Policies and Diplomacy buttons going in the top left of the WorldScreen just under WorldScreenTopBar */
class TechPolicyDiplomacyButtons(val worldScreen: WorldScreen) : Table(BaseScreen.skin) {
    private val fogOfWarButtonHolder = Container<Button?>()
    private val fogOfWarButton = "Fog of War".toTextButton()

    private val techButtonHolder = Container<Table?>()
    private val pickTechButton = Table(skin)
    private val pickTechLabel = "".toLabel(Color.WHITE, UiTypography.size(TextRole.PageTitle, legacySize = 30))

    private val policyButtonHolder = Container<Button?>()
    private val policyScreenButton = Button(skin)
    private val diplomacyButtonHolder = Container<Button?>()
    private val diplomacyButton = Button(skin)
    private val undoButtonHolder = Container<Button?>()
    private val undoButton = Button(skin)
    private val espionageButtonHolder = Container<Button?>()
    private val espionageButton = Button(skin)

    private val viewingCiv = worldScreen.selectedGameView.civView
    private val game = worldScreen.game

    init {
        techButtonHolder.name = "ui-tech-open"
        policyButtonHolder.name = "ui-policy-open"
        diplomacyButtonHolder.name = "ui-diplomacy-open"
        defaults().left()
        add(fogOfWarButtonHolder).colspan(4).row()
        add(techButtonHolder).colspan(4).row()
        add(policyButtonHolder).padTop(10f).padRight(10f)
        add(diplomacyButtonHolder).padTop(10f).padRight(10f)
        add(espionageButtonHolder).padTop(10f).padRight(10f)
        add(undoButtonHolder).padTop(10f).padRight(10f)
        add().growX()  // Allows Policy and Diplo buttons to keep to the left

        fogOfWarButton.label.setFontSize(UiTypography.size(TextRole.PageTitle, legacySize = 30))
        fogOfWarButton.labelCell.pad(10f)
        fogOfWarButton.pack()
        fogOfWarButtonHolder.onActivation(UncivSound.Paper) {
            worldScreen.fogOfWar = !worldScreen.fogOfWar
            worldScreen.shouldUpdate = true
        }

        pickTechButton.background = BaseScreen.skinStrings.getUiBackground("WorldScreen/PickTechButton", BaseScreen.skinStrings.roundedEdgeRectangleShape, colorFromRGB(7, 46, 43))
        pickTechButton.defaults().pad(20f)
        pickTechButton.add(pickTechLabel)
        techButtonHolder.onActivation(UncivSound.Paper, KeyboardBinding.TechnologyTree) {
            game.pushScreen{ TechPickerScreen(worldScreen.selectedGameView.civView.getCiv()) }
        }

        undoButton.add(ImageGetter.getImage("OtherIcons/Undo")).size(30f).pad(15f)
        undoButton.onActivation(binding = KeyboardBinding.Undo) {
            handleUndo()
        }

        policyScreenButton.add(ImageGetter.getImage("OtherIcons/Policies")).size(30f).pad(15f)
        policyButtonHolder.onActivation(binding = KeyboardBinding.SocialPolicies) {
            game.pushScreen{ PolicyPickerScreen(worldScreen.selectedCiv, worldScreen.canChangeState) }
        }

        diplomacyButton.add(ImageGetter.getImage("OtherIcons/DiplomacyW")).size(30f).pad(15f)
        diplomacyButtonHolder.onActivation(binding = KeyboardBinding.Diplomacy) {
            game.pushScreen{ DiplomacyScreen(worldScreen.selectedGameView.civView) }
        }

        if (game.gameInfo!!.isEspionageEnabled()) {
            espionageButton.add(ImageGetter.getImage("OtherIcons/Espionage")).size(30f).pad(15f)
            espionageButtonHolder.onActivation(binding = KeyboardBinding.Espionage) {
                // We want to make sure to deselect a spy in the case that the player wants to cancel moving
                // the spy on the map screen by pressing this button
                if (worldScreen.bottomUnitTable.selectedSpy != null) {
                    worldScreen.bottomUnitTable.selectSpy(null)
                }
                game.pushScreen{ EspionageScreen(worldScreen.selectedCiv, worldScreen) }
            }
        }
    }

    fun update(): Boolean {
        updateFogOfWarButton()
        updateTechButton()
        updateUndoButton()
        updatePolicyButton()
        val result = updateDiplomacyButton()
        if (game.gameInfo!!.isEspionageEnabled())
            updateEspionageButton()
        if (BattlefieldHud.enabled) {
            clearChildren()
            val touch = UiNavigation.minimumTouchSize()
            if (fogOfWarButtonHolder.actor != null) add(fogOfWarButtonHolder).minHeight(touch).row()
            val techWidth = BattlefieldHud.points(140f)
            techButtonHolder.fill().minSize(0f).maxSize(techWidth, touch)
            add(techButtonHolder).width(techWidth).height(touch)
            for (holder in listOf(policyButtonHolder, diplomacyButtonHolder, espionageButtonHolder, undoButtonHolder))
                if (holder.actor != null) {
                    holder.fill().minSize(0f).maxSize(touch)
                    BattlefieldGeometry.fitControl(holder.actor!!)
                    holder.actor!!.pad(BattlefieldHud.points(6f))
                    for (cell in holder.actor!!.cells) cell.size(BattlefieldHud.points(20f)).pad(0f)
                    add(holder).size(touch).padLeft(BattlefieldHud.points(4f))
                }
        }
        pack()
        setPosition(10f, worldScreen.topBar.y - height - 15f)
        return result
    }

    private fun updateFogOfWarButton() {
        if (viewingCiv.isSpectator()) {
            fogOfWarButtonHolder.actor = fogOfWarButton
            fogOfWarButtonHolder.touchable = Touchable.enabled
        } else {
            fogOfWarButtonHolder.touchable = Touchable.disabled
            fogOfWarButtonHolder.actor = null
        }
    }

    private fun updateTechButton() {
        // Spectators can change the selected civilization without recreating this HUD.
        val researchCiv = worldScreen.selectedGameView.civView
        techButtonHolder.touchable = Touchable.disabled
        techButtonHolder.actor = null
        if (worldScreen.gameInfo.ruleset.technologies.isEmpty() || researchCiv.cities().isEmpty()) return
        techButtonHolder.touchable = Touchable.enabled

        val currentTech = researchCiv.currentTechnologyName()
        if (currentTech != null) {
            if (BattlefieldHud.enabled) {
                val tech = worldScreen.gameInfo.ruleset.technologies[currentTech]!!
                val icon = ImageGetter.getTechIconPortrait(tech.name, BattlefieldHud.points(22f))
                val turns = researchCiv.turnsToTech(currentTech)
                techButtonHolder.actor = battlefieldTechButton(currentTech.tr(true), icon, "ui-tech-current").apply {
                    // Keep the turn count visible even when a long technology name is ellipsized.
                    add("$turns${Fonts.turn}".toLabel(fontSize = BattlefieldHud.font(TextRole.Supporting)))
                        .padLeft(BattlefieldHud.points(3f))
                }
                return
            }
            val innerButton = TechButton(currentTech, researchCiv.getCiv().tech)
            innerButton.setButtonColor(ExpeditionBlueTheme.color(colorFromRGB(7, 46, 43), ExpeditionBlueTheme.panel))
            techButtonHolder.actor = innerButton
            val turnsToTech = researchCiv.turnsToTech(currentTech)
            innerButton.text.setText(currentTech.tr(true))
            innerButton.turns.setText(turnsToTech + Fonts.turn)
        } else {
            val canResearch = researchCiv.canResearchTech()
            if (canResearch || researchCiv.hasResearchedAnyTech()) {
                if (BattlefieldHud.enabled) {
                    techButtonHolder.actor = battlefieldTechButton(
                        if (canResearch) "Choose tech" else "Technologies",
                        ImageGetter.getStatIcon("Science"),
                        if (canResearch) "ui-tech-pick" else "ui-tech-overview",
                        needsResearch = canResearch
                    )
                    return
                }
                val text = if (canResearch) "{Pick a tech}!" else "Technologies"
                pickTechLabel.setText(text.tr())
                techButtonHolder.actor = pickTechButton
            }
        }
    }

    /** All map research states share one compact geometry and the holder's existing activation. */
    private fun battlefieldTechButton(text: String, icon: Actor, buttonName: String, needsResearch: Boolean = false) =
        IconTextButton(text, BattlefieldGeometry.icon(icon), BattlefieldHud.font(TextRole.Supporting), labelFontSize = UiTypography.size(TextRole.Control)).apply {
            name = buttonName
            BattlefieldGeometry.fitControl(this)
            label.wrap = false
            label.setEllipsis(true)
            if (needsResearch) label.color = ExpeditionBlueTheme.gold
            labelCell.minWidth(0f).growX()
            iconCell.size(BattlefieldHud.points(22f)).padRight(BattlefieldHud.points(5f))
            pad(BattlefieldHud.points(6f))
            touchable = Touchable.disabled // The holder opens the technology picker in every state.
        }

    private fun updateUndoButton() {
        // Don't show the undo button if there is no action to undo
        if (worldScreen.canUndo()) {
            undoButtonHolder.touchable = Touchable.enabled
            undoButtonHolder.actor = undoButton
        } else {
            undoButtonHolder.touchable = Touchable.disabled
            undoButtonHolder.actor = null
        }
    }

    private fun updatePolicyButton() {
        // Don't show policies until they become relevant
        if (viewingCiv.hasAdoptedPolicies() || viewingCiv.canAdoptPolicy()) {
            policyButtonHolder.touchable = Touchable.enabled
            policyButtonHolder.actor = policyScreenButton
        } else {
            policyButtonHolder.touchable = Touchable.disabled
            policyButtonHolder.actor = null
        }
    }

    private fun updateDiplomacyButton(): Boolean {
        return if (worldScreen.gameInfo.endless != null || viewingCiv.isDefeated() || viewingCiv.isSpectator()
                || viewingCiv.getKnownCivs().none { it != viewingCiv && !it.isBarbarian() }
        ) {
            diplomacyButtonHolder.touchable = Touchable.disabled
            diplomacyButtonHolder.actor = null
            false
        } else {
            diplomacyButtonHolder.touchable = Touchable.enabled
            diplomacyButtonHolder.actor = diplomacyButton
            true
        }
    }

    private fun updateEspionageButton() {
        if (worldScreen.selectedCiv.espionageManager.spyList.isEmpty()) {
            espionageButtonHolder.touchable = Touchable.disabled
            espionageButtonHolder.actor = null
        } else {
            espionageButtonHolder.touchable = Touchable.enabled
            espionageButtonHolder.actor = espionageButton
        }
    }

    private fun handleUndo() {
        undoButton.disable()
        worldScreen.restoreUndoCheckpoint()
    }

    override fun act(delta: Float) = super.act(delta)
    override fun draw(batch: Batch?, parentAlpha: Float) = super.draw(batch, parentAlpha)
    override fun hit(x: Float, y: Float, touchable: Boolean): Actor? = super.hit(x, y, touchable)
}
