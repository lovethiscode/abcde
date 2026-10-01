package com.unciv.app

import com.unciv.logic.multiplayer.MultiplayerGameInvite

/** URLs may arrive before startup finishes or more than once via the scene lifecycle. */
internal class IOSGameInviteQueue {
    private var pending: MultiplayerGameInvite? = null
    private var active: MultiplayerGameInvite? = null

    @Synchronized fun offer(invite: MultiplayerGameInvite) {
        if (invite != active) pending = invite
    }

    @Synchronized fun take(): MultiplayerGameInvite? {
        if (active != null) return null
        val next = pending ?: return null
        pending = null
        active = next
        return next
    }

    @Synchronized fun finish(retry: MultiplayerGameInvite? = null) {
        active = null
        if (pending == null) pending = retry
    }
}
