package com.unciv.ui.popups.options

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.unciv.GUI
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSettings.ScreenSize
import com.unciv.models.skins.SkinCache
import com.unciv.models.tilesets.TileSetCache
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivSlider
import com.unciv.ui.components.widgets.WrappableLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.ConfirmPopup
import com.unciv.ui.screens.basescreen.TextureArraySpriteBatch
import com.unciv.ui.screens.worldscreen.NotificationsScroll
import com.unciv.utils.Display
import com.unciv.utils.ScreenMode
import com.unciv.utils.ScreenOrientation

internal class DisplayTab(
    optionsPopup: OptionsPopup
): OptionsPopupTab(optionsPopup) {
    override fun lateInitialize() {
        section("Screen") {
            addScreenSizeSelectBox()
            addAutoSizeButton()
            addScreenOrientationSelectBox()
            if (com.unciv.ui.components.widgets.UiNavigation.isIos) {
                addCheckbox("Extend to fullscreen", settings::iosUseDisplayCutout) {
                    Display.setCutout(it)
                    settings.save()
                }
                add("Maps, backgrounds, and controls extend to the screen edges.".tr()
                    .let { WrappableLabel(it, optionsPopup.tabs.prefWidth - 60f, fontSize = UiTypography.size(TextRole.Supporting, legacySize = 14)).apply { wrap = true } })
                    .colspan(2).growX().row()
            }
            addScreenModeSelectBox()


            if (Gdx.app.type == Application.ApplicationType.Desktop) {
                addCheckbox("Map mouse auto-scroll", settings::mapAutoScroll, updateWorld = true) {
                    if (GUI.isWorldLoaded())
                        GUI.getMap().isAutoScrollEnabled = it
                }
                addScrollSpeedSlider()
            }
        }
        section("Graphics") {
            addTileSetSelectBox()
            addUnitSetSelectBox()
            addSkinSelectBox()
        }
        section("UI", opened = false) {
            addNotificationScrollSelect()
            addCheckbox("Show minimap", settings::showMinimap, updateWorld = true)
            addCheckbox("Show tutorials", settings.showTutorials, updateWorld = true) { settings.showTutorials = it }
            addResetTutorials()
            addCheckbox("Show long-press indicators", settings.showLongPressIndicators.toBoolean()) {
                settings.showLongPressIndicators = GameSettings.LongPressIndicatorSetting.of(it)
            }
            addCheckbox("Show zoom buttons in world screen", settings::showZoomButtons, true)
            addCheckbox("Never close popups by clicking outside", settings::forbidPopupClickBehindToClose)
            addCheckbox("Use circles to indicate movable tiles", settings::useCirclesToIndicateMovableTiles, updateWorld = true)
            addPediaUnitArtSizeSlider()
        }
        section("Visual Hints", opened = false) {
            addCheckbox("Show unit movement arrows", settings::showUnitMovements, updateWorld = true)
            addCheckbox("Show suggested city locations for units that can found cities", settings::showSettlersSuggestedCityLocations, updateWorld = true)
            addCheckbox("Show tile yields", settings::showTileYields, updateWorld = true)
            addCheckbox("Show worked tiles", settings::showWorkedTiles, updateWorld = true)
            addCheckbox("Show resources and improvements", settings::showResourcesAndImprovements, updateWorld = true)
            addCheckbox("Show pixel improvements", settings::showPixelImprovements, updateWorld = true)

            addUnitIconAlphaSlider()
        }
        section("Performance", opened = false) {
            addCheckbox("Continuous rendering", settings::continuousRendering) {
                Gdx.graphics.isContinuousRendering = it ||
                    (com.unciv.ui.components.widgets.UiNavigation.isMobile && game.screen is OptionsScreen)
            }
            val rendererString = Gdx.graphics.glVersion.rendererString
            fun fpsText() = "FPS: ${Gdx.graphics.framesPerSecond}\nRenderer: $rendererString"
            val fpsLabel = fpsText().toLabel(TextRole.Supporting).apply { wrap = true }
            fpsLabel.actions.add(Actions.forever(Actions.run { fpsLabel.setText(fpsText()) }))
            add(fpsLabel).width(optionsPopup.tabs.prefWidth - 60f).colspan(2).padTop(10f).row()

            val continuousRenderingDescription = "When disabled, saves battery life but certain animations will be suspended"
            val continuousRenderingLabel = WrappableLabel(
                continuousRenderingDescription,
                optionsPopup.tabs.prefWidth - 60f, Color.ORANGE.brighten(0.7f),UiTypography.size(TextRole.Supporting, legacySize = 14))
            continuousRenderingLabel.wrap = true
            add(continuousRenderingLabel).colspan(2).padTop(10f).row()

            addCheckbox("Enable alternative rendering", settings::alternativeRendering) { reloadWorldAndOptions() }

            val maxTextureUnitsText =
                try {
                    val maxTextureUnits = TextureArraySpriteBatch().maxTextureUnits
                    "Max texture units: $maxTextureUnits"
                } catch (e: Exception) { "Error creating TextureArraySpriteBatch" }

            val maxTextureUnitsLabel = WrappableLabel(
                maxTextureUnitsText,
                optionsPopup.tabs.prefWidth - 60f, Color.WHITE,UiTypography.size(TextRole.Supporting, legacySize = 14))
            maxTextureUnitsLabel.wrap = true
            add(maxTextureUnitsLabel).colspan(2).padTop(10f).row()


            val disableNewerRenderingDescription = "On some devices this rendering method is faster"
            val disableNewerRenderingLabel = WrappableLabel(
                disableNewerRenderingDescription,
                optionsPopup.tabs.prefWidth - 60f, Color.ORANGE.brighten(0.7f),UiTypography.size(TextRole.Supporting, legacySize = 14))
            disableNewerRenderingLabel.wrap = true
            add(disableNewerRenderingLabel).colspan(2).padTop(10f).row()

            addCheckbox("Animate Unit movement button", settings::unitMovementButtonAnimation)
            addCheckbox("Animate Unit actions menu", settings::unitActionsTableAnimation)

        }

        super.lateInitialize()
    }

    private fun Table.addScrollSpeedSlider() {
        addSlider("Map panning speed", settings::mapPanningSpeed, 0.2f, 25f, 0.2f) {
            settings.save()
            if (GUI.isWorldLoaded())
                GUI.getMap().mapPanningSpeed = settings.mapPanningSpeed
        }
    }

    private fun Table.addUnitIconAlphaSlider() {
        addSlider("Unit icon opacity", settings::unitIconOpacity, 0f, 1f, 0.1f, UncivSlider::formatPercent) {
            GUI.setUpdateWorldOnNextRender()
        }
    }

    private fun Table.addPediaUnitArtSizeSlider() {
        addSlider("Size of Unitset art in Civilopedia", settings::pediaUnitArtSize, 0f, 360f, keepValueTip = true) {
            GUI.setUpdateWorldOnNextRender() // TODO: I doubt that helps, the setting has only influence on CivilopediaScreen
        }.actor.apply {
            setSnapToValues(threshold = 60f,
                0f, 32f, 48f, 64f, 96f, 120f, 180f, 240f, 360f,
                editLabel = "{Size of Unitset art in Civilopedia}:"
            )
        }
    }

    private fun Table.addScreenModeSelectBox() {
        val modes = Display.getScreenModes()
        if (modes.isEmpty()) return
        val proxy = object {
            var value: ScreenMode
                get() = modes[settings.screenMode] ?: modes.values.first()
                set(value) { settings.screenMode = value.getId() }
        }
        addSelectBox("Screen Mode", proxy::value, modes.values) { mode, _ ->
            settings.refreshWindowSize()
            Display.setScreenMode(mode.getId(), settings)
        }
    }

    private fun Table.addScreenSizeSelectBox() {
        addSelectBox("UI Scale", settings::screenSize, ScreenSize.entries) { _, _ ->
            reloadWorldAndOptions()
        }
    }

    private fun Table.addAutoSizeButton() {
        val useSidebar = optionsPopup.useSidebar
        val recommendedSizeSelected = useSidebar && settings.screenSize == guessScreenSize()
        val autoSizeButton = (if (useSidebar) "Use recommended size" else "Autosize").toTextButton()
        autoSizeButton.onClick {
            val newSize = guessScreenSize()
            if (newSize == settings.screenSize) return@onClick
            settings.screenSize = newSize
            reloadWorldAndOptions()
        }
        if (useSidebar) {
            val description = (if (recommendedSizeSelected)
                "The recommended UI size is already selected."
            else "Choose a UI size that fits your screen.").toLabel(fontColor = OptionsPopupStyle.muted, role = TextRole.Body).apply {
                wrap = true
            }
            autoSizeButton.apply {
                isDisabled = recommendedSizeSelected
                label.setEllipsis(false)
                label.wrap = true
                labelCell.minWidth(0f).growX()
            }
            add(description).minWidth(0f).growX().left()
                .padRight(OptionsPopupStyle.points(16f)).spaceBottom(OptionsPopupStyle.points(8f))
            add(autoSizeButton).width(rightWidgetMinWidth).fillX().right()
                .minHeight(OptionsPopupStyle.points(44f)).pad(OptionsPopupStyle.points(6f))
                .spaceBottom(OptionsPopupStyle.points(8f)).row()
            return
        }
        add(autoSizeButton).center().row()

        val mode = Gdx.graphics.displayMode
        val widthInches = mode.width / Gdx.graphics.ppiX
        val heightInches = mode.height / Gdx.graphics.ppiY
        add("{Screen size}: %.1f\" x %.1f\"".format(widthInches, heightInches).toLabel(TextRole.Body)).colspan(2).row()
    }

    private fun guessScreenSize(): ScreenSize = ScreenSize.guess()

    private fun Table.addScreenOrientationSelectBox() {
        if (!Display.hasOrientation()) return
        addSelectBox("Screen orientation", settings::displayOrientation, ScreenOrientation.entries) { orientation, _ ->
            Display.setOrientation(orientation)
            if (com.unciv.ui.components.widgets.UiNavigation.isMobile) settings.save()
            else reloadWorldAndOptions()
        }
    }

    private fun Table.addTileSetSelectBox() {
        val unitSets = ImageGetter.getAvailableUnitsets()
        addSelectBox("Tileset", settings::tileSet, ImageGetter.getAvailableTilesets().asIterable()) { newValue, oldValue ->
            // Switch unitSet together with tileSet as long as one with the same name exists and both are selected
            if (oldValue == settings.unitSet && unitSets.contains(newValue)) {
                settings.unitSet = newValue
            }
            // ImageGetter ruleset should be correct no matter what screen we're on
            TileSetCache.assembleTileSetConfigs(ImageGetter.ruleset.mods)
            reloadWorldAndOptions()
        }
    }

    private fun Table.addUnitSetSelectBox() {
        val nullValue = "None".tr()
        val choices = sequenceOf(nullValue) + ImageGetter.getAvailableUnitsets()
        addSelectBox("Unitset", settings::unitSet, choices.asIterable()) { value, _ ->
            if (value == nullValue) settings.unitSet = null
            // ImageGetter ruleset should be correct no matter what screen we're on
            TileSetCache.assembleTileSetConfigs(ImageGetter.ruleset.mods)
            reloadWorldAndOptions()
        }
    }

    private fun Table.addSkinSelectBox() {
        addSelectBox("UI Skin", settings::skin, ImageGetter.getAvailableSkins().asIterable()) { _, _ ->
            // ImageGetter ruleset should be correct no matter what screen we're on
            SkinCache.assembleSkinConfigs(ImageGetter.ruleset.mods)
            reloadWorldAndOptions()
        }
    }

    private fun Table.addResetTutorials() {
        val resetTutorialsButton = "Reset tutorials".toTextButton()
        resetTutorialsButton.onClick {
            ConfirmPopup(
                stage,
                "Do you want to reset completed tutorials?",
                "Reset"
            ) {
                settings.tutorialsShown.clear()
                settings.tutorialTasksCompleted.clear()
                resetTutorialsButton.setText("Done!".tr())
                resetTutorialsButton.clearListeners()
            }.open(true)
        }
        add(resetTutorialsButton).colspan(2).left().pad(10f).row()
    }

    private fun Table.addNotificationScrollSelect() {
        addEnumAsStringSelectBox("Notifications on world screen", settings::notificationScroll, NotificationsScroll.UserSetting.entries) {
            GUI.setUpdateWorldOnNextRender()
        }
    }
}
