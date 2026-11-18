package com.mythbrix.cinetor.feature.adult

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import com.mythbrix.cinetor.CinetorApplication
import com.mythbrix.cinetor.core.data.AppContainer
import com.mythbrix.cinetor.core.data.Episode
import com.mythbrix.cinetor.core.data.Item
import com.mythbrix.cinetor.core.data.ItemType
import com.mythbrix.cinetor.core.data.Space
import com.mythbrix.cinetor.core.data.gateway.PagedCatalog
import com.mythbrix.cinetor.core.data.gateway.RowPage
import com.mythbrix.cinetor.core.ui.CinetorArtwork
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.player.PlayerStateHolder
import com.mythbrix.cinetor.feature.player.findActivity
import com.mythbrix.cinetor.navigation.SpaceRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Locale

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
 * - Renders visible titles underneath every video thumbnail across `For You`, `Hottest`, `Latest`, and `Top Rated`,
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

        if (isBrowseRoot && container != null) {
            AdultBrowseChrome(
                gate = gate,
                container = container,
                contentPadding = contentPadding,
            )
        } else {
            content()
        }
    }
}

private data class AdultShelfGroup(
    val title: String,
    val rowId: String,
    val items: List<Item>,
)

@Composable
private fun AdultBrowseChrome(
    gate: AdultGate,
    container: AppContainer,
    contentPadding: PaddingValues,
) {
    val tokens = Cinetor.tokens
    var sortName by rememberSaveable { mutableStateOf(AdultSortChip.FOR_YOU.name) }
    var kindName by rememberSaveable { mutableStateOf(AdultKindFilter.MOVIES.name) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<Item?>(null) }

    val selectedSort = runCatching { AdultSortChip.valueOf(sortName) }.getOrDefault(AdultSortChip.FOR_YOU)
    val selectedKind = runCatching { AdultKindFilter.valueOf(kindName) }.getOrDefault(AdultKindFilter.MOVIES)

    BackHandler(enabled = selectedItem != null || selectedSort != AdultSortChip.FOR_YOU) {
        if (selectedItem != null) {
            selectedItem = null
        } else {
            sortName = AdultSortChip.FOR_YOU.name
            filterQuery = ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (selectedSort == AdultSortChip.FOR_YOU) {
            AdultForYouView(
                container = container,
                query = filterQuery,
                onQueryChange = { filterQuery = it },
                contentPadding = contentPadding,
                onOpenItem = { item -> selectedItem = item },
            )
        } else {
            AdultSortedCatalogView(
                container = container,
                sort = selectedSort,
                kind = selectedKind,
                query = filterQuery,
                onSelectKind = { kindName = it.name },
                onQueryChange = { filterQuery = it },
                contentPadding = contentPadding,
                onOpenItem = { item -> selectedItem = item },
            )
        }

        // Thumb-friendly Liquid Glass dock above the bottom bar (SURFACE level — zero self-blur).
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (contentPadding.calculateBottomPadding() - 12.dp).coerceAtLeast(8.dp))
                .padding(horizontal = 12.dp)
                .clip(CircleShape)
                .background(tokens.surfaceElevated.copy(alpha = 0.94f))
                .glass(CircleShape, GlassLevel.SURFACE)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AdultSortChip.entries.forEach { chip ->
                val active = chip == selectedSort
                Text(
                    chip.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) tokens.onAccent else tokens.textPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (active) tokens.accent else Color.Transparent)
                        .clickable { sortName = chip.name }
                        .semantics { contentDescription = "Adult filter ${chip.label}" }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(tokens.glassFill)
                    .clickable { gate.lock() }
                    .semantics { contentDescription = "Lock 18+ session" }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = tokens.accent, modifier = Modifier.size(14.dp))
                Text("Lock", style = MaterialTheme.typography.labelMedium, color = tokens.textPrimary)
            }
        }

        selectedItem?.let { item ->
            AdultTitleDetailSheet(
                item = item,
                container = container,
                onDismiss = { selectedItem = null },
            )
        }
    }
}

