package com.unciv.logic.multiplayer

import com.unciv.logic.IdChecker
import com.unciv.logic.UncivShowableException
import com.unciv.logic.multiplayer.storage.validatePublicHttpsUrl
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** The invitation identifies a save, not a player seat or authentication credential. */
data class MultiplayerGameInvite(val gameId: String, val serverUrl: String?, val lobby: Boolean = false) {
    // RoboVM 2.3.26 does not provide Boolean.hashCode(boolean).
    override fun hashCode(): Int = 31 * (31 * gameId.hashCode() + (serverUrl?.hashCode() ?: 0)) + if (lobby) 1231 else 1237

    companion object {
        const val INVITATION_HOST = "jerry8870.github.io"
        const val INVITATION_PATH = "/Unciv-Wiki/invite.html"

        fun isLink(value: String) = value.trim().contains("://")

        fun createLink(gameId: String, serverUrl: String): String {
            val id = checkedGameId(gameId)
            val server = validatePublicHttpsUrl(serverUrl)
            return "https://$INVITATION_HOST$INVITATION_PATH?gameId=$id&server=${URLEncoder.encode(server, "UTF-8")}"
        }

        const val LOBBY_PATH = "/Unciv-Wiki/lobby.html"
        fun createLobbyLink(gameId: String, serverUrl: String) =
            createLink(gameId, serverUrl).replace(INVITATION_PATH, LOBBY_PATH)

        fun parse(value: String): MultiplayerGameInvite {
            val text = value.trim()
            val uri = try { URI(text) } catch (_: Exception) { throw invalidInvite() }
            if (uri.rawPath == LOBBY_PATH) {
                return parse(text.replace(LOBBY_PATH, INVITATION_PATH)).copy(lobby = true)
            }
            if (uri.scheme.equals("https", ignoreCase = true) && IdChecker.isGameDeepLink(text)) {
                val id = IdChecker.checkAndReturnUuiId(text) ?: throw invalidInvite()
                return MultiplayerGameInvite(id, null) // Upstream links use the selected server.
            }
            if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(INVITATION_HOST, ignoreCase = true)
                || uri.userInfo != null || uri.port != -1 || uri.rawFragment != null
                || uri.rawPath != INVITATION_PATH
            ) throw invalidInvite()
            val query = uri.rawQuery ?: throw invalidInvite()
            val parameters = try {
                query.split('&').map { field ->
                    val parts = field.split('=', limit = 2)
                    if (parts.size != 2) throw invalidInvite()
                    URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
                }
            } catch (_: Exception) { throw invalidInvite() }
            if (parameters.size != 2 || parameters.map { it.first }.toSet() != setOf("gameId", "server"))
                throw invalidInvite()
            val values = parameters.toMap()
            val id = checkedGameId(values.getValue("gameId"))
            val server = try { validatePublicHttpsUrl(values.getValue("server")) }
                catch (_: Exception) { throw invalidInvite() }
            return MultiplayerGameInvite(id, server)
        }

        private fun checkedGameId(value: String): String {
            if (!value.matches(Regex("[A-Za-z0-9-]+"))) throw invalidInvite()
            return IdChecker.checkAndReturnUuiId(value) ?: throw invalidInvite()
        }

        private fun invalidInvite() = UncivShowableException("This invitation link is invalid. Ask the creator for a new game invitation.")
    }
}
