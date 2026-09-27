package com.bouncepay.store

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    /** Where the bridge posts packets. Printed by the mock bank on start-up. */
    val bankUrl: String = "http://192.168.1.100:4000",

    /**
     * Behave as if there were no network, even when there is.
     *
     * On demo day this is far more reliable than airplane mode, which on many
     * handsets also switches Bluetooth off — the one radio this app needs.
     */
    val forceOffline: Boolean = false,

    /**
     * If this device is the bridge but the bank does not answer, settle with
     * the on-device bank instead. Only ever used by a bridge: a device with no
     * network always forwards, or the mesh would never be exercised.
     */
    val useFallbackBank: Boolean = true,

    /** Enrolled with the bank; its account exists and can be debited. */
    val enrolled: Boolean = false,

    /**
     * The offline wallet. Loaded while online, spent while offline.
     *
     * The bank enforces the real balance, but by then the packet has already
     * travelled the mesh. Refusing to *sign* past this limit stops a phone
     * handing out authorisations it cannot honour.
     */
    val walletPaise: Int = 0,

    /**
     * The bank's public key, pinned whenever this phone talks to the bank
     * directly. Receipts that arrive over the mesh are only believed if they
     * verify against it.
     */
    val bankPubKey: String? = null,

    /** How this phone appears to others nearby. Blank means the model name. */
    val displayName: String = "",
)

/** Persisted settings, observable so the UI updates when they change. */
class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("bouncepay", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    private fun read() = Settings(
        bankUrl = prefs.getString(KEY_BANK_URL, null) ?: Settings().bankUrl,
        forceOffline = prefs.getBoolean(KEY_FORCE_OFFLINE, false),
        useFallbackBank = prefs.getBoolean(KEY_FALLBACK, true),
        enrolled = prefs.getBoolean(KEY_ENROLLED, false),
        walletPaise = prefs.getInt(KEY_WALLET, 0),
        bankPubKey = prefs.getString(KEY_BANK_KEY, null),
        displayName = prefs.getString(KEY_NAME, null).orEmpty(),
    )

    fun update(transform: (Settings) -> Settings) = synchronized(this) {
        val next = transform(_settings.value)
        prefs.edit {
            putString(KEY_BANK_URL, next.bankUrl.trim())
            putBoolean(KEY_FORCE_OFFLINE, next.forceOffline)
            putBoolean(KEY_FALLBACK, next.useFallbackBank)
            putBoolean(KEY_ENROLLED, next.enrolled)
            putInt(KEY_WALLET, next.walletPaise)
            putString(KEY_BANK_KEY, next.bankPubKey)
            putString(KEY_NAME, next.displayName.trim().take(MAX_NAME))
        }
        _settings.value = next
    }

    /**
     * Debits the offline wallet if it can cover [amountPaise].
     * Atomic with respect to other callers, so two quick taps cannot both pass.
     */
    fun tryDebit(amountPaise: Int): Boolean = synchronized(this) {
        if (_settings.value.walletPaise < amountPaise) return false
        update { it.copy(walletPaise = it.walletPaise - amountPaise) }
        true
    }

    private companion object {
        const val KEY_BANK_URL = "bankUrl"
        const val KEY_FORCE_OFFLINE = "forceOffline"
        const val KEY_FALLBACK = "useFallbackBank"
        const val KEY_ENROLLED = "enrolled"
        const val KEY_WALLET = "walletPaise"
        const val KEY_BANK_KEY = "bankPubKey"
        const val KEY_NAME = "displayName"
        const val MAX_NAME = 24
    }
}
