package com.unciv.models.skins

import com.badlogic.gdx.Application.ApplicationType
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.scenes.scene2d.ui.Button.ButtonStyle
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox.CheckBoxStyle
import com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane.ScrollPaneStyle
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox.SelectBoxStyle
import com.badlogic.gdx.scenes.scene2d.ui.Slider.SliderStyle
import com.badlogic.gdx.scenes.scene2d.ui.Skin
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.badlogic.gdx.scenes.scene2d.utils.BaseDrawable
import com.badlogic.gdx.scenes.scene2d.utils.Drawable
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable
import com.badlogic.gdx.utils.Json
import com.badlogic.gdx.utils.JsonValue
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.ui.images.ImageGetter

/** iOS default UI only. Explicit mod skin configs and gameplay colours keep their own meaning. */
object ExpeditionBlueTheme {
    val background = Color.valueOf("17343F")
    val panel = Color.valueOf("234551")
    val raised = Color.valueOf("2D5461")
    val hero = Color.valueOf("295361")
    val gold = Color.valueOf("E2C893")
    val primaryInk = Color.valueOf("253D43")
    val text = Color.valueOf("F3F3EA")
    val muted = Color.valueOf("BDD0D4")
    val border = Color.valueOf("466370")
    val controlBorder = Color.valueOf("8CAAB4")
    val unexplored = Color.valueOf("243E46")
    val success = Color.valueOf("AAC9B3")
    val danger = Color.valueOf("F0B3AA")
    val dangerSurface = Color.valueOf("603D43")

    fun isDefaultSkin(skin: String) = com.unciv.ui.components.widgets.UiNavigation.isMobile &&
        skin == Constants.defaultSkin && SkinCache[skin] == null

    val enabled get() = com.unciv.ui.components.widgets.UiNavigation.isMobile &&
        (!UncivGame.isCurrentInitialized() || isDefaultSkin(UncivGame.Current.settings.skin))

    fun color(original: Color, themed: Color) = if (enabled) themed else original

    fun labelColor(original: Color) = if (!enabled) original else when (original) {
        Color.WHITE -> text
        Color.LIGHT_GRAY, Color.GRAY -> muted
        Color.RED -> danger
        Color.GREEN, Color.FOREST -> success
        Color.GOLD -> gold
        else -> original
    }

