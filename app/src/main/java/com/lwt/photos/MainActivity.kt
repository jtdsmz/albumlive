package com.lwt.photos

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaType
import com.lwt.photos.ui.AlbumPermissionHelper
import com.lwt.photos.ui.GalleryMediaItem
import com.lwt.photos.ui.GalleryUiState
import com.lwt.photos.ui.GalleryViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<GalleryViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotosTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                PhotosApp(
                    state = state,
                    onPermissionResult = viewModel::refreshPermission,
                    onMediaType = viewModel::selectMediaType,
                    onFolder = viewModel::selectFolder,
                    onPreview = viewModel::preview,
                    onPickForMotionPhoto = viewModel::pickForMotionPhoto,
                    onDismissPreview = { viewModel.preview(null) },
                    onCreateMotionPhoto = viewModel::createAndSaveMotionPhoto,
                    onClearMessage = viewModel::clearMessage
                )
            }
        }
    }
}

@Composable
private fun PhotosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2C5F5D),
            secondary = Color(0xFF875C32),
            background = Color(0xFFF7F7F2),
            surface = Color(0xFFFFFFFF)
        ),
        content = content
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PhotosApp(
    state: GalleryUiState,
    onPermissionResult: () -> Unit,
    onMediaType: (MediaType) -> Unit,
    onFolder: (AlbumFolder?) -> Unit,
    onPreview: (GalleryMediaItem) -> Unit,
    onPickForMotionPhoto: (GalleryMediaItem) -> Unit,
    onDismissPreview: () -> Unit,
    onCreateMotionPhoto: () -> Unit,
    onClearMessage: () -> Unit
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionResult()
    }

    LaunchedEffect(Unit) {
        if (!state.hasPermission) {
            permissionLauncher.launch(AlbumPermissionHelper.getReadPermissions())
        }
    }

    Scaffold(
        topBar = {
            Header(
                state = state,
                onMediaType = onMediaType,
                onRequestPermission = {
                    permissionLauncher.launch(AlbumPermissionHelper.getReadPermissions())
                },
                onCreateMotionPhoto = onCreateMotionPhoto
            )
        },
        bottomBar = {
            SelectionBar(state = state, onCreateMotionPhoto = onCreateMotionPhoto)
        }
    ) { padding ->
        if (!state.hasPermission) {
            PermissionPanel(
                modifier = Modifier.padding(padding),
                onRequest = { permissionLauncher.launch(AlbumPermissionHelper.getReadPermissions()) }
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                FolderRow(
                    folders = state.folders,
                    selectedFolder = state.selectedFolder,
                    onFolder = onFolder
                )
                MediaGrid(
                    state = state,
                    onPreview = onPreview,
                    onPickForMotionPhoto = onPickForMotionPhoto
                )
            }
        }
    }

    state.previewItem?.let {
        PreviewSheet(
            item = it,
            livePreviewVideoPath = state.livePreviewVideoPath,
            onDismiss = onDismissPreview
        )
    }

    state.message?.let {
        MessageSheet(message = it, onDismiss = onClearMessage)
    }
}

@Composable
private fun Header(
    state: GalleryUiState,
    onMediaType: (MediaType) -> Unit,
    onRequestPermission: () -> Unit,
    onCreateMotionPhoto: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRequestPermission) { Text("授权") }
            Button(onClick = onCreateMotionPhoto, enabled = !state.isSaving) {
                Text(if (state.isSaving) "保存中" else "保存实况")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaType.entries.forEach { type ->
                FilterChip(
                    selected = state.mediaType == type,
                    onClick = { onMediaType(type) },
                    label = {
                        Text(
                            when (type) {
                                MediaType.ALL -> "全部"
                                MediaType.IMAGE -> "图片"
                                MediaType.VIDEO -> "视频"
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionPanel(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("需要相册权限")
            Button(onClick = onRequest) { Text("授权") }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<AlbumFolder>,
    selectedFolder: AlbumFolder?,
    onFolder: (AlbumFolder?) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selectedFolder == null,
                onClick = { onFolder(null) },
                label = { Text("最近") }
            )
        }
        items(folders, key = { it.bucketId }) { folder ->
            FilterChip(
                selected = selectedFolder?.bucketId == folder.bucketId,
                onClick = { onFolder(folder) },
                label = {
                    Text(
                        "${folder.folderName} ${folder.mediaCount}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

@Composable
private fun MediaGrid(
    state: GalleryUiState,
    onPreview: (GalleryMediaItem) -> Unit,
    onPickForMotionPhoto: (GalleryMediaItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(state.items, key = { "${it.type}_${it.id}_${it.uri}" }) { item ->
            MediaCell(
                item = item,
                isCover = state.selectedCover?.uri == item.uri,
                isVideo = state.selectedVideo?.uri == item.uri,
                onPreview = { onPreview(item) },
                onPick = { onPickForMotionPhoto(item) }
            )
        }
    }
}

@Composable
private fun MediaCell(
    item: GalleryMediaItem,
    isCover: Boolean,
    isVideo: Boolean,
    onPreview: () -> Unit,
    onPick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE5E3DB))
            .clickable(onClick = onPreview)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(6.dp)
        ) {
            Text(
                item.displayName,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (item.type == MediaType.VIDEO) Label(text = formatDuration(item.duration))
                if (item.isLivePhoto) Label(text = "实况")
                if (isCover) Label(text = "封面")
                if (isVideo) Label(text = "视频")
            }
        }
        TextButton(
            onClick = onPick,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Text("选", color = Color.White)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(Color(0xAA2C5F5D), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun SelectionBar(state: GalleryUiState, onCreateMotionPhoto: () -> Unit) {
    Surface(shadowElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("封面: ${state.selectedCover?.displayName ?: "-"}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text("视频: ${state.selectedVideo?.displayName ?: "-"}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onCreateMotionPhoto,
                enabled = state.selectedCover != null && state.selectedVideo != null && !state.isSaving
            ) {
                Text("合成")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PreviewSheet(
    item: GalleryMediaItem,
    livePreviewVideoPath: String?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (item.type == MediaType.VIDEO) {
                VideoPlayer(uri = item.uri)
            } else {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentScale = ContentScale.Fit
                )
                if (item.isLivePhoto && livePreviewVideoPath != null) {
                    Text("实况视频")
                    VideoPlayer(uri = Uri.fromFile(java.io.File(livePreviewVideoPath)))
                }
            }
            Text("${item.folderPath.ifBlank { "无文件路径，使用 Uri 访问" }}\n${item.mimeType} ${item.width}x${item.height}")
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(8.dp))
    )
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose { player.release() }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MessageSheet(message: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message)
            Button(onClick = onDismiss) { Text("确定") }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "视频"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    return "%02d:%02d".format(minutes, seconds)
}