@Composable
private fun AdultForYouView(
    container: AppContainer,
    query: String,
    onQueryChange: (String) -> Unit,
    contentPadding: PaddingValues,
    onOpenItem: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    val wide = LocalConfiguration.current.smallestScreenWidthDp >= 600
    var shelves by remember { mutableStateOf<List<AdultShelfGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(container, retryTick) {
        loading = true
        failed = false
        try {
            val paged = container.catalog as? PagedCatalog
            val specs = listOf(
                "Hottest Movies" to adultGatewayRowId(AdultKindFilter.MOVIES, AdultSortChip.HOTTEST),
                "Latest Movies" to adultGatewayRowId(AdultKindFilter.MOVIES, AdultSortChip.LATEST),
                "Top Rated Movies" to adultGatewayRowId(AdultKindFilter.MOVIES, AdultSortChip.TOP_RATED),
                "Latest Series" to adultGatewayRowId(AdultKindFilter.SERIES, AdultSortChip.LATEST),
            )
            val loaded = coroutineScope {
                specs.map { (title, rowId) ->
                    async {
                        val raw = runCatching {
                            if (paged != null) paged.rowPage(rowId, 1).items else container.catalog.row(rowId)
                        }.getOrDefault(emptyList())
                        val safe = raw.filter { AdultGate.isSafeAdultTitle(it.title) }.distinctBy { it.id }
                        if (safe.isEmpty()) null else AdultShelfGroup(title = title, rowId = rowId, items = safe)
                    }
                }.awaitAll().filterNotNull()
            }
            if (loaded.isNotEmpty()) {
                shelves = loaded
            } else {
                failed = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    val allPool = remember(shelves) {
        shelves.asSequence().flatMap { it.items.asSequence() }.distinctBy { it.id }.toList()
    }
    val trimmedQuery = query.trim()
    val filteredPool = remember(allPool, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            allPool
        } else {
            allPool.filter { item ->
                item.title.contains(trimmedQuery, ignoreCase = true) ||
                    item.genres.any { g -> g.contains(trimmedQuery, ignoreCase = true) }
            }
        }
    }
    val featured = remember(shelves) {
        shelves.firstOrNull()?.items?.firstOrNull { it.poster.isNotBlank() || it.backdrop.isNotBlank() }
            ?: allPool.firstOrNull()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        TextField(
            value = query,
            onValueChange = { onQueryChange(it.take(40)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.textSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear filter", tint = tokens.textSecondary)
                    }
                }
            },
            placeholder = { Text("Search private 18+ catalog…", color = tokens.textSecondary, maxLines = 1) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = tokens.accent,
                focusedTextColor = tokens.textPrimary,
                unfocusedTextColor = tokens.textPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE),
        )

        if (loading && shelves.isEmpty()) {
            LinearProgressIndicator(
                color = tokens.accent,
                trackColor = tokens.divider,
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp),
            )
        }

        when {
            shelves.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
            shelves.isEmpty() && failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Could not load 18+ catalog right now.", color = tokens.textSecondary)
                    TextButton(onClick = { retryTick += 1 }) {
                        Text("Retry", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            trimmedQuery.isNotEmpty() && filteredPool.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No matching titles found.", color = tokens.textSecondary)
            }
            else -> LazyVerticalGrid(
                columns = if (wide) GridCells.Adaptive(minSize = 148.dp) else GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 64.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (trimmedQuery.isEmpty()) {
                    if (featured != null) {
                        item(key = "adult-featured-${featured.id}", span = { GridItemSpan(maxLineSpan) }) {
                            AdultFeaturedCard(
                                item = featured,
                                onClick = { onOpenItem(featured) },
                            )
                        }
                    }

                    shelves.forEach { shelf ->
                        item(key = "shelf-${shelf.rowId}", span = { GridItemSpan(maxLineSpan) }) {
                            AdultHorizontalShelf(
                                shelf = shelf,
                                onOpenItem = onOpenItem,
                            )
                        }
                    }

                    if (allPool.isNotEmpty()) {
                        item(key = "more-for-you-header", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "All Featured Titles",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = tokens.textPrimary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                items(filteredPool, key = { "grid-${it.id}" }) { item ->
                    AdultPosterTile(
                        item = item,
                        onClick = { onOpenItem(item) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdultFeaturedCard(
    item: Item,
    onClick: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = item.title },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(188.dp)
                .clip(shape)
                .background(tokens.skeletonBase)
                .border(1.dp, tokens.glassStroke, shape),
        ) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.backdrop.ifBlank { item.poster },
                width = 380.dp,
                height = 188.dp,
                large = true,
                contentScale = ContentScale.Crop,
                fallbackTitle = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.55f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.82f),
                        ),
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "18+ FEATURED",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tokens.onAccent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.accent)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (item.rating > 0.0) {
                    Text(
                        "★ %.1f".format(Locale.US, item.rating),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFD54F),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.72f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(tokens.accent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(16.dp))
                Text("Watch", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = tokens.onAccent)
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        val sub = buildList {
            add(if (item.type == ItemType.SERIES) "Series" else "Movie")
            if (item.year > 0) add(item.year.toString())
            addAll(item.genres.take(2))
        }.joinToString(" · ")
        Text(
            text = sub,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun AdultHorizontalShelf(
    shelf: AdultShelfGroup,
    onOpenItem: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = tokens.textPrimary,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items, key = { "${shelf.rowId}:${it.id}" }) { item ->
                AdultPosterTile(
                    item = item,
                    onClick = { onOpenItem(item) },
                    modifier = Modifier.width(124.dp),
                )
            }
        }
    }
}

@Composable
private fun AdultSortedCatalogView(
    container: AppContainer,
    sort: AdultSortChip,
    kind: AdultKindFilter,
    query: String,
    onSelectKind: (AdultKindFilter) -> Unit,
    onQueryChange: (String) -> Unit,
    contentPadding: PaddingValues,
    onOpenItem: (Item) -> Unit,
) {
    val tokens = Cinetor.tokens
    val rowId = remember(kind, sort) { adultGatewayRowId(kind, sort) }
    var items by remember(rowId) { mutableStateOf<List<Item>>(emptyList()) }
    var page by remember(rowId) { mutableIntStateOf(1) }
    var hasMore by remember(rowId) { mutableStateOf(true) }
    var loading by remember(rowId) { mutableStateOf(false) }
    var failed by remember(rowId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val wide = LocalConfiguration.current.smallestScreenWidthDp >= 600

    fun fetchPage(targetPage: Int) {
        if (loading) return
        loading = true
        failed = false
        scope.launch {
            try {
                val paged = container.catalog as? PagedCatalog
                val result = if (paged != null) {
                    paged.rowPage(rowId, targetPage)
                } else {
                    val list = if (targetPage == 1) container.catalog.row(rowId) else emptyList()
                    RowPage(list, targetPage, false)
                }
                val safeItems = result.items.filter { AdultGate.isSafeAdultTitle(it.title) }
                items = (if (targetPage == 1) safeItems else items + safeItems).distinctBy { it.id }
                page = targetPage
                hasMore = result.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(rowId) {
        gridState.scrollToItem(0)
        fetchPage(1)
    }

    val nearEnd by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 9
        }
    }
    LaunchedEffect(nearEnd, hasMore, loading, page) {
        if (nearEnd && hasMore && !loading && query.isBlank()) {
            fetchPage(page + 1)
        }
    }

    val filtered = remember(items, query) {
        val q = query.trim()
        if (q.isEmpty()) items else items.filter {
            it.title.contains(q, ignoreCase = true) || it.genres.any { g -> g.contains(q, ignoreCase = true) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdultKindFilter.entries.forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { onSelectKind(k) },
                    label = { Text(k.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tokens.accent,
                        selectedLabelColor = tokens.onAccent,
                    ),
                )
            }
            TextField(
                value = query,
                onValueChange = { onQueryChange(it.take(40)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.textSecondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear filter", tint = tokens.textSecondary)
                        }
                    }
                },
                placeholder = { Text("Filter ${sort.label.lowercase()}…", color = tokens.textSecondary, maxLines = 1) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = tokens.accent,
                    focusedTextColor = tokens.textPrimary,
                    unfocusedTextColor = tokens.textPrimary,
                ),
                modifier = Modifier
                    .weight(1f)
                    .glass(RoundedCornerShape(16.dp), GlassLevel.SURFACE),
            )
        }

        if (loading && items.isEmpty()) {
            LinearProgressIndicator(
                color = tokens.accent,
                trackColor = tokens.divider,
                modifier = Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp),
            )
        }

        when {
            filtered.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
            filtered.isEmpty() && failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Could not load ${sort.label.lowercase()} titles.", color = tokens.textSecondary)
                    TextButton(onClick = { fetchPage(1) }) {
                        Text("Retry", color = tokens.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            filtered.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No matching titles in ${sort.label}.", color = tokens.textSecondary)
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = if (wide) GridCells.Adaptive(minSize = 148.dp) else GridCells.Fixed(3),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 64.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.id }) { item ->
                    AdultPosterTile(
                        item = item,
                        onClick = { onOpenItem(item) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = tokens.accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Video thumbnail card for Adult Space: renders 2:3 artwork + rating/type badge + visible 2-line title underneath.
 */
@Composable
private fun AdultPosterTile(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = Cinetor.tokens
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = item.title },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(tokens.skeletonBase)
                .border(1.dp, tokens.glassStroke, shape),
        ) {
            CinetorArtwork(
                seedKey = item.id,
                path = item.poster,
                width = 124.dp,
                height = 186.dp,
                fallbackTitle = item.title,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.type == ItemType.SERIES) {
                Text(
                    "Series",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.68f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (item.rating > 0.0) {
                Text(
                    "★ %.1f".format(Locale.US, item.rating),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFD54F),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.textPrimary,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val sub = buildList {
            if (item.year > 0) add(item.year.toString())
            item.genres.firstOrNull()?.let(::add)
        }.joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdultTitleDetailSheet(
    item: Item,
    container: AppContainer,
    onDismiss: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val myList by container.myList.collectAsStateWithLifecycle()
    var resolved by remember(item.id) { mutableStateOf(item) }
    var loadingDetail by remember(item.id) { mutableStateOf(true) }

    LaunchedEffect(item.id) {
        loadingDetail = true
        resolved = runCatching { container.catalog.item(item.id) }.getOrDefault(item)
        loadingDetail = false
    }

    val saved = resolved.id in myList
    val episodes: List<Episode> = remember(resolved) {
        resolved.seasons.flatMap { it.episodes }
    }
    val sheetShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(sheetShape)
                .background(tokens.surfaceElevated)
                .glass(sheetShape, GlassLevel.SURFACE)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .width(92.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tokens.skeletonBase)
                        .border(1.dp, tokens.glassStroke, RoundedCornerShape(12.dp)),
                ) {
                    CinetorArtwork(
                        seedKey = resolved.id,
                        path = resolved.poster,
                        width = 92.dp,
                        height = 138.dp,
                        fallbackTitle = resolved.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        resolved.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = buildList {
                        add("18+")
                        if (resolved.year > 0) add(resolved.year.toString())
                        if (resolved.rating > 0.0) add("★ %.1f".format(Locale.US, resolved.rating))
                        add(if (resolved.type == ItemType.SERIES) "Series" else "Movie")
                    }
                    Text(
                        meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.textSecondary,
                    )
                    if (resolved.genres.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            resolved.genres.take(4).forEach { g ->
                                Text(
                                    g,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.textPrimary,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(tokens.glassFill)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close details", tint = tokens.textSecondary)
                }
            }

            if (resolved.synopsis.isNotBlank()) {
                Text(
                    resolved.synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val epId = episodes.firstOrNull()?.id
                        container.playback.play(resolved, epId)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.accent,
                        contentColor = tokens.onAccent,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = { container.toggleMyList(resolved) }) {
                    Icon(
                        if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        tint = if (saved) tokens.accent else tokens.textPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (saved) "Saved" else "My List", color = tokens.textPrimary)
                }
            }

            if (loadingDetail && resolved.type == ItemType.SERIES && episodes.isEmpty()) {
                LinearProgressIndicator(color = tokens.accent, modifier = Modifier.fillMaxWidth().height(2.dp))
            } else if (episodes.isNotEmpty()) {
                Text(
                    "Episodes (${episodes.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    episodes.take(30).forEach { ep ->
                        Text(
                            "Ep ${ep.n}",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.textPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.glassFill)
                                .border(1.dp, tokens.glassStroke, RoundedCornerShape(10.dp))
                                .clickable {
                                    container.playback.play(resolved, ep.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
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
