package com.mythbrix.cinetor.feature.adult

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mythbrix.cinetor.CinetorApplication
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.PagedCatalog
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.findActivity
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * FLAG_SECURE reference set: any reason present (adult screen visible, adult video playing) → screenshots, screen
 * recording and the recents thumbnail are blocked. Main thread only.
 */
object SecureFlags {
    private val reasons = linkedSetOf<String>()
    private var activity: WeakReference<Activity>? = null

    val secure: Boolean get() = reasons.isNotEmpty()

    fun attach(a: Activity) {
        activity = WeakReference(a)
        apply()
    }

    fun set(reason: String, on: Boolean) {
        if (on) reasons += reason else reasons -= reason
        apply()
    }

    private fun apply() {
        val w = activity?.get()?.window ?: return
        if (reasons.isNotEmpty()) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Marks the composition as secure while it is shown. */
@Composable
fun SecureWindow(reason: String) {
    val context = LocalContext.current
    // Unique per instance: two adult screens overlap during a navigation transition.
    val key = remember(reason) { "$reason@" + Any().hashCode() }
    DisposableEffect(key, context) {
        context.findActivity()?.let(SecureFlags::attach)
        SecureFlags.set(key, true)
        onDispose { SecureFlags.set(key, false) }
    }
}

/**
 * Process-wide wiring: activity tracking for FLAG_SECURE, FLAG_SECURE while an adult item is in the
 * player, and stopping adult playback when the section is turned off.
 */
object AdultLifecycle {
    private var installed = false

    fun install(app: Application, gate: AdultGate, playback: () -> PlayerStateHolder, mainScope: CoroutineScope) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) = SecureFlags.attach(a)
            override fun onActivityStarted(a: Activity) = SecureFlags.attach(a)
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        mainScope.launch {
            playback().state
                .map { it.active && it.item?.space == Space.ADULT }
                .distinctUntilChanged()
                .collect { SecureFlags.set("adult-player", it) }
        }
        mainScope.launch {
            gate.enabled.collect { on ->
                val p = playback()
                if (!on && p.state.value.item?.space == Space.ADULT) p.close()
            }
        }
    }
}

/**
 * Wraps every adult screen: [content] under FLAG_SECURE while the section is on; a short notice
 * with a way back when it has been turned off in Settings. There is no PIN.
 */
@Composable
fun AdultGuard(gate: AdultGate, contentPadding: PaddingValues, onExit: () -> Unit, content: @Composable () -> Unit) {
    val enabled by gate.enabled.collectAsStateWithLifecycle()
    if (enabled) {
        SecureWindow("adult-screen")
        content()
    } else {
        AdultOffNotice(contentPadding, onExit)
    }
}

@Composable
private fun AdultOffNotice(contentPadding: PaddingValues, onExit: () -> Unit) {
    val tokens = Cinetor.tokens
    Box(
        Modifier.fillMaxSize().padding(contentPadding).semantics { contentDescription = "Adult Content is off" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Adult Content is off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tokens.textPrimary)
            Text(
                "Turn it on in Settings → Content & Privacy.",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
            )
            TextButton(onClick = onExit) { Text("Back to Home", color = tokens.accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Mode for the reusable 18+ setup/disable modal flow (shared by Settings and Profile/Me). */
enum class AdultSetupAction { ENABLE, DISABLE }

/**
 * Reusable 18+ on/off modal (shared by Settings and Me). On: a one-time age confirmation, no PIN.
 * Off: a plain confirmation. Keeps the exact `"I am 18 or older"` semantics for CI smoke tests.
 */
@Composable
fun AdultSetupFlowDialog(
    gate: AdultGate,
    action: AdultSetupAction?,
    onDismiss: () -> Unit,
) {
    if (action == null) return
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    var message by remember(action) { mutableStateOf<String?>(null) }
    val enabling = action == AdultSetupAction.ENABLE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (enabling) "Confirm your age" else "Turn off Adult Content?", fontWeight = FontWeight.Bold) },
        text = {
            Text(
                message ?: if (enabling) {
                    "Adult Content is strictly for adults. Confirm that you are 18 years or older to show it under Me."
                } else {
                    "It will be hidden everywhere again until you turn it back on."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        if (enabling) {
                            when (gate.enable(ageConfirmed = true)) {
                                EnableResult.Ok -> onDismiss()
                                EnableResult.KidsProfile -> message = "Not available on Kids profiles."
                                EnableResult.AgeNotConfirmed -> onDismiss()
                            }
                        } else {
                            gate.disable()
                            onDismiss()
                        }
                    }
                },
                modifier = Modifier.semantics { contentDescription = if (enabling) "I am 18 or older" else "Turn off" },
            ) {
                Text(if (enabling) "I am 18+" else "Turn off", color = tokens.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = tokens.textSecondary) }
        },
        containerColor = tokens.surfaceElevated,
    )
}

/**
 * Settings → Content: "Show 18+ section". On: confirm age (no PIN). Off: confirm.
 * Disabled (greyed) on Kids profiles.
 */
@Composable
fun AdultContentSection(gate: AdultGate) {
    val tokens = Cinetor.tokens
    val enabled by gate.enabled.collectAsStateWithLifecycle()
    val kids = !gate.canEnable
    var setupAction by remember { mutableStateOf<AdultSetupAction?>(null) }

    Text("Content", style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "Show 18+ section" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Show 18+ section", style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary)
            Text(
                if (kids) "Not available on Kids profiles" else "Shows Adult Content under Me after an 18+ confirmation",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
            )
        }
        Switch(
            checked = enabled,
            enabled = !kids,
            onCheckedChange = { on ->
                setupAction = if (on) AdultSetupAction.ENABLE else AdultSetupAction.DISABLE
            },
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent),
            modifier = Modifier.semantics { contentDescription = "Toggle 18+ section" },
        )
    }

    AdultSetupFlowDialog(
        gate = gate,
        action = setupAction,
        onDismiss = { setupAction = null },
    )
}

/**
 * Space-level wrapper used by the nav host for every Adult destination:
 * - Shows content only while the section is on, under `FLAG_SECURE` (no PIN).
 * - Uses `GlassLevel.SURFACE` exclusively (eliminating `RenderNode` self-blur crashes on Android 12–15).
 * - Browse root renders the standard section screen (hero, Top 10, shelves → Details),
 *   without leaking into global search or watch history.
 */
@Composable
fun AdultSpace(gate: AdultGate, contentPadding: PaddingValues, onExit: () -> Unit, content: @Composable () -> Unit) {
    AdultGuard(gate, contentPadding, onExit) {
        val context = LocalContext.current
        val container = remember(context) { (context.applicationContext as? CinetorApplication)?.container }
        // Warm Space.ADULT gateway shelves into the isolated adult pool once unlocked so Search tab is instant.
        LaunchedEffect(container) {
            val c = container ?: return@LaunchedEffect
            val paged = c.catalog as? PagedCatalog ?: return@LaunchedEffect
            listOf(
                adultGatewayRowId(AdultKindFilter.MOVIES, AdultSortChip.HOTTEST),
                adultGatewayRowId(AdultKindFilter.SERIES, AdultSortChip.LATEST),
            ).forEach { rowId ->
                runCatching { paged.rowPage(rowId, 1) }
            }
        }

        // Same section layout as Home / Anime / Drama: hero card, ranked Top 10, shelves, and a tap
        // opens the full Details page (stream resolved behind the skeleton).
        content()
    }
}

/** Settings column helper kept here so the Settings stub only adds one call. */
@Composable
fun AdultSettingsBlock(gate: AdultGate?) {
    if (gate == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AdultContentSection(gate) }
}
