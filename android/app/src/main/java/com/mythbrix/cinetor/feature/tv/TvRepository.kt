package com.mythbrix.cinetor.feature.tv

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mythbrix.cinetor.MainActivity
import com.mythbrix.cinetor.R
import com.mythbrix.cinetor.core.data.CatalogRepository
import com.mythbrix.cinetor.core.data.EventStatus
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the TV space shows (isolated: never fed to global search / history). */
data class TvData(
    val channels: List<Item>,
    val events: List<Item>,
    val anchorSec: Long,
    val featured: List<Item>,
) {
    val groups: List<String> get() = channelGroups(channels)
    val liveEvents: List<Item> get() = eventsFor(SportsTab.LIVE, events)
}

/** TV catalog cache (channels in guide order) + zapping lineups for the player. */
class TvRepository(
    private val catalog: CatalogRepository,
    private val anchor: suspend () -> Long?,
) {
    private val mutex = Mutex()
    private val _data = MutableStateFlow<TvData?>(null)
    val data: StateFlow<TvData?> = _data.asStateFlow()

    suspend fun load(): TvData {
        _data.value?.let { return it }
        return mutex.withLock {
            _data.value ?: run {
                val all = catalog.allItems(Space.TV)
                val channels = all.filter { it.type == ItemType.CHANNEL }.sortedBy { it.channelNumber ?: Int.MAX_VALUE }
                val events = all.filter { it.type == ItemType.EVENT }
                val heroIds = catalog.categories(Space.TV).firstOrNull()?.heroIds.orEmpty()
                val featured = heroIds.mapNotNull { id -> channels.firstOrNull { it.id == id } }.ifEmpty { channels.take(3) }
                TvData(channels, events, anchor() ?: (System.currentTimeMillis() / 1000), featured).also { _data.value = it }
            }
        }
    }

    /** Channels zap through channels; live events through live events; anything else alone. */
    fun lineupFor(item: Item): List<Item> {
        val d = _data.value ?: return listOf(item)
        return when {
            item.type == ItemType.CHANNEL -> d.channels
            item.type == ItemType.EVENT && item.status == EventStatus.LIVE -> d.liveEvents
            else -> listOf(item)
        }
    }
}

/** Favourite channels + sports reminders (SharedPreferences; tiny sets). */
class TvPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tv_space", Context.MODE_PRIVATE)
    private val _favorites = MutableStateFlow(prefs.getStringSet(KEY_FAV, emptySet()).orEmpty().toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()
    private val _reminders = MutableStateFlow(prefs.getStringSet(KEY_REM, emptySet()).orEmpty().toSet())
    val reminders: StateFlow<Set<String>> = _reminders.asStateFlow()

    fun toggleFavorite(id: String) {
        _favorites.update { if (id in it) it - id else it + id }
        prefs.edit().putStringSet(KEY_FAV, _favorites.value).apply()
    }

    fun setReminder(id: String, on: Boolean) {
        _reminders.update { if (on) it + id else it - id }
        prefs.edit().putStringSet(KEY_REM, _reminders.value).apply()
    }

    private companion object {
        const val KEY_FAV = "favorites"
        const val KEY_REM = "reminders"
    }
}

/** Kick-off reminders: inexact AlarmManager alarm → [SportsReminderReceiver] posts a local notification. */
object SportsReminders {
    const val CHANNEL_ID = "sports_reminders"
    private const val EXTRA_ID = "event_id"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"

    fun schedule(context: Context, event: Item, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.set(AlarmManager.RTC_WAKEUP, atMs, pending(context, event, create = true) ?: return)
    }

    fun cancel(context: Context, event: Item) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, event, create = false)?.let { am.cancel(it); it.cancel() }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context, event: Item, create: Boolean): PendingIntent? {
        val intent = Intent(context, SportsReminderReceiver::class.java)
            .setAction("com.mythbrix.cinetor.SPORTS_REMINDER")
            .putExtra(EXTRA_ID, event.id)
            .putExtra(EXTRA_TITLE, event.title)
            .putExtra(EXTRA_TEXT, "${event.league ?: "Sports"} · starts in 5 minutes")
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, event.id.hashCode(), intent, flags)
    }

    internal fun post(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Sports reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE) ?: "Match starting")
            .setContentText(intent.getStringExtra(EXTRA_TEXT) ?: "Starts soon")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((intent.getStringExtra(EXTRA_ID) ?: "x").hashCode(), n) }
    }
}

class SportsReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SportsReminders.post(context, intent)
}
