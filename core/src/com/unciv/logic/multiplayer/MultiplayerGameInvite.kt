package com.unciv.logic.multiplayer

import com.unciv.logic.IdChecker
import com.unciv.logic.UncivShowableException
import com.unciv.logic.multiplayer.storage.validatePublicHttpsUrl
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** The invitation identifies a save, not a player seat or authentication credential. */
data class MultiplayerGameInvite(val gameId: String, val serverUrl: String?) {
    companion object {
        const val SCHEME = "unciv4ios"

        fun isCustomLink(value: String) = value.trim().startsWith("$SCHEME:", ignoreCase = true)

        fun createLink(gameId: String, serverUrl: String): String {
            val id = checkedGameId(gameId)
            val server = validatePublicHttpsUrl(serverUrl)
            return "$SCHEME://game/$id?server=${URLEncoder.encode(server, "UTF-8")}"
        }

        fun parse(value: String): MultiplayerGameInvite {
            val text = value.trim()
            val uri = try { URI(text) } catch (_: Exception) { throw invalidInvite() }
            if (uri.scheme.equals("https", ignoreCase = true) && IdChecker.isGameDeepLink(text)) {
                val id = IdChecker.checkAndReturnUuiId(text) ?: throw invalidInvite()
                return MultiplayerGameInvite(id, null) // Upstream links use the selected server.
            }
            if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals("game", ignoreCase = true)
                || uri.userInfo != null || uri.port != -1 || uri.rawFragment != null
                || !uri.rawPath.orEmpty().startsWith("/") || uri.rawPath.substring(1).contains('/')
            ) throw invalidInvite()
            val id = checkedGameId(uri.rawPath.substring(1))
            val query = uri.rawQuery ?: throw invalidInvite()
            if (!query.startsWith("server=") || query.contains('&')) throw invalidInvite()
            val server = try { URLDecoder.decode(query.substringAfter('='), "UTF-8")}
                catch (_: Exception) { throw invalidInvite() }
            return MultiplayerGameInvite(id, validatePublicHttpsUrl(server))
        }

        private fun checkedGameId(value: String): String {
            if (!value.matches(Regex("[A-Za-z0-9-]+"))) throw invalidInvite()
            return IdChecker.checkAndReturnUuiId(value) ?: throw invalidInvite()
        }

        private fun invalidInvite() = UncivShowableException("This invitation link is invalid. Ask the creator for a new game invitation.")
    }
}
