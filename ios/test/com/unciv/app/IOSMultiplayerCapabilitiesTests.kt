package com.unciv.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IOSMultiplayerCapabilitiesTests {
    @Test
    fun iosHidesSupportUntilMaterialsPassReview() {
        assertFalse(IOSGame().voluntarySupportAvailable)
    }

    @Test
    fun iosEnablesOnlyForegroundApiV1Multiplayer() {
        val game = IOSGame()
        val capabilities = game.platformCapabilities

        assertTrue(capabilities.onlineMultiplayer)
        assertTrue(capabilities.multiplayerChat)
        assertTrue(capabilities.onlineModManagement)
        assertTrue(capabilities.multiplayerServerRequiresHttps)
        assertTrue(capabilities.multiplayerApiV1Only)
        assertFalse(capabilities.secureMultiplayerServerPasswords)
        assertFalse(capabilities.oggAudio)
        assertTrue(capabilities.multiplayerAdvancedActions)
        assertFalse(capabilities.multiplayerFriends)
        assertTrue(capabilities.multiplayerInviteLinks)
        assertTrue(game.createMultiplayerV1Transport() is IOSMultiplayerV1Transport)
        assertTrue(game.createChatTransport() is IOSChatTransport)
    }
}