    fun config() = SkinConfig().apply {
        baseColor = panel.cpy()
        clearColor = background.cpy()
        fun variants(tint: Color, vararg paths: String) {
            for (path in paths) skinVariants[path] = SkinConfig.SkinElement(tint = tint)
        }
        variants(background, "LoadGameScreen/Background", "SaveGameScreen/Background",
            "MultiplayerScreen/Background", "MultiplayerLobbyScreen/Background",
            "TechPickerScreen/Background", "PlayerReadyScreen/Background", "DiplomacyScreen/LeftSide")
        variants(panel, "General/Popup/InnerTable", "General/AnimatedMenu", "General/TabbedPager",
            "General/ExpanderTab", "General/Tooltip", "General/DisabledBox", "LanguagePickerScreen/LanguageTable",
            "LoadGameScreen/SavesPanel", "LoadGameScreen/DetailsPanel", "LoadGameScreen/CloudPanel",
            "SaveGameScreen/SavesPanel", "SaveGameScreen/EditorPanel", "TechPickerScreen/BottomTable",
            "MultiplayerScreen/Header", "MultiplayerScreen/ServerBar", "MultiplayerScreen/GamesPanel",
            "MultiplayerScreen/DetailsPanel", "MultiplayerScreen/InfoItem", "MultiplayerLobbyScreen/GameSummary",
            "NewGameScreen/RoomHeader", "NewGameScreen/BottomTable", "NewGameScreen/TopTable",
            "NewGameScreen/PlayerPickerTable", "NewGameScreen/GameOptionsTable", "NewGameScreen/MapOptionsTable",
            "WorldScreen/TopBar/StatsTable", "WorldScreen/TopBar/ResourceTable", "WorldScreen/TopBar/LeftAttachment",
            "WorldScreen/TopBar/RightAttachment", "WorldScreen/UnitTable", "WorldScreen/BattleTable",
            "WorldScreen/TileInfoTable", "WorldScreen/Notification", "WorldScreen/TutorialTaskTable",
            "WorldScreen/PickTechButton", "WorldScreen/Minimap/Background", "DiplomacyScreen/RightSide",
            "CityScreen/CityStatsTable/InnerTable", "CityScreen/CityScreenTileTable/InnerTable",
            "CityScreen/ConstructionInfoTable/Background", "CityScreen/ConstructionInfoTable/SelectedConstructionTable",
            "CityScreen/CityConstructionTable/ConstructionsQueueTable", "CityScreen/CityConstructionTable/AvailableConstructionsTable",
            "CityScreen/CityConstructionTable/QueueEntry", "CityScreen/CityConstructionTable/Header",
            "TechPickerScreen/TechButtonIconsOutline", "WorldScreenMusicPopup/TrackList/Up")
        variants(raised, "General/KeyCapturingButton", "AnimatedMenu/Button",
            "CivilopediaScreen/EntryButton", "LoadGameScreen/Header", "SaveGameScreen/Header",
            "NewGameScreen/PlayerPickerTable/PlayerTable", "CityScreen/CityConstructionTable/QueueEntrySelected",
            "CityScreen/CityConstructionTable/PickConstructionButton", "CityScreen/CityConstructionTable/PickConstructionButtonSelected",
            "CityScreen/CitizenManagementTable/ResetCell", "CityScreen/CitizenManagementTable/AvoidCell",
            "CityScreen/CitizenManagementTable/FocusCell", "WorldScreenMusicPopup/TrackList/Down",
            "WorldScreenMusicPopup/TrackList/Over", "OverviewScreen/NotificationLog/HighlightColor1",
            "OverviewScreen/NotificationLog/HighlightColor2")
        variants(border, "WorldScreen/Minimap/Border", "CityScreen/CityStatsTable/Background",
            "CityScreen/CityScreenTileTable/Background")
        skinVariants["General/Popup/Background"] = SkinConfig.SkinElement(tint = background, alpha = .72f)
        skinVariants["General/Tooltip"] = SkinConfig.SkinElement(tint = panel, foregroundColor = text)
        variants(gold, "TechPickerScreen/ResearchedTechColor", "TechPickerScreen/ResearchedFutureTechColor")
        variants(raised, "TechPickerScreen/CurrentTechColor", "TechPickerScreen/QueuedTechColor")
        variants(hero, "TechPickerScreen/ResearchableTechColor")
        variants(success, "WorldScreen/NextTurn/ProgressColor")
        variants(border, "WorldScreen/NextTurn/ProgressBar")
        for (name in listOf("ButtonBGPickable", "ButtonBGNotPickable", "ButtonBGAdopted",
            "BranchBGNotAdopted", "BranchBGAdopted")) variants(panel, "PolicyScreen/Colors/$name")
        for (name in listOf("ButtonBGPickableSelected", "ButtonBGNotPickableSelected", "ButtonBGAdoptedSelected",
            "BranchHeaderBG")) variants(raised, "PolicyScreen/Colors/$name")
        variants(raised, "PolicyScreen/Colors/BranchBGCompleted")
        variants(gold, "PolicyScreen/Colors/ButtonIconAdopted",
            "PolicyScreen/Colors/ButtonIconAdoptedSelected", "PolicyScreen/Colors/BranchLabelAdopted")
        variants(text, "PolicyScreen/Colors/ButtonIconPickable", "PolicyScreen/Colors/ButtonIconPickableSelected",
            "PolicyScreen/Colors/BranchLabelPickable")
        variants(muted, "PolicyScreen/Colors/ButtonIconNotPickable", "PolicyScreen/Colors/ButtonIconNotPickableSelected",
            "PolicyScreen/Colors/BranchLabelNotPickable")
    }

    /** Resolve colours before Skin.json builds its drawables, so all controls share the palette. */
    fun createSkin(): Skin = object : Skin() {
        override fun getJsonLoader(skinFile: FileHandle): Json {
            val loader = super.getJsonLoader(skinFile)
            val original = loader.getSerializer(Color::class.java)
            val colors = mapOf("color" to raised, "highlight" to hero, "pressed" to panel,
                "disabled" to background, "gray" to muted, "dark-gray" to text, "white" to text,
                "selection" to raised, "positive" to gold, "negative" to dangerSurface,
                "negative-highlight" to dangerSurface, "negative-selected" to dangerSurface,
                "promotion-default" to panel, "promotion-selected" to hero, "promotion-path" to raised,
                "promotion-promoted" to gold, "promotion-promoted-text" to primaryInk,
                "promotion-pickable" to raised, "promotion-prerequisite" to border,
                "promotion-grouplines" to border, "mods-scroll-bg" to panel)
            loader.setSerializer(Color::class.java, object : Json.ReadOnlySerializer<Color>() {
                override fun read(json: Json, data: JsonValue, type: Class<*>?): Color =
                    if (!data.isString && data.name in colors) colors.getValue(data.name).cpy()
                    else original.read(json, data, type)
            })
            return loader
        }
    }

