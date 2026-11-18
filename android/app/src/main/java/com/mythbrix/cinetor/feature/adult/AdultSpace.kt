package com.mythbrix.cinetor.feature.adult

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.mythbrix.cinetor.CinetorApplication
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.PagedCatalog
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.design.liquidGlassButton
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.findActivity
import com.mythbrix.cinetor.navigation.SpaceRoute
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
 * Process-wide wiring: background timer (re-lock after 5 min), activity tracking for FLAG_SECURE, FLAG_SECURE while
 * an adult item is in the player, and stopping adult playback when the session locks.
 */
object AdultLifecycle {
    private var installed = false

    fun install(app: Application, gate: AdultGate, playback: () -> PlayerStateHolder, mainScope: CoroutineScope) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityCreated(a: Activity, b: Bundle?) = SecureFlags.attach(a)
            override fun onActivityStarted(a: Activity) {
                SecureFlags.attach(a)
                val wasZero = started == 0
                started += 1
                if (wasZero) gate.onForeground()
            }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) gate.onBackground()
            }
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
            gate.unlocked.collect { unlocked ->
                val p = playback()
                if (!unlocked && p.state.value.item?.space == Space.ADULT) p.close()
            }
        }
    }
}

/**
 * Wraps every adult screen: PIN pad until the session is unlocked, then [content] under FLAG_SECURE.
 */
@Composable
fun AdultGuard(gate: AdultGate, contentPadding: PaddingValues, onExit: () -> Unit, content: @Composable () -> Unit) {
    val unlocked by gate.unlocked.collectAsStateWithLifecycle()
    if (unlocked) {
        SecureWindow("adult-screen")
        content()
    } else {
        PinEntryScreen(gate, contentPadding, onExit)
    }
}

/** Full-screen Liquid Glass PIN entry with shake on wrong PIN and 30 s lockout after 5 tries. */
@Composable
fun PinEntryScreen(gate: AdultGate, contentPadding: PaddingValues, onExit: () -> Unit) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var shake by remember { mutableIntStateOf(0) }
    var lockedFor by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!gate.isEnabled()) message = "The 18+ section is turned off in Settings."
        lockedFor = gate.lockoutRemainingMs()
    }
    LaunchedEffect(lockedFor > 0) {
        while (lockedFor > 0) {
            message = "Too many tries. Try again in ${(lockedFor + 999) / 1000} s"
            error = true
            delay(250)
            lockedFor = gate.lockoutRemainingMs()
        }
        if (error && message?.startsWith("Too many") == true) {
            message = null
            error = false
        }
    }
    fun submit(value: String) {
        busy = true
        scope.launch {
            when (val r = gate.unlock(value)) {
                UnlockResult.Ok -> Unit
                is UnlockResult.Wrong -> {
                    error = true
                    message = "Wrong PIN · ${r.remaining} ${if (r.remaining == 1) "try" else "tries"} left"
                    shake += 1
                }
                is UnlockResult.LockedOut -> {
                    shake += 1
                    lockedFor = gate.lockoutRemainingMs()
                }
                UnlockResult.NotEnabled -> {
                    error = true
                    message = "The 18+ section is turned off in Settings."
                }
            }
            pin = ""
            busy = false
        }
    }
    Box(
        Modifier.fillMaxSize().padding(contentPadding).semantics { contentDescription = "Adult PIN screen" },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 400.dp)
                .padding(16.dp)
                .glass(RoundedCornerShape(28.dp), GlassLevel.SURFACE)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.padding(top = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = tokens.accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "18+ RESTRICTED",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.onAccent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            PinPanel(
                title = "Enter PIN",
                subtitle = message ?: "PIN required each session · Relocks after 5 min in background",
                entered = pin.length,
                shakeKey = shake,
                error = error,
                enabled = lockedFor <= 0 && !busy,
                onDigit = { d ->
                    if (pin.length < PinHasher.PIN_LENGTH) {
                        pin += d
                        error = false
                        if (pin.length == PinHasher.PIN_LENGTH) submit(pin)
                    }
                },
                onBackspace = { pin = pin.dropLast(1) },
                onCancel = onExit,
            )
        }
    }
}

private enum class SetupStep { AGE, PIN, CONFIRM, DISABLE }

/** Mode for the reusable 18+ setup/disable modal flow (shared by Settings and Profile/Me). */
enum class AdultSetupAction { ENABLE, DISABLE }

