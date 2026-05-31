package io.github.jtdsmz.albumlive.domain.model

import android.net.Uri

data class AlbumFolder(
    val bucketId: String,
    val folderName: String,
    val folderPath: String,
    val coverUri: Uri,
    val coverPath: String,
    val mediaCount: Int,
    val lastModifiedTime: Long
)
