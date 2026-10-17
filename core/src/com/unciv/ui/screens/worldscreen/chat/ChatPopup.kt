package com.unciv.ui.screens.worldscreen.chat

import com.unciv.ui.components.fonts.UiTypography
import com.unciv.ui.components.fonts.TextRole
import com.unciv.ui.components.widgets.UiNavigation
import com.badlogic.gdx.Input
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.math.Rectangle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.ui.Button
import com.badlogic.gdx.scenes.scene2d.ui.ImageButton
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.utils.Align
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.multiplayer.chat.Chat
import com.unciv.logic.multiplayer.chat.ChatStore
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.brighten
import com.unciv.ui.components.extensions.coerceLightnessAtLeast
import com.unciv.ui.components.extensions.getContrastRatio
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.setFontSize
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.ColorMarkupLabel
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.popups.Popup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.basescreen.UncivStage
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.BattlefieldGeometry


private val civChatColorsMap = mapOf(
    "System" to Color.WHITE,
    "Server" to Color.LIGHT_GRAY,
)

/** Approximate Popup InnerTable tone — for nation-name contrast checks. */
private val chatPanelBackground = Color.DARK_GRAY

private const val NAME_MIN_CONTRAST = 3.0
private const val NAME_LIGHTEN = 0.18f
private const val NAME_MIN_LIGHTNESS = 0.55f

private fun isTooCloseToWhite(color: Color): Boolean =
    color.r > 0.82f && color.g > 0.82f && color.b > 0.82f

private fun fallbackColorForName(name: String): Color {
    val hue = ((name.hashCode() % 360) + 360) % 360
    return Color().fromHsv(hue.toFloat(), 0.70f, 0.95f)
}

/**
 * Readable nation color on the dark chat panel.
 * Prefer outer, then inner; skip near-white (body is white); else a stable hash hue.
 */
private fun pickNationNameColor(nation: Nation?, senderName: String): Color {
    val candidates = buildList {
        if (nation != null) {
            add(nation.getOuterColor())
            add(nation.getInnerColor())
        }
    }

    for (color in candidates) {
        if (isTooCloseToWhite(color)) continue
        if (getContrastRatio(chatPanelBackground, color) < NAME_MIN_CONTRAST) continue
        return color.brighten(NAME_LIGHTEN).apply { a = 1f }
    }
    for (color in candidates) {
        if (isTooCloseToWhite(color)) continue
        return color.coerceLightnessAtLeast(NAME_MIN_LIGHTNESS).apply { a = 1f }
    }
    return fallbackColorForName(senderName)
}

private fun resolveNation(gameInfo: GameInfo, senderCivName: String): Nation? =
    gameInfo.getCivilizationOrNull(senderCivName)?.nation
        ?: gameInfo.ruleset.nations[senderCivName]

/** One chat line: no bubble — nation-colored name + white body. */
fun createChatMessageLine(
    gameInfo: GameInfo,
    senderCivName: String,
    message: String,
    suffix: String? = null,
): Table {
    val row = Table()

    if (senderCivName in civChatColorsMap) {
        val line = Label(
            "${senderCivName.tr()}${if (suffix != null) " [${suffix.tr()}]" else ""}: ${message.tr()}",
            BaseScreen.skin
        ).apply {
            wrap = true
            setAlignment(Align.left)
            color = civChatColorsMap.getValue(senderCivName)
        }
        row.add(line).growX().left()
        return row
    }

    val nameColor = pickNationNameColor(resolveNation(gameInfo, senderCivName), senderCivName)
    val suffixPart = if (suffix != null) " ({$suffix})" else ""
    val nameMarkup = "#" + nameColor.toString().substring(0, 6)
    val line = ColorMarkupLabel(
        "«$nameMarkup»{$senderCivName}$suffixPart:«WHITE» $message",
        defaultColor = Color.WHITE
    ).apply {
        wrap = true
        setAlignment(Align.left)
    }
    row.add(line).growX().left()
    return row
}

