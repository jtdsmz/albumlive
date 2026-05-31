package io.github.jtdsmz.albumlive.domain.repository

import io.github.jtdsmz.albumlive.domain.model.LivePhotoAssets
import io.github.jtdsmz.albumlive.domain.model.MediaItem

internal interface LivePhotoRepository {
    fun checkLivePhoto(path: String): Boolean
    suspend fun extractLivePhotoAssets(
        item: MediaItem,
        outputDir: String,
        outputFilePrefix: String
    ): Result<LivePhotoAssets>
}