/**
 * Reusable strict 18+ Age Confirmation + 4-digit PIN Setup / Disable modal flow.
 * Keeps exact `"I am 18 or older"` and `"PIN setup"` semantics for CI smoke tests.
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
    var step by remember(action) {
        mutableStateOf(if (action == AdultSetupAction.ENABLE) SetupStep.AGE else SetupStep.DISABLE)
    }
    var first by remember(action) { mutableStateOf("") }
    var pin by remember(action) { mutableStateOf("") }
    var message by remember(action) { mutableStateOf<String?>(null) }
    var error by remember(action) { mutableStateOf(false) }
    var shake by remember(action) { mutableIntStateOf(0) }

    when (step) {
        SetupStep.AGE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Confirm your age", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The 18+ section is strictly restricted to adults. Confirm that you are 18 years or older to set a session PIN.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { step = SetupStep.PIN },
                    modifier = Modifier.semantics { contentDescription = "I am 18 or older" },
                ) {
                    Text("I am 18+", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = tokens.textSecondary)
                }
            },
            containerColor = tokens.surfaceElevated,
        )
        SetupStep.PIN, SetupStep.CONFIRM, SetupStep.DISABLE -> Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val s = step
            Box(
                Modifier
                    .padding(16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(tokens.surfaceElevated)
                    .border(1.dp, tokens.glassStroke, RoundedCornerShape(28.dp))
                    .semantics { contentDescription = "PIN setup" },
            ) {
                PinPanel(
                    title = when (s) {
                        SetupStep.PIN -> "Set a 4-digit PIN"
                        SetupStep.CONFIRM -> "Confirm PIN"
                        else -> "Enter PIN to turn off"
                    },
                    subtitle = message ?: when (s) {
                        SetupStep.PIN -> "Required every session to open 18+ content"
                        SetupStep.CONFIRM -> "Re-enter the same 4 digits"
                        else -> "Confirm your current PIN to disable 18+"
                    },
                    entered = pin.length,
                    shakeKey = shake,
                    error = error,
                    enabled = true,
                    onDigit = { d ->
                        if (pin.length < PinHasher.PIN_LENGTH) pin += d
                        if (pin.length == PinHasher.PIN_LENGTH) {
                            val value = pin
                            pin = ""
                            when (s) {
                                SetupStep.PIN -> {
                                    first = value
                                    message = null
                                    error = false
                                    step = SetupStep.CONFIRM
                                }
                                SetupStep.CONFIRM -> scope.launch {
                                    when (gate.enable(ageConfirmed = true, pin = first, confirmPin = value)) {
                                        EnableResult.Ok -> onDismiss()
                                        EnableResult.PinMismatch -> {
                                            message = "PINs didn't match — try again"
                                            error = true
                                            shake += 1
                                            first = ""
                                            step = SetupStep.PIN
                                        }
                                        EnableResult.KidsProfile -> {
                                            message = "Not available on Kids profiles"
                                            error = true
                                        }
                                        else -> {
                                            message = "Invalid PIN"
                                            error = true
                                            shake += 1
                                        }
                                    }
                                }
                                else -> scope.launch {
                                    when (val r = gate.verify(value)) {
                                        UnlockResult.Ok -> {
                                            gate.disable()
                                            onDismiss()
                                        }
                                        is UnlockResult.Wrong -> {
                                            message = "Wrong PIN · ${r.remaining} left"
                                            error = true
                                            shake += 1
                                        }
                                        is UnlockResult.LockedOut -> {
                                            message = "Too many tries — wait 30 s"
                                            error = true
                                            shake += 1
                                        }
                                        UnlockResult.NotEnabled -> onDismiss()
                                    }
                                }
                            }
                        }
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                    onCancel = onDismiss,
                )
            }
        }
    }
}

/**
 * Settings → Content: "Show 18+ section". On: confirm age → set PIN → confirm PIN. Off: current PIN required.
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
                if (kids) "Not available on Kids profiles" else "Adds a PIN-protected Adult space to Spaces",
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
 * - Enforces PIN unlock + `FLAG_SECURE`.
 * - Uses `GlassLevel.SURFACE` exclusively (eliminating `RenderNode` self-blur crashes on Android 12–15).
 * - Browse root renders the standard section screen (hero, Top 10, shelves → Details) plus a Lock pill,
 *   without leaking into global search or watch history.
 */
@Composable
fun AdultSpace(gate: AdultGate, contentPadding: PaddingValues, onExit: () -> Unit, content: @Composable () -> Unit) {
    AdultGuard(gate, contentPadding, onExit) {
        val context = LocalContext.current
        val container = remember(context) { (context.applicationContext as? CinetorApplication)?.container }
        val backStackEntry = LocalLifecycleOwner.current as? NavBackStackEntry
        val isBrowseRoot = remember(backStackEntry, container) {
            container != null &&
                !container.deviceProfile.isTv &&
                backStackEntry?.destination?.hasRoute<SpaceRoute>() == true
        }

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
        // opens the full Details page (stream resolved behind the skeleton). Only a Lock pill is extra.
        Box(Modifier.fillMaxSize()) {
            content()
            if (isBrowseRoot) {
                val tokens = Cinetor.tokens
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 12.dp)
                        .liquidGlassButton(CircleShape, interaction = interaction)
                        .clickable(interactionSource = interaction, indication = null) { gate.lock() }
                        .semantics { contentDescription = "Lock 18+ session" }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(16.dp))
                    Text("Lock", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tokens.textPrimary)
                }
            }
        }
    }
}

/** Settings column helper kept here so the Settings stub only adds one call. */
@Composable
fun AdultSettingsBlock(gate: AdultGate?) {
    if (gate == null) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AdultContentSection(gate) }
}
