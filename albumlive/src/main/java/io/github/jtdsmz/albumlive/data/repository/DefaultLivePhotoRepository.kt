package io.github.jtdsmz.albumlive.data.repository

import io.github.jtdsmz.albumlive.data.LivePhotoUtils
import io.github.jtdsmz.albumlive.domain.model.LivePhotoAssets
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.repository.LivePhotoRepository

internal class DefaultLivePhotoRepository : LivePhotoRepository {
    override fun checkLivePhoto(path: String): Boolean {
        return LivePhotoUtils.isLivePhotoFile(path)
    }

    override suspend fun extractLivePhotoAssets(
        item: MediaItem,
        outputDir: String,
        outputFilePrefix: String
    ): Result<LivePhotoAssets> {
        return LivePhotoUtils.extractLivePhotoAssets(
            inputPath = item.path,
            outputDir = outputDir,
            outputFilePrefix = outputFilePrefix
        ).map {
            LivePhotoAssets(
                coverImagePath = it.coverImagePath,
                videoPath = it.videoPath
            )
        }
    }
}
