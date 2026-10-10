package com.unciv.logic.campaign

enum class CampaignSyncState { LocalOnly, Pending, Syncing, Synced, NoAccount, Restricted, AccountChanged, WaitingForNetwork, Failed }

data class CampaignSyncStatus(
    val state: CampaignSyncState = CampaignSyncState.LocalOnly,
    val lastSuccessfulSyncAt: Long = 0L,
    /** Opaque identity captured by the confirmation dialog, never displayed. */
    val accountToConfirm: String? = null,
)

interface CampaignProgressSync {
    val status: CampaignSyncStatus
    fun onProgressSaved()
    /** Nonblocking. Only an explicit, confirmed import may change the bound account. */
    fun requestSync(importAccount: String? = null)
    fun addStatusListener(listener: (CampaignSyncStatus) -> Unit)
    fun removeStatusListener(listener: (CampaignSyncStatus) -> Unit)
    fun close()

    object None : CampaignProgressSync {
        override val status = CampaignSyncStatus()
        override fun onProgressSaved() = Unit
        override fun requestSync(importAccount: String?) = Unit
        override fun addStatusListener(listener: (CampaignSyncStatus) -> Unit) = Unit
        override fun removeStatusListener(listener: (CampaignSyncStatus) -> Unit) = Unit
        override fun close() = Unit
    }
}
