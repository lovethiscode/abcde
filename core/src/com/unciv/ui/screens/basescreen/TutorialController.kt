package com.unciv.ui.screens.basescreen

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.TutorialTrigger
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.civilopediascreen.ICivilopediaText
import yairm210.purity.annotations.Readonly


class TutorialController(screen: BaseScreen) {

    private val tutorialQueue = mutableSetOf<TutorialTrigger>()
    private var isTutorialShowing = false
    var allTutorialsShowedCallback: (() -> Unit)? = null
    private val tutorialRender = TutorialRender(screen)
    //TODO needs more testing whether this holds in all cases
    private val tutorials by screen.getCivilopediaRuleset()::tutorials

    fun showTutorial(tutorial: TutorialTrigger) {
        tutorialQueue.add(tutorial)
        showTutorialIfNeeded()
    }

    private fun removeTutorial(tutorial: TutorialTrigger) {
        isTutorialShowing = false
        tutorialQueue.remove(tutorial)
        with(UncivGame.Current.settings) {
            if (!tutorialsShown.contains(tutorial.name)) {
                tutorialsShown.add(tutorial.name)
                save()
            }
        }
        showTutorialIfNeeded()
    }

    private fun showTutorialIfNeeded() {
        if (!UncivGame.Current.settings.showTutorials) return
        val tutorial = tutorialQueue.firstOrNull()
        if (tutorial == null) {
            allTutorialsShowedCallback?.invoke()
        } else if (!isTutorialShowing) {
            isTutorialShowing = true
            val texts = getTutorial(tutorial)
            tutorialRender.showTutorial(TutorialForRender(tutorial, texts)) {
                removeTutorial(tutorial)
            }
        }
    }

    @Readonly
    private fun getTutorial(tutorial: TutorialTrigger): List<String> {
        val name = tutorial.value.replace('_', ' ').trimStart()
        return tutorials[name]?.steps ?: emptyList()
    }
}


data class TutorialForRender(val tutorial: TutorialTrigger, val texts: List<String>)

class TutorialRender(private val screen: BaseScreen) {

    fun showTutorial(tutorial: TutorialForRender, closeAction: () -> Unit) {
        showDialog(tutorial.tutorial.name, tutorial.texts, closeAction)
    }

    private fun showDialog(tutorialName: String, texts: List<String>, closeAction: () -> Unit) {
        if (texts.isEmpty()) return closeAction()

        fun showNextStep() = showDialog(tutorialName, texts.subList(1, texts.size), closeAction)
        val tutorialPopup = object : Popup(screen) {
            val stepDialog = ordinaryPresentation && tutorialName != TutorialTrigger.ContactMe.name
            init {
                if (stepDialog) {
                    // Preserve the existing outside-tap progression without disguising Next as X.
                    clickBehindToClose = true
                    onCloseCallback = ::showNextStep
                }
                if (ordinaryPresentation) {
                    val trigger = TutorialTrigger.valueOf(tutorialName)
                    val support = trigger == TutorialTrigger.ContactMe
                    val title = when (trigger) {
                        TutorialTrigger.ContactMe -> "Thank you for your support"
                        TutorialTrigger.SlowStart -> "New Game"
                        TutorialTrigger.EnemyCityNeedsConqueringWithMeleeUnit -> "Enemy City"
                        TutorialTrigger.BarbarianEncountered -> "Barbarians"
                        TutorialTrigger.OtherCivEncountered -> "Diplomacy"
                        else -> trigger.value.replace('_', ' ').trimStart()
                    }
                    addDialogTitle(title, if (support) "Developer message" else "Tutorial", dismissible = !stepDialog)
                }
            }
        }
        tutorialPopup.name = Constants.tutorialPopupNamePrefix + tutorialName

        val externalImage = ImageGetter.findExternalImage(tutorialName)
        if (externalImage != null) {
            tutorialPopup.add(ImageGetter.getExternalImage(externalImage)).row()
        }

        tutorialPopup.addGoodSizedLabel(texts[0]).row()

        if (tutorialPopup.stepDialog) {
            tutorialPopup.addButton("Continue", KeyCharAndCode.SPACE) {
                tutorialPopup.close()
                showNextStep()
            }.actor.apply {
                name = "ui-tutorial-continue"
                keyShortcuts.add(KeyCharAndCode.BACK)
            }
        } else tutorialPopup.addCloseButton(additionalKey = KeyCharAndCode.SPACE) {
            tutorialPopup.remove()
            showNextStep()
        }.actor.name = "ui-tutorial-close"
        tutorialPopup.open()
    }
}
