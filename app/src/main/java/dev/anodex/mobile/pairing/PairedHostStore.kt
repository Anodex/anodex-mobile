package dev.anodex.mobile.pairing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.anodex.mobile.connection.HostIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pairingDataStore: DataStore<Preferences> by preferencesDataStore("pairing")

/**
 * The paired desktop, persisted across launches.
 *
 * Only the secret is encrypted ([SecretCipher]); the host name and fingerprint are not, because
 * they are not secrets and encrypting them would only make the failure modes harder to diagnose.
 * The secret is the thing that authenticates this phone to a machine that runs commands on the
 * user's behalf.
 *
 * There is exactly one paired host at a time (§7.2). That is a deliberate simplification: it makes
 * revocation trivial and leaves one remote identity to reason about rather than a set.
 */
class PairedHostStore(
    private val context: Context,
    private val cipher: SecretCipher = SecretCipher(),
) {

    /**
     * The paired host, or null when unpaired.
     *
     * Emits null rather than throwing when the secret cannot be decrypted — which happens for real,
     * when the user adds or removes a screen lock and Android invalidates the Keystore key. The
     * app treats that as "no longer paired" and sends the user back through pairing, rather than
     * pretending to hold a credential it can no longer read.
     */
    val paired: Flow<PairedHost?> = context.pairingDataStore.data.map { prefs ->
        val id = prefs[KEY_HOST_ID] ?: return@map null
        val name = prefs[KEY_HOST_NAME] ?: return@map null
        val fingerprint = prefs[KEY_FINGERPRINT] ?: return@map null
        val secret = prefs[KEY_SECRET]?.let(cipher::decrypt) ?: return@map null

        PairedHost(
            identity = HostIdentity(id = id, displayName = name),
            secret = secret,
            certificateFingerprint = fingerprint,
            pairedNetworkId = prefs[KEY_NETWORK_ID],
            lastSeenEpochMs = prefs[KEY_LAST_SEEN]?.toLongOrNull(),
        )
    }

    /** Replaces any existing pairing. Pairing a new phone revokes the old one, by design. */
    suspend fun save(host: PairedHost) {
        val encrypted = cipher.encrypt(host.secret)
        context.pairingDataStore.edit { prefs ->
            prefs[KEY_HOST_ID] = host.identity.id
            prefs[KEY_HOST_NAME] = host.identity.displayName
            prefs[KEY_SECRET] = encrypted
            prefs[KEY_FINGERPRINT] = host.certificateFingerprint
            host.pairedNetworkId?.let { prefs[KEY_NETWORK_ID] = it }
            host.lastSeenEpochMs?.let { prefs[KEY_LAST_SEEN] = it.toString() }
        }
    }

    /** Records that the desktop was reachable, for the offline screen's "last seen". */
    suspend fun recordSeen(atEpochMs: Long) {
        context.pairingDataStore.edit { prefs -> prefs[KEY_LAST_SEEN] = atEpochMs.toString() }
    }

    /**
     * Forgets the pairing entirely.
     *
     * Clears the stored values *and* drops the Keystore key, so the ciphertext left in any backup
     * or filesystem snapshot is permanently unreadable rather than merely unreferenced.
     */
    suspend fun clear() {
        context.pairingDataStore.edit { it.clear() }
        cipher.forget()
    }

    private companion object {
        val KEY_HOST_ID = stringPreferencesKey("host_id")
        val KEY_HOST_NAME = stringPreferencesKey("host_name")
        val KEY_SECRET = stringPreferencesKey("secret")
        val KEY_FINGERPRINT = stringPreferencesKey("fingerprint")
        val KEY_NETWORK_ID = stringPreferencesKey("paired_network_id")

        // Stored as a string: DataStore has no Long key type and a lossy Double round-trip on an
        // epoch millisecond is a bug waiting to be blamed on the clock.
        val KEY_LAST_SEEN = stringPreferencesKey("last_seen_epoch_ms")
    }
}
