package com.lwt.photos.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jtdsmz.albumlive.AlbumLiveApi
import io.github.jtdsmz.albumlive.CreateMotionPhotoOptions
import io.github.jtdsmz.albumlive.SaveMotionPhotoOptions
import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class GalleryMediaItem(
    val media: MediaItem,
    val isLivePhoto: Boolean = false
) {
    val id: Long get() = media.id
    val type: MediaType get() = media.type
    val uri get() = media.uri
    val path: String get() = media.path
    val displayName: String get() = media.displayName
    val mimeType: String get() = media.mimeType
    val folderPath: String get() = media.folderPath
    val duration: Long get() = media.duration
    val width: Int get() = media.width
    val height: Int get() = media.height
}

data class GalleryUiState(
    val hasPermission: Boolean = false,
    val mediaType: MediaType = MediaType.ALL,
    val folders: List<AlbumFolder> = emptyList(),
    val selectedFolder: AlbumFolder? = null,
    val items: List<GalleryMediaItem> = emptyList(),
    val previewItem: GalleryMediaItem? = null,
    val livePreviewVideoPath: String? = null,
    val selectedCover: GalleryMediaItem? = null,
    val selectedVideo: GalleryMediaItem? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var liveScanJob: Job? = null
    private val albumLiveApi = AlbumLiveApi.create(application)

    init {
        refreshPermission()
    }

    fun refreshPermission() {
        val granted = AlbumPermissionHelper.hasReadPermission(getApplication())
        _uiState.value = _uiState.value.copy(hasPermission = granted)
        if (granted) reload()
    }

    fun selectMediaType(type: MediaType) {
        _uiState.value = _uiState.value.copy(mediaType = type, selectedFolder = null)
        reload()
    }

    fun selectFolder(folder: AlbumFolder?) {
        _uiState.value = _uiState.value.copy(selectedFolder = folder)
        loadMedia()
    }

    fun preview(item: GalleryMediaItem?) {
        _uiState.value = _uiState.value.copy(previewItem = item, livePreviewVideoPath = null, message = null)
        if (item == null || item.type != MediaType.IMAGE) return

        viewModelScope.launch {
            val checkedItem = withContext(Dispatchers.IO) {
                val isLivePhoto = checkLivePhotoWithAppCache(item.path)
                item.copy(isLivePhoto = isLivePhoto)
            }
            updateLivePhotoFlag(checkedItem)
            if (checkedItem.isLivePhoto) {
                extractLivePreviewVideo(checkedItem)
            }
        }
    }

    fun pickForMotionPhoto(item: GalleryMediaItem) {
        val state = _uiState.value
        val next = when (item.type) {
            MediaType.IMAGE -> state.copy(selectedCover = item, message = "已选择封面")
            MediaType.VIDEO -> state.copy(selectedVideo = item, message = "已选择视频")
            MediaType.ALL -> state
        }
        _uiState.value = next
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun createAndSaveMotionPhoto() {
        val state = _uiState.value
        val cover = state.selectedCover
        val video = state.selectedVideo
        if (cover == null || video == null) {
            _uiState.value = state.copy(message = "请先选择 JPEG 封面和视频")
            return
        }
        if (cover.path.isBlank() || video.path.isBlank()) {
            _uiState.value = state.copy(message = "当前授权模式下无法访问文件路径，不能合成实况")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, message = null)
            val app = getApplication<Application>()
            val result = albumLiveApi.createAndSaveMotionPhoto(
                coverPath = cover.path,
                videoPath = video.path,
                createOptions = CreateMotionPhotoOptions(
                    outputDir = File(app.cacheDir, "motion_photo"),
                    oplusOwner = "Photos"
                ),
                saveOptions = SaveMotionPhotoOptions(
                    relativePath = "Pictures/Photos/"
                )
            )
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = result.fold(
                    onSuccess = { "已保存到相册: $it" },
                    onFailure = { "保存失败: ${it.message}" }
                )
            )
            if (result.isSuccess) reload()
        }
    }

    private fun reload() {
        loadFolders()
        loadMedia()
    }

    private fun loadFolders() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = albumLiveApi.getFolders(_uiState.value.mediaType)
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    folders = result.getOrDefault(emptyList()),
                    message = result.exceptionOrNull()?.message
                )
            }
        }
    }

    private fun loadMedia() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, items = emptyList())
            val current = _uiState.value
            val flow = albumLiveApi.getMediaItems(current.selectedFolder, current.mediaType)
            val items = mutableListOf<GalleryMediaItem>()
            flow.collect { result ->
                result.fold(
                    onSuccess = {
                        items += GalleryMediaItem(media = it)
                        if (items.size % 24 == 0) {
                            _uiState.value = _uiState.value.copy(items = items.toList())
                        }
                    },
                    onFailure = {
                        _uiState.value = _uiState.value.copy(message = it.message)
                    }
                )
            }
            _uiState.value = _uiState.value.copy(isLoading = false, items = items.toList())
            startAsyncLivePhotoMarking(items)
        }
    }

    private fun startAsyncLivePhotoMarking(items: List<GalleryMediaItem>) {
        liveScanJob?.cancel()
        val candidates = items.filter {
            it.type == MediaType.IMAGE &&
                it.path.isNotBlank() &&
                LivePhotoMarkCache.get(it.path) == null
        }
        if (candidates.isEmpty()) return
        liveScanJob = viewModelScope.launch(Dispatchers.IO) {
            candidates.forEach { item ->
                val isLivePhoto = checkLivePhotoWithAppCache(item.path)
                if (isLivePhoto) {
                    withContext(Dispatchers.Main) {
                        updateLivePhotoFlag(item.copy(isLivePhoto = true))
                    }
                }
            }
        }
    }

    private fun updateLivePhotoFlag(item: GalleryMediaItem) {
        val state = _uiState.value
        val nextItems = state.items.map {
            if (it.type == item.type && it.id == item.id && it.uri == item.uri) item else it
        }
        val nextPreview = state.previewItem?.let {
            if (it.type == item.type && it.id == item.id && it.uri == item.uri) item else it
        }
        val nextCover = state.selectedCover?.let {
            if (it.type == item.type && it.id == item.id && it.uri == item.uri) item else it
        }
        _uiState.value = state.copy(
            items = nextItems,
            previewItem = nextPreview,
            selectedCover = nextCover
        )
    }

    private suspend fun extractLivePreviewVideo(item: GalleryMediaItem) {
        val outputDir = File(getApplication<Application>().cacheDir, "live_preview")
        val result = if (item.path.isBlank()) {
            Result.failure(IllegalStateException("当前授权模式下无法访问文件路径，不能拆解实况"))
        } else {
            albumLiveApi.extractLivePhotoAssets(item.media, outputDir.absolutePath, "preview_${item.id}")
        }
        _uiState.value = _uiState.value.copy(
            livePreviewVideoPath = result.getOrNull()?.videoPath,
            message = result.exceptionOrNull()?.message
        )
    }

    private fun checkLivePhotoWithAppCache(path: String): Boolean {
        if (path.isBlank()) return false
        LivePhotoMarkCache.get(path)?.let { return it }
        return albumLiveApi.checkLivePhoto(path).also {
            LivePhotoMarkCache.put(path, it)
        }
    }
}
