package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.translations.TranslationFileReader
import com.unciv.models.translations.getPlaceholderText
import com.unciv.models.translations.squareBraceRegex
import com.unciv.models.translations.tr
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class MultiplayerLobbyLocalizationTest {
    private fun translations(language: String) =
        TranslationFileReader.read(Gdx.files.internal("jsons/translations/$language.properties"))

    @Test
    fun selectedLanguagesCoverRoomAndGameListMessagesWithoutLosingParameters() {
        for (language in languages) {
            val entries = translations(language)
            val keyCounts = Gdx.files.internal("jsons/translations/$language.properties").readString("UTF-8")
                .lineSequence().filter { !it.startsWith('#') && it.contains(" = ") }
                .map { it.substringBefore(" = ").getPlaceholderText() }.groupingBy { it }.eachCount()
            for (source in messages) {
                assertEquals("$language has conflicting entries for $source", 1, keyCounts[source.getPlaceholderText()])
                val translated = entries[source]
                assertNotNull("$language is missing $source", translated)
                assertFalse("$language has blank $source", translated.isNullOrBlank())
                assertEquals("$language changed parameters in $source",
                    squareBraceRegex.findAll(source).map { it.value }.toList().sorted(),
                    squareBraceRegex.findAll(translated!!).map { it.value }.toList().sorted())
            }
        }
    }

    @Test
    fun playerNumbersAndReadyCountsUseTheSelectedLanguageAtRuntime() {
        for (language in languages) {
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { this.language = language }
            val entries = translations(language)
            UncivGame.Current.translations.createTranslations(language, entries, noDiacritics = true)
            assertEquals(entries.getValue("Player [number]").replace("[number]", "2"),
                "Player [2]".tr(hideIcons = true))
            val source = "[\$joined]/[\$total] joined · [\$ready]/[\$readyTotal] ready"
            assertEquals(entries.getValue(source).replace("[\$joined]", "2")
                .replace("[\$total]", "3").replace("[\$readyTotal]", "3").replace("[\$ready]", "1"),
                "[2]/[3] joined · [1]/[3] ready".tr(hideIcons = true))
            assertEquals(entries.getValue("Room is full"), "Room is full".tr(hideIcons = true))
            assertEquals(entries.getValue("[count] games").replace("[count]", "3"),
                "[3] games".tr(hideIcons = true))
            assertEquals(entries.getValue("Remaining [duration]").replace("[duration]", "5"),
                "Remaining [5]".tr(hideIcons = true))
            assertEquals(entries.getValue("Last updated [duration] ago").replace("[duration]", "5"),
                "Last updated [5] ago".tr(hideIcons = true))
        }
    }

    @Test
    fun chineseRoomCopyDoesNotFallBackToEnglish() {
        UncivGame.Current = UncivGame()
        UncivGame.Current.settings = GameSettings().apply { language = "Simplified_Chinese" }
        UncivGame.Current.translations.createTranslations("Simplified_Chinese",
            translations("Simplified_Chinese"), noDiacritics = true)
        assertEquals("玩家 2", "Player [2]".tr(hideIcons = true))
        assertEquals("添加玩家", "Add player".tr(hideIcons = true))
        assertEquals("房间已满", "Room is full".tr(hideIcons = true))
        assertEquals("正在开始游戏…", "Starting game...".tr(hideIcons = true))
        assertEquals("点击文明头像选择文明，然后点击准备。",
            "Choose your civilization, then get ready.".tr(hideIcons = true))
    }

    companion object {
        private val languages = listOf(
            "English", "Simplified_Chinese", "Traditional_Chinese", "Spanish", "French", "German", "Brazilian_Portuguese", "Russian", "Japanese", "Korean",
        )
        private val messages = listOf(
            "Add player manually",
            "Manually added",
            "Mark ready",
            "Edit manual player",
            "Confirm this player is ready to play. Their app is not connected to this room.",
            "Paste the Player ID sent by the Android player. After starting, send them the Game ID.",
            "[\$filled]/[\$total] seats filled · [\$ready]/[\$readyTotal] ready",
            "This player was added manually. Ask the host to remove them before joining by invitation.",
            "Add player",
            "Player [number]",
            "Game ID or invitation link",
            "Multiplayer",
            "Asynchronous turn-based · Download the latest save when it's your turn to continue playing",
            "My multiplayer games",
            "Current selection",
            "Current server:",
            "Create room",
            "Join by invitation",
            "Copy my Player ID",
            "How multiplayer works",
            "Create a room here, then invite players to join",
            "Refresh all games",
            "Refreshing...",
            "Some games failed to refresh",
            "Refresh complete",
            "Connection not checked",
            "[count] games",
            "Current turn",
            "This turn has lasted",
            "My civilization",
            "Time remaining",
            "No game selected",
            "Select a game from the list to see its details.",
            "No mods",
            "Your turn",
            "Waiting for opponent",
            "Refresh failed",
            "Unavailable",
            "Game details are unavailable.",
            "Refresh failed. Showing the latest saved details.",
            "Last updated [duration] ago",
            "Remaining [duration]",
            "Download latest save and enter",
            "View latest game state",
            "Share game",
            "Copy invitation link",
            "Game invitation copied. Send it to another iOS player.",
            "Delete local record",
            "Are you sure you want to delete this local multiplayer record?",
            "Retry pending action",
            "After resigning, this civilization will be permanently controlled by AI.",
            "Join by invitation or Game ID",
            "Paste an invitation to join a waiting room, or a Game ID to add an existing game.",
            "Paste the Game ID or invitation link",
            "Paste ID or link",
            "Local display name (optional)",
            "For example: Weekend game",
            "Join or add game",
            "Play with iOS players",
            "1. Tap Create room, choose the rules and map, then share the invitation.",
            "2. Other iOS players open the invitation or paste it in Join by invitation, tap their civilization portrait to choose, then tap Ready.",
            "3. The host is ready automatically. When everyone is ready, the host taps Start game. No Player ID or Game ID exchange is needed.",
            "Play with Android players",
            "1. The Android player copies their Player ID from Multiplayer and sends it to the iOS host.",
            "2. In an empty player seat, the host taps Add player manually, pastes the Player ID, taps the portrait to choose a civilization, then taps Mark ready.",
            "3. After starting, the Game ID is copied automatically. Send it to the Android player to add the game in Multiplayer.",
            "Android players join the started game by Game ID; they do not join the waiting room through its invitation link.",
            "If Android hosts, send them your Player ID, then paste their Game ID in Join by invitation.",
            "After the game starts",
            "Multiplayer is asynchronous and turn-based. When it is your turn, download the latest save, play, then end your turn to upload it.",
            "All players must use the same game server and compatible game versions and rulesets.",
            "For chat, set or check your server password in Options → Multiplayer. Saving it reconnects chat automatically without leaving the game.",
            "Multiplayer room",
            "Continue room",
            "Connecting to room...",
            "Share invitation",
            "Copy invitation",
            "Invite friends",
            "Invitation copied. Send it to your friends.",
            "Players",
            "Host",
            "Host · You",
            "Waiting for player",
            "Your friend chooses their civilization",
            "[\$joined]/[\$total] joined · [\$ready]/[\$readyTotal] ready",
            "Not ready",
            "Ready",
            "Cancel ready",
            "Choose civilization",
            "Choose your civilization, then get ready.",
            "Waiting for other players to get ready.",
            "Invite friends to fill the empty seats.",
            "Everyone is ready. You can start the game.",
            "Everyone is ready. Waiting for the host to start.",
            "Room and player information",
            "Game setup",
            "Syncing room settings...",
            "Close room",
            "Leave room",
            "Leaving closes the room for everyone.",
            "Leave this waiting room?",
            "Kick",
            "Remove this player from the room?",
            "Rejoin room",
            "You have left this room or were removed. Open the invitation to rejoin.",
            "Retry start",
            "Starting game...",
            "Game started",
            "Starting game. Waiting for the host to finish uploading...",
            "Host closed the room",
            "Host timed out",
            "Leave the current waiting room and join this invitation?",
            "Leave and join",
            "Save the current game and open this room invitation?",
            "Save and open",
            "Connection failed. Retrying...",
            "Room request failed. Please retry.",
            "The room API is not configured in this build.",
            "The room API must be updated before creating rooms.",
            "The invitation game server differs from this room.",
            "The game server differs from the room API configuration.",
            "The original start save is unavailable. Recover the previous upload before continuing.",
            "The room rules differ from this app. Update the app or server.",
            "Rooms support built-in rules only",
            "Invalid game setup",
            "Missing game parameters",
            "Missing map parameters",
            "Rooms require generated maps without mods",
            "Invalid room configuration",
            "Missing players",
            "Invalid room capacity",
            "Seat type differs from setup",
            "Rooms require a fixed player count without spectators",
            "Invalid player or creation ID",
            "Civilization differs from setup",
            "Room not found",
            "Room is busy. Please retry.",
            "Waiting for host to recover the original save",
            "Room is no longer waiting",
            "Only the host can do this",
            "Civilization is unavailable",
            "Civilization is already selected",
            "Room settings changed. Refresh and retry.",
            "Invalid room seats",
            "Cannot remove or replace the host",
            "Remove the player before changing an occupied seat",
            "Invalid player ID",
            "Room is full",
            "This seat has changed. Refresh and retry.",
            "Cannot remove the host",
            "You can choose only your own civilization",
            "Your seat changed. Refresh and retry.",
            "All human seats must be occupied and ready",
            "Start operation no longer matches this room",
            "Invalid save fingerprint",
            "Recover the original save instead of generating another one",
            "Start save fingerprint does not match",
            "The full game save has not been verified on the game server",
            "Start timed out before upload was registered",
            "Unknown room operation",
            "The room connection version does not match. Please try again later.",
            "The join result is unavailable. Open the invitation to join again.",
            "This room request conflicts with a previous request. Please retry.",
            "Leave the current waiting room before joining another.",
            "A player disconnected. Wait for them to reconnect and get ready, or remove them.",
            "This room connection was replaced. Reopen the room to continue.",
            "Invalid room request",
            "Room request is too large",
        )
    }
}
