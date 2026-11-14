package com.mythbrix.cinetor.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythbrix.cinetor.core.data.HistoryStorage
import kotlinx.coroutines.flow.first

private val Context.resumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_resume")

/** DataStore blob for [com.mythbrix.cinetor.core.player.ResumeRepository] (keyed profile+item+episode inside). */
class ResumeDataStore(context: Context) : HistoryStorage {
    private val store = context.applicationContext.resumeDataStore

    override suspend fun load(): String? = store.data.first()[KEY]

    override suspend fun save(json: String) {
        store.edit { it[KEY] = json }
    }

    private companion object {
        val KEY = stringPreferencesKey("resume_v1")
    }
}
