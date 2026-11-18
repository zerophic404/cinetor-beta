package com.mythbrix.cinetor.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mythbrix.cinetor.design.Cinetor

/**
 * Edit profile: display name plus a picture picker. "Built-in" keeps the generated shape avatars;
 * "Anime" and "Heroes" list popular character pictures loaded on demand ([CharacterGallery]).
 * Save writes the name to the profile and the picture to [ProfilePhotos].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditSheet(
    profile: Profile,
    gallery: CharacterGallery,
    onSave: (name: String, avatar: Int, photo: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = Cinetor.tokens
    val photos by ProfilePhotos.photos.collectAsState()
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var avatar by remember(profile.id) { mutableIntStateOf(profile.avatar) }
    var photo by remember(profile.id) { mutableStateOf(photos[profile.id]) }
    var set by remember { mutableStateOf(if (photo != null) CharacterSet.ANIME else CharacterSet.BUILT_IN) }
    var pics by remember { mutableStateOf<List<CharacterPic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(set) {
        if (set == CharacterSet.BUILT_IN) return@LaunchedEffect
        loading = true
        pics = gallery.load(set)
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceElevated,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val current = photo
                if (current != null) PhotoAvatar(current, 64.dp) else ProfileAvatar(name.ifBlank { "?" }, avatar, profile.color, 64.dp)
                Text(
                    "Edit profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = name.trim().isNotEmpty(),
                    onClick = { onSave(name.trim(), avatar, photo) },
                ) { Text("Save", color = tokens.accent, fontWeight = FontWeight.SemiBold) }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CharacterSet.entries.forEach { s ->
                    val active = s == set
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) tokens.onAccent else tokens.textPrimary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (active) tokens.accent else tokens.glassFill)
                            .clickable { set = s }
                            .semantics { contentDescription = "Pictures ${s.label}" }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(340.dp)) {
                when {
                    set == CharacterSet.BUILT_IN -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(Avatars.shapes.size) { i ->
                            ProfileAvatar(
                                name.ifBlank { "?" }, i, profile.color, 64.dp,
                                selected = photo == null && i == avatar,
                                modifier = Modifier
                                    .clickable { avatar = i; photo = null }
                                    .semantics { contentDescription = "Built-in avatar ${i + 1}" },
                            )
                        }
                    }
                    loading -> CircularProgressIndicator(color = tokens.accent, modifier = Modifier.align(Alignment.Center))
                    pics.isEmpty() -> Text(
                        "Couldn't load pictures right now.",
                        color = tokens.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(76.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(pics, key = { it.imageUrl }) { pic ->
                            Column(
                                Modifier
                                    .clickable { photo = pic.imageUrl }
                                    .semantics { contentDescription = "Picture ${pic.name}" },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                PhotoAvatar(pic.imageUrl, 64.dp, selected = photo == pic.imageUrl)
                                Text(
                                    pic.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