    fun finishSkin(skin: Skin) {
        val outline = skin.newDrawable("RoundedEdgeRectangle", controlBorder)
        val disabled = outlinedControl(skin.getDrawable("button-disabled"), outline)
        fun finishButtons(style: ButtonStyle, edge: Drawable) {
            style.up = outlinedControl(style.up, edge)
            style.down = outlinedControl(style.down, edge)
            style.over = outlinedControl(style.over, edge)
            style.checked = outlinedControl(skin.getDrawable("button-c"), edge)
            style.disabled = disabled
        }
        finishButtons(skin.get(ButtonStyle::class.java), outline)
        for (entry in skin.getAll(TextButtonStyle::class.java)) {
            val style = entry.value
            val edge = when (entry.key) {
                "positive" -> skin.newDrawable("RoundedEdgeRectangle", gold)
                "negative" -> skin.newDrawable("RoundedEdgeRectangle", danger)
                else -> outline
            }
            finishButtons(style, edge)
            style.disabledFontColor = muted
            style.checkedFontColor = gold
        }
        skin.get("positive", TextButtonStyle::class.java).apply {
            fontColor = primaryInk
            downFontColor = text
            overFontColor = text
        }
        skin.get("disabled", TextButtonStyle::class.java).fontColor = muted
        skin.get(CheckBoxStyle::class.java).apply {
            fontColor = text
            overFontColor = text
            downFontColor = text
            checkboxOff = skin.newDrawable("Checkbox", muted)
            checkboxOn = skin.newDrawable("Checkbox-pressed", gold)
            checkboxOver = checkboxOff
            checkboxOnOver = checkboxOn
            checkboxOnDisabled = skin.newDrawable("Checkbox-pressed", border)
            checkboxOffDisabled = skin.newDrawable("Checkbox", border)
            checkedFontColor = gold
            checkedOverFontColor = gold
            checkedDownFontColor = gold
        }
        for (style in skin.getAll(ScrollPaneStyle::class.java).values()) {
            if (com.unciv.ui.components.widgets.UiNavigation.isMobile) {
                // Touch scrolling remains available, but mobile pages do not show scrollbars.
                style.hScroll = null
                style.hScrollKnob = null
                style.vScroll = null
                style.vScrollKnob = null
            } else {
                style.hScrollKnob = skin.newDrawable("Scrollbar", border)
                style.vScrollKnob = style.hScrollKnob
            }
        }
        for (style in skin.getAll(SliderStyle::class.java).values()) {
            style.background = skin.newDrawable("Rectangle", border)
            style.knobBefore = skin.newDrawable("Rectangle", gold)
            style.knob = skin.newDrawable("Circle", gold)
            style.knobOver = style.knob
            style.knobDown = style.knob
        }
        skin.get(ListStyle::class.java).fontColorSelected = gold
        skin.get(SelectBoxStyle::class.java).apply {
            background = selectBackground(skin, raised)
            backgroundOver = selectBackground(skin, hero)
            backgroundOpen = selectBackground(skin, panel)
            backgroundDisabled = selectBackground(skin, ExpeditionBlueTheme.background)
            disabledFontColor = muted
        }
    }

    /** Preserve control padding and touch geometry while keeping its edge visible on panels. */
    fun outlinedControl(surface: Drawable, outline: Drawable): Drawable = object : BaseDrawable(surface) {
        override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
            outline.draw(batch, x, y, width, height)
            surface.draw(batch, x + 2f, y + 2f, width - 4f, height - 4f)
        }
    }

    private fun selectBackground(skin: Skin, color: Color): Drawable {
        val surface = outlinedControl(skin.newDrawable("RoundedEdgeRectangle", color),
            skin.newDrawable("RoundedEdgeRectangle", controlBorder))
        val arrow = ImageGetter.getDrawable("OtherIcons/ArrowRight").tint(text) as TransformDrawable
        return object : BaseDrawable(surface) {
            init { rightWidth += 26f }
            override fun draw(batch: Batch, x: Float, y: Float, width: Float, height: Float) {
                surface.draw(batch, x, y, width, height)
                arrow.draw(batch, x + width - 26f, y + height / 2f - 8f, 8f, 8f, 16f, 16f, 1f, 1f, -90f)
            }
        }
    }
}
