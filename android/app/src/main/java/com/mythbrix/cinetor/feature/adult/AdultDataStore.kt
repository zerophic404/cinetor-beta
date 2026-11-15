package com.mythbrix.cinetor.feature.adult

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythbrix.cinetor.core.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

private val Context.adultGateDataStore: DataStore<Preferences> by preferencesDataStore(name = "adult_gate")

/** Gate persistence: enabled flag in [SettingsStore]; salted PIN hash + attempts in their own DataStore. */
class AdultDataStore(context: Context, private val settings: SettingsStore) : AdultStore {
    private val store = context.applicationContext.adultGateDataStore

    override val enabled: Flow<Boolean> = settings.adultEnabled

    override suspend fun setEnabled(on: Boolean) = settings.setAdultEnabled(on)

    override suspend fun pin(): PinRecord? {
        val p = store.data.first()
        val hash = p[HASH] ?: return null
        val salt = p[SALT] ?: return null
        return PinRecord(hash, salt)
    }

    override suspend fun setPin(record: PinRecord?) {
        store.edit {
            if (record == null) {
                it.remove(HASH)
                it.remove(SALT)
            } else {
                it[HASH] = record.hash
                it[SALT] = record.salt
            }
        }
    }

    override suspend fun attempts(): PinAttempts {
        val p = store.data.first()
        return PinAttempts(p[FAILED] ?: 0, p[LOCKOUT] ?: 0L)
    }

    override suspend fun setAttempts(attempts: PinAttempts) {
        store.edit {
            it[FAILED] = attempts.failed
            it[LOCKOUT] = attempts.lockoutUntilMs
        }
    }

    private companion object {
        val HASH = stringPreferencesKey("pin_sha256")
        val SALT = stringPreferencesKey("pin_salt")
        val FAILED = intPreferencesKey("pin_failed")
        val LOCKOUT = longPreferencesKey("pin_lockout_until")
    }
}
