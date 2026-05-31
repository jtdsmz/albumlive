package io.github.jtdsmz.albumlive.domain.usecase

import io.github.jtdsmz.albumlive.domain.repository.LivePhotoRepository

internal class CheckLivePhotoUseCase(
    private val livePhotoRepository: LivePhotoRepository
) {
    operator fun invoke(path: String): Boolean {
        return livePhotoRepository.checkLivePhoto(path)
    }
}
