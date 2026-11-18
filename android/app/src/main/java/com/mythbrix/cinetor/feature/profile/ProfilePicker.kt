package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mythbrix.cinetor.core.ui.rememberSizedImage
import com.mythbrix.cinetor.design.Cinetor
import com.mythbrix.cinetor.design.GlassLevel
import com.mythbrix.cinetor.design.glass
import com.mythbrix.cinetor.feature.adult.PinHasher
import com.mythbrix.cinetor.feature.adult.PinPanel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Shape for a generated avatar. */
fun avatarShape(shape: AvatarShape): Shape = when (shape) {
    AvatarShape.CIRCLE -> CircleShape
    AvatarShape.SQUIRCLE -> RoundedCornerShape(30)
    AvatarShape.TICKET -> RoundedCornerShape(topStartPercent = 50, bottomEndPercent = 50, topEndPercent = 12, bottomStartPercent = 12)
    AvatarShape.HEXAGON -> polygon(6, 0.0)
    AvatarShape.DIAMOND -> polygon(4, 0.0)
    AvatarShape.STAR -> GenericShape { size, _ ->
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension / 2f
        val inner = outer * 0.62f
        for (i in 0 until 16) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 8
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

private fun polygon(sides: Int, rotation: Double): Shape = GenericShape { size, _ ->
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension / 2f
    for (i in 0 until sides) {
        val a = -PI / 2 + rotation + i * 2 * PI / sides
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Coloured shape + initial (no image assets). */
@Composable
fun ProfileAvatar(name: String, avatar: Int, color: Long, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val tokens = Cinetor.tokens
    val shape = avatarShape(Avatars.shape(avatar))
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color(color))
            .then(if (selected) Modifier.border(3.dp, tokens.textPrimary, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(Avatars.initial(name), color = Color.White, fontSize = (size.value * 0.42f).sp, style = MaterialTheme.typography.titleLarge)
    }
}

/** Profile avatar: the chosen character picture when one is set, else the generated shape. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val photos by ProfilePhotos.photos.collectAsState()
    val photo = photos[profile.id]
    if (photo == null) {
        ProfileAvatar(profile.name, profile.avatar, profile.color, size, modifier, selected)
    } else {
        PhotoAvatar(photo, size, modifier, selected)
    }
}

/** Round character picture avatar. */
@Composable
fun PhotoAvatar(url: String, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val tokens = Cinetor.tokens
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(tokens.surfaceElevated)
            .then(if (selected) Modifier.border(3.dp, tokens.accent, CircleShape) else Modifier),
    ) {
        AsyncImage(
            model = rememberSizedImage(url, size, size),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * "Who's watching?" — profile grid (Task 5.1). Tap = switch (PIN if set), pencil = edit, + = add (max 5).
 * [onDone] after a successful switch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePicker(
    manager: ProfileManager,
    contentPadding: PaddingValues,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onDone: () -> Unit,
    title: String = "Who's watching?",
) {
    val tokens = Cinetor.tokens
    val profiles by manager.profiles.collectAsStateWithLifecycle()
    val activeId by manager.activeId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = tokens.textPrimary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            profiles.forEach { p ->
                ProfileTile(
                    profile = p,
                    selected = p.id == activeId,
                    onClick = {
                        scope.launch {
                            when (manager.select(p.id)) {
                                SelectResult.Ok -> onDone()
                                SelectResult.PinRequired -> pinFor = p
                                else -> Unit
                            }
                        }
                    },
                    onEdit = { onEdit(p.id) },
                )
            }
            if (profiles.size < ProfileManager.MAX_PROFILES) {
                Column(
                    Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onAdd)
                        .semantics { contentDescription = "Add profile" }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(80.dp).glass(RoundedCornerShape(24.dp), GlassLevel.SURFACE),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = tokens.textPrimary, modifier = Modifier.size(36.dp)) }
                    Text("Add", color = tokens.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(
            "${profiles.size} of ${ProfileManager.MAX_PROFILES} profiles",
            color = tokens.textTertiary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    pinFor?.let { p ->
        ProfilePinDialog(
            title = "Enter ${p.name}'s PIN",
            onDismiss = { pinFor = null },
            onPin = { pin ->
                when (manager.select(p.id, pin)) {
                    SelectResult.Ok -> { pinFor = null; onDone(); true }
                    else -> false
                }
            },
        )
    }
}

@Composable
private fun ProfileTile(profile: Profile, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit) {
    val tokens = Cinetor.tokens
    Column(
        Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            ProfileAvatar(
                profile, 80.dp, selected = selected,
                modifier = Modifier.clickable(onClick = onClick).semantics { contentDescription = "Profile ${profile.name}" },
            )
            if (profile.hasPin()) {
                Icon(
                    Icons.Rounded.Lock, contentDescription = null, tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
                )
            }
        }
        Text(profile.name, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (profile.kids) Text("Kids", color = tokens.accent, style = MaterialTheme.typography.labelSmall)
        Icon(
            Icons.Rounded.Edit, contentDescription = "Edit ${profile.name}", tint = tokens.textSecondary,
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onEdit).padding(4.dp),
        )
    }
}

/** PIN pad dialog; [onPin] returns true when accepted (dialog closes), false → shake + retry. */
@Composable
fun ProfilePinDialog(title: String, onDismiss: () -> Unit, onPin: suspend (String) -> Boolean) {
    val tokens = Cinetor.tokens
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var shake by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.padding(16.dp).clip(RoundedCornerShape(28.dp)).background(tokens.surfaceElevated)
                .semantics { contentDescription = "Profile PIN" },
        ) {
            PinPanel(
                title = title,
                subtitle = if (error) "Wrong PIN — try again" else null,
                entered = pin.length,
                shakeKey = shake,
                error = error,
                enabled = true,
                onDigit = { d ->
                    if (pin.length < PinHasher.PIN_LENGTH) pin += d
                    if (pin.length == PinHasher.PIN_LENGTH) {
                        val value = pin
                        pin = ""
                        scope.launch {
                            if (!onPin(value)) { error = true; shake++ }
                        }
                    }
                },
                onBackspace = { pin = pin.dropLast(1) },
                onCancel = onDismiss,
            )
        }
    }
}

/**
 * Cold-start "Who's watching?" overlay: decided once, when profiles first load; shown only with > 1 profile.
 * Profiles created later in the same session never pop it up.
 */
@Composable
fun StartupProfilePicker(manager: ProfileManager) {
    var decided by rememberSaveable { mutableStateOf(false) }
    var show by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(manager) {
        if (decided) return@LaunchedEffect
        val list = manager.profiles.first { it.isNotEmpty() }
        show = list.size > 1
        decided = true
    }
    if (!show) return
    Box(
        Modifier.fillMaxSize().background(Cinetor.tokens.bg.copy(alpha = 0.97f)).clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        ProfilePicker(
            manager = manager,
            contentPadding = PaddingValues(top = 48.dp, bottom = 24.dp),
            onEdit = {},
            onAdd = {},
            onDone = { show = false },
        )
    }
}
