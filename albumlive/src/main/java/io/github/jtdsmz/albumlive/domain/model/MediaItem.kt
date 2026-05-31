package io.github.jtdsmz.albumlive.domain.model

import android.net.Uri

data class MediaItem(
    val id: Long,
    val type: MediaType,
    val uri: Uri,
    val path: String,
    val bucketId: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val folderName: String,
    val folderPath: String,
    val duration: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)
