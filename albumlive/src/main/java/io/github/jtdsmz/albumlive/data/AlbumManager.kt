package io.github.jtdsmz.albumlive.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.jtdsmz.albumlive.SaveMotionPhotoOptions
import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

internal object AlbumManager {
    private data class FolderStat(
        val bucketId: String,
        val folderName: String,
        val folderPath: String,
        val coverUri: Uri,
        val coverPath: String,
        var mediaCount: Int,
        var lastModifiedTime: Long
    )

    private val imageProjection: Array<String>
        get() = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.MIME_TYPE)
            add(MediaStore.Images.Media.SIZE)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
        }.toTypedArray()

    private val videoProjection: Array<String>
        get() = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.Video.Media.BUCKET_ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.MIME_TYPE)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.DATE_MODIFIED)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add("width")
            add("height")
        }.toTypedArray()

    fun getFolders(context: Context, mediaType: MediaType = MediaType.ALL): Result<List<AlbumFolder>> = runCatching {
        val folders = linkedMapOf<String, FolderStat>()
        if (mediaType == MediaType.IMAGE || mediaType == MediaType.ALL) queryImageFolders(context, folders)
        if (mediaType == MediaType.VIDEO || mediaType == MediaType.ALL) queryVideoFolders(context, folders)
        folders.values
            .map {
                AlbumFolder(
                    bucketId = it.bucketId,
                    folderName = it.folderName,
                    folderPath = it.folderPath,
                    coverUri = it.coverUri,
                    coverPath = it.coverPath,
                    mediaCount = it.mediaCount,
                    lastModifiedTime = it.lastModifiedTime
                )
            }
            .sortedByDescending { it.lastModifiedTime }
    }

    fun getAllMediaFlow(context: Context, mediaType: MediaType = MediaType.ALL): Flow<Result<MediaItem>> = flow {
        runCatching {
            val items = mutableListOf<MediaItem>()
            if (mediaType == MediaType.IMAGE || mediaType == MediaType.ALL) items += queryImages(context, null, null)
            if (mediaType == MediaType.VIDEO || mediaType == MediaType.ALL) items += queryVideos(context, null, null)
            items.sortedByDescending { it.dateAdded }
        }.fold(
            onSuccess = { items -> items.forEach { emit(Result.success(it)) } },
            onFailure = { emit(Result.failure(it)) }
        )
    }.flowOn(Dispatchers.IO)

    fun getMediaFlowByFolder(
        context: Context,
        folder: AlbumFolder,
        mediaType: MediaType = MediaType.ALL
    ): Flow<Result<MediaItem>> = flow {
        if (folder.bucketId.isBlank() && folder.folderPath.isBlank()) return@flow
        runCatching {
            val items = mutableListOf<MediaItem>()
            if (mediaType == MediaType.IMAGE || mediaType == MediaType.ALL) {
                val (selection, args) = folderSelection(folder, isImage = true)
                items += queryImages(context, selection, args).filter { it.matchesFolder(folder) }
            }
            if (mediaType == MediaType.VIDEO || mediaType == MediaType.ALL) {
                val (selection, args) = folderSelection(folder, isImage = false)
                items += queryVideos(context, selection, args).filter { it.matchesFolder(folder) }
            }
            items.sortedByDescending { it.dateAdded }
        }.fold(
            onSuccess = { items -> items.forEach { emit(Result.success(it)) } },
            onFailure = { emit(Result.failure(it)) }
        )
    }.flowOn(Dispatchers.IO)

    private fun folderSelection(folder: AlbumFolder, isImage: Boolean): Pair<String, Array<String>> {
        val targetPath = folder.folderPath.trimEnd('/')
        return if (targetPath.isNotBlank()) {
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("$targetPath/%")
        } else {
            val bucketColumn = if (isImage) MediaStore.Images.Media.BUCKET_ID else MediaStore.Video.Media.BUCKET_ID
            "$bucketColumn = ?" to arrayOf(folder.bucketId)
        }
    }

    private fun MediaItem.matchesFolder(folder: AlbumFolder): Boolean {
        val targetPath = folder.folderPath.trimEnd('/')
        return if (targetPath.isNotBlank()) {
            folderPath.trimEnd('/') == targetPath
        } else {
            bucketId == folder.bucketId
        }
    }

    fun saveMotionPhotoToGallery(
        context: Context,
        motionPhotoPath: String,
        options: SaveMotionPhotoOptions
    ): Result<Uri> = runCatching {
        val motionFile = File(motionPhotoPath)
        require(motionFile.exists() && motionFile.isFile) { "motion photo not found" }
        require(LivePhotoUtils.isLivePhotoFile(motionPhotoPath)) { "file is not a live photo" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMotionPhotoByMediaStore(context, motionFile, options)
        } else {
            saveMotionPhotoByFile(context, motionFile, options)
        } ?: error("save motion photo failed")
    }

    private fun queryImages(context: Context, selection: String?, args: Array<String>?): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            selection,
            args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val path = cursor.getStringOrEmpty(dataCol)
                val relativePath = cursor.getStringOrEmpty(relativePathCol).trimEnd('/')
                val folderPath = path.substringBeforeLast('/', "")
                    .ifBlank { relativePath }
                val bucketId = cursor.getString(bucketIdCol).orEmpty()
                result += MediaItem(
                    id = id,
                    type = MediaType.IMAGE,
                    uri = uri,
                    path = path,
                    bucketId = bucketId,
                    displayName = cursor.getString(nameCol).orEmpty(),
                    mimeType = cursor.getString(mimeCol) ?: "image/*",
                    size = cursor.getLong(sizeCol),
                    dateAdded = cursor.getLong(addedCol),
                    dateModified = cursor.getLong(modifiedCol),
                    folderName = cursor.getString(bucketCol).orEmpty(),
                    folderPath = folderPath,
                    width = cursor.getInt(widthCol),
                    height = cursor.getInt(heightCol)
                )
            }
        }
        return result
    }

    private fun queryVideos(context: Context, selection: String?, args: Array<String>?): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthCol = cursor.getColumnIndex("width")
            val heightCol = cursor.getColumnIndex("height")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val path = cursor.getStringOrEmpty(dataCol)
                val relativePath = cursor.getStringOrEmpty(relativePathCol).trimEnd('/')
                val folderPath = path.substringBeforeLast('/', "")
                    .ifBlank { relativePath }
                val bucketId = cursor.getString(bucketIdCol).orEmpty()
                result += MediaItem(
                    id = id,
                    type = MediaType.VIDEO,
                    uri = uri,
                    path = path,
                    bucketId = bucketId,
                    displayName = cursor.getString(nameCol).orEmpty(),
                    mimeType = cursor.getString(mimeCol) ?: "video/*",
                    size = cursor.getLong(sizeCol),
                    dateAdded = cursor.getLong(addedCol),
                    dateModified = cursor.getLong(modifiedCol),
                    folderName = cursor.getString(bucketCol).orEmpty(),
                    folderPath = folderPath,
                    duration = cursor.getLong(durationCol),
                    width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                )
            }
        }
        return result
    }

    private fun queryImageFolders(context: Context, folders: MutableMap<String, FolderStat>) {
        queryFolders(
            context = context,
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Images.Media._ID,
            bucketIdColumn = MediaStore.Images.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            dateModifiedColumn = MediaStore.Images.Media.DATE_MODIFIED,
            mediaBaseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            folders = folders
        )
    }

    private fun queryVideoFolders(context: Context, folders: MutableMap<String, FolderStat>) {
        queryFolders(
            context = context,
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            bucketIdColumn = MediaStore.Video.Media.BUCKET_ID,
            bucketNameColumn = MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            dateModifiedColumn = MediaStore.Video.Media.DATE_MODIFIED,
            mediaBaseUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            folders = folders
        )
    }

    private fun queryFolders(
        context: Context,
        uri: Uri,
        idColumn: String,
        bucketIdColumn: String,
        bucketNameColumn: String,
        dateModifiedColumn: String,
        mediaBaseUri: Uri,
        folders: MutableMap<String, FolderStat>
    ) {
        val projection = buildList {
            add(bucketIdColumn)
            add(bucketNameColumn)
            add(MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(idColumn)
            add(dateModifiedColumn)
        }.toTypedArray()
        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "$bucketIdColumn ASC, $dateModifiedColumn DESC"
        )?.use { cursor ->
            val bucketIdCol = cursor.getColumnIndexOrThrow(bucketIdColumn)
            val bucketNameCol = cursor.getColumnIndexOrThrow(bucketNameColumn)
            val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            val relativePathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val idCol = cursor.getColumnIndexOrThrow(idColumn)
            val dateCol = cursor.getColumnIndexOrThrow(dateModifiedColumn)
            var lastBucketId = ""
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdCol).orEmpty()
                val dataPath = cursor.getStringOrEmpty(dataCol)
                val relativePath = cursor.getStringOrEmpty(relativePathCol).trimEnd('/')
                val folderPath = dataPath.substringBeforeLast('/', "")
                    .ifBlank { relativePath }
                val modified = cursor.getLong(dateCol)
                if (bucketId != lastBucketId) {
                    lastBucketId = bucketId
                    mergeFolder(
                        folders = folders,
                        bucketId = bucketId,
                        folderName = cursor.getString(bucketNameCol).orEmpty().ifBlank { "Unknown" },
                        folderPath = folderPath,
                        coverUri = ContentUris.withAppendedId(mediaBaseUri, cursor.getLong(idCol)),
                        coverPath = dataPath,
                        modified = modified
                    )
                } else {
                    folders[bucketId]?.mediaCount = (folders[bucketId]?.mediaCount ?: 0) + 1
                }
            }
        }
    }

    private fun mergeFolder(
        folders: MutableMap<String, FolderStat>,
        bucketId: String,
        folderName: String,
        folderPath: String,
        coverUri: Uri,
        coverPath: String,
        modified: Long
    ) {
        val existing = folders[bucketId]
        if (existing == null) {
            folders[bucketId] = FolderStat(bucketId, folderName, folderPath, coverUri, coverPath, 1, modified)
        } else {
            existing.mediaCount += 1
            if (modified > existing.lastModifiedTime) {
                existing.lastModifiedTime = modified
            }
        }
    }

    private fun saveMotionPhotoByMediaStore(context: Context, file: File, options: SaveMotionPhotoOptions): Uri? {
        val now = System.currentTimeMillis()
        val (width, height) = imageBounds(file.absolutePath)
        val isVivo = Build.MANUFACTURER.orEmpty().lowercase().contains("vivo")
        val vivoLivePhotoId = if (isVivo) MotionPhotoOemHelpers.readVivoLivePhotoId(file) else null
        val relativePath = options.relativePath.trim('/').ifBlank { Environment.DIRECTORY_PICTURES } + "/"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, file.lastModified().takeIf { it > 0L } ?: now)
            put(MediaStore.Images.Media.DISPLAY_NAME, ensureJpegName(options.displayName ?: file.name))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.SIZE, file.length())
            if (width > 0) put(MediaStore.Images.Media.WIDTH, width)
            if (height > 0) put(MediaStore.Images.Media.HEIGHT, height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            if (isVivo && !vivoLivePhotoId.isNullOrBlank()) put("live_photo", vivoLivePhotoId)
        }
        val resolver = context.contentResolver
        var savedUri = try {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (_: Throwable) {
            if (values.containsKey("live_photo")) {
                values.remove("live_photo")
                runCatching {
                    resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                }.getOrNull()
            } else {
                null
            }
        } ?: return null

        val ok = runCatching {
            resolver.openOutputStream(savedUri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(savedUri, null, null)
            return null
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        if (isVivo && !vivoLivePhotoId.isNullOrBlank()) values.put("live_photo", vivoLivePhotoId)
        resolver.update(savedUri, values, null, null)
        return savedUri
    }

    @Suppress("DEPRECATION")
    private fun saveMotionPhotoByFile(context: Context, file: File, options: SaveMotionPhotoOptions): Uri? {
        val relativePath = options.relativePath.trim('/')
        val dir = if (relativePath.isBlank()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } else {
            File(Environment.getExternalStorageDirectory(), relativePath)
        }
        if (!dir.exists()) dir.mkdirs()
        val target = uniqueFile(File(dir, ensureJpegName(options.displayName ?: file.name)))
        val ok = runCatching {
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
        if (!ok) return null
        return Uri.fromFile(target).also {
            context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, it))
        }
    }

    private fun imageBounds(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
    }

    private fun Cursor.getStringOrEmpty(columnIndex: Int): String {
        return if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex).orEmpty() else ""
    }

    private fun ensureJpegName(name: String): String {
        val safeName = name.ifBlank { "motion_photo_${System.currentTimeMillis()}.jpg" }
        val ext = safeName.substringAfterLast('.', "")
        return if (ext.equals("jpg", true) || ext.equals("jpeg", true)) safeName else "$safeName.jpg"
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val baseName = file.nameWithoutExtension
        return File(file.parentFile, "${baseName}_${System.currentTimeMillis()}.${file.extension.ifBlank { "jpg" }}")
    }
}
