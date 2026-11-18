package com.mythbrix.cinetor.core.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mythbrix.cinetor.core.data.HistoryStorage
import kotlinx.coroutines.flow.first

private val Context.subtitleStyleDataStore: DataStore<Preferences> by preferencesDataStore(name = "subtitle_style")

/** DataStore blob for per-profile subtitle styles. */
class SubtitleStyleDataStore(context: Context) : HistoryStorage {
    private val store = context.applicationContext.subtitleStyleDataStore

    override suspend fun load(): String? = store.data.first()[KEY]

    override suspend fun save(json: String) {
        store.edit { it[KEY] = json }
    }

    private companion object {
        val KEY = stringPreferencesKey("subtitle_style_v2")
    }
}
