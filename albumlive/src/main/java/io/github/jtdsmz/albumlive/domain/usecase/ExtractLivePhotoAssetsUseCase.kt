package io.github.jtdsmz.albumlive.domain.usecase

import io.github.jtdsmz.albumlive.domain.model.LivePhotoAssets
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.repository.LivePhotoRepository

internal class ExtractLivePhotoAssetsUseCase(
    private val livePhotoRepository: LivePhotoRepository
) {
    suspend operator fun invoke(
        item: MediaItem,
        outputDir: String,
        outputFilePrefix: String
    ): Result<LivePhotoAssets> {
        return livePhotoRepository.extractLivePhotoAssets(item, outputDir, outputFilePrefix)
    }
}