class ChatPopup(
    val chat: Chat,
    private val worldScreen: WorldScreen,
) : Popup(screen = worldScreen, scrollable = Scrollability.None) {
    private val chatTable = Table(skin)
    private val scrollPane = ScrollPane(chatTable, skin)
    private val messageField = UncivTextField(hint = "Type something...")
    private val passwordReminder = Table(skin)
    private val iosScale = if (UiNavigation.isIos) UiNavigation.minimumTouchSize() / 44f else 1f
    private val chatHeaderRow = Table(skin)
    private val composer = Table(skin)
    private val inputFont by lazy {
        Fonts.fontImplementation.getBitmapFont().apply { data.setScale(UiTypography.size(TextRole.Control, iosScale).toFloat() / Fonts.ORIGINAL_FONT_SIZE) }
    }

    init {
        ChatStore.chatPopup = this
        chatTable.defaults().growX().pad(5f * iosScale).left()
        chatTable.top().left()

        /**
         * Layout:
         * |  ChatHeader | CloseButton |
         * |  ChatTable (colSpan = 2)  |
         * | MessageField | SendButton |
         */

        val chatHeader = Table(skin)
        val chatLabel = "Chat".toLabel(fontSize = UiTypography.size(TextRole.PageTitle, iosScale, legacySize = 30), alignment = Align.center)
        val chatIcon = ImageGetter.getImage("OtherIcons/Chat")

        chatHeader.add(chatIcon).size(chatLabel.height * 1.6f)
            .padRight(chatLabel.height / 3).padBottom(chatLabel.height / 4)
        chatHeader.add(chatLabel).apply { if (!UiNavigation.isIos) expandX() }

        if (UiNavigation.isIos) {
            val closeButton = UiNavigation.dismissButton().apply { onClick { close() } }
            BattlefieldGeometry.fitControl(closeButton)
            chatHeaderRow.add(chatHeader).minWidth(0f).growX().left()
            chatHeaderRow.add(closeButton).size(UiNavigation.minimumTouchSize())
            add(chatHeaderRow).colspan(2).growX().row()
        } else {
            add(chatHeader).left().pad(5f).expandX()
            add(ImageButton(ImageGetter.getImage("OtherIcons/Close").drawable).onClick {
                    ChatStore.chatPopup = null
                    close()
                }
            ).size(chatLabel.height * 1.3f).right().row()
        }

        scrollPane.setFadeScrollBars(false)
        scrollPane.setScrollingDisabled(true, false)
        scrollPane.setOverscroll(false, false)
        val sendButton = Button(skin)
        sendButton.name = "ui-chat-send"
        messageField.name = "ui-chat-message"
        if (UiNavigation.isIos) {
            innerTable.pad(8f * iosScale)
            innerTable.defaults().pad(0f)
            innerTable.getCell(chatHeaderRow).pad(0f).height(44f * iosScale)
            add(scrollPane).colspan(2).minSize(0f, 0f).prefSize(0f, 0f).grow().pad(8f * iosScale, 0f, 8f * iosScale, 0f).row()
            add(passwordReminder).colspan(2).growX().row()
            BattlefieldGeometry.fitControl(sendButton)
            sendButton.add(ImageGetter.getImage("OtherIcons/Send")).size(22f * iosScale)
            messageField.style = com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle(messageField.style).apply {
                font = inputFont
                messageFont = inputFont
            }
            composer.add(messageField).minWidth(0f).growX().height(44f * iosScale)
            composer.add(sendButton).size(44f * iosScale).padLeft(8f * iosScale)
            add(composer).colspan(2).growX().row()
        } else {
            add(scrollPane).colspan(2).size(0.5f * worldScreen.stage.width, 0.5f * worldScreen.stage.height).grow().row()
            add(passwordReminder).colspan(2).width(0.5f * worldScreen.stage.width).fillX().row()
            add(messageField).expandX().fillX()
            sendButton.add(ImageGetter.getImage("OtherIcons/Send"))
            add(sendButton).size(messageField.height * 1.2f, messageField.height).padLeft(1f).row()
        }

        populateChat()
        refreshPasswordReminder()

        sendButton.onClick { sendMessage() }

        messageField.addListener(object : InputListener() {
            override fun keyDown(event: InputEvent?, keycode: Int): Boolean {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    sendMessage()
                }
                return true
            }
        })
    }

    // Size the panel inside both the window safe area and the keyboard-visible region.
    // Only the message list flexes; header, close and composer remain directly usable.
    override fun layout() {
        if (!UiNavigation.isIos) { super.layout(); return }
        setPosition(0f, 0f)
        val safe = worldScreen.safeAreaBoundsInWorld()
        val visible = (stageToShowOn as UncivStage).lastKnownVisibleArea
        val left = maxOf(safe.x, visible.x)
        val bottom = maxOf(safe.y, visible.y)
        val right = minOf(safe.x + safe.width, visible.x + visible.width)
        val top = minOf(safe.y + safe.height, visible.y + visible.height)
        val margin = 8f * iosScale
        val panelWidth = minOf(600f * iosScale, right - left - margin * 2f).coerceAtLeast(1f)
        val panelHeight = minOf(480f * iosScale, top - bottom - margin * 2f).coerceAtLeast(1f)
        innerTable.setBounds(left + (right - left - panelWidth) / 2f,
            bottom + (top - bottom - panelHeight) / 2f, panelWidth, panelHeight)
        innerTable.validate()
    }

    override fun getPrefWidth() = if (UiNavigation.isIos) stageToShowOn.width else super.getPrefWidth()
    override fun getPrefHeight() = if (UiNavigation.isIos) stageToShowOn.height else super.getPrefHeight()

    override fun onVisibleAreaChanged(visibleArea: Rectangle) {
        if (!UiNavigation.isIos) { super.onVisibleAreaChanged(visibleArea); return }
        invalidateHierarchy()
        validate()
        scrollPane.scrollPercentY = 1f
        scrollPane.updateVisualScroll()
    }

    override fun close() {
        if (ChatStore.chatPopup === this) ChatStore.chatPopup = null
        if (stageToShowOn.keyboardFocus?.isDescendantOf(this) == true) {
            stageToShowOn.keyboardFocus = null
            Gdx.input.setOnscreenKeyboardVisible(false)
        }
        super.close()
        if (UiNavigation.isIos) inputFont.dispose()
    }

    fun refreshPasswordReminder() {
        passwordReminder.clearChildren()
        if (ChatStore.passwordRequired) {
            passwordReminder.add(
                "To use chat, set or check your password in Options → Multiplayer.".toLabel(TextRole.Body).apply {
                    wrap = true
                    if (UiNavigation.isIos) setFontSize(UiTypography.size(TextRole.Metadata, iosScale))
                }
            ).growX().padBottom(5f)
        }
    }

    fun sendMessage() {
        val message = messageField.text.trim()

        val userId = UncivGame.Current.settings.multiplayer.getUserId()
        val currentPlayerCiv = worldScreen.gameInfo.currentPlayerCiv
        val civName = if (currentPlayerCiv.playerId == userId) {
            currentPlayerCiv.civID
        } else {
            // what do I do if someone is a spectator?
            worldScreen.gameInfo.civilizations.firstOrNull { civ -> civ.playerId == userId }?.civID
                ?: "Unknown"
        }

        if (message.isNotEmpty()) {
            chat.requestMessageSend(civName, message)
            messageField.setText("")
        }
    }

    fun addMessage(
        senderCivName: String,
        message: String,
        suffix: String? = null,
        scroll: Boolean = true
    ) {
        val line = createChatMessageLine(worldScreen.gameInfo, senderCivName, message, suffix)
        if (UiNavigation.isIos) line.children.filterIsInstance<Label>().forEach { it.setFontSize(UiTypography.size(TextRole.Body, iosScale)) }
        chatTable.add(line).minWidth(0f).growX().left().row()
        if (scroll) scrollToBottom()
    }

    private fun populateChat() {
        chatTable.clearChildren()
        chat.forEachMessage { civName, message ->
            addMessage(civName, message)
        }
        ChatStore.pollGlobalMessages { civName, message ->
            addMessage(civName, message, suffix = "one time")
        }
        scrollToBottom()
    }

    private fun scrollToBottom() {
        chatTable.invalidate()
        scrollPane.layout()
        scrollPane.scrollY = 0f
        scrollPane.scrollPercentY = 1f
    }
}
