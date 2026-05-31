package io.github.jtdsmz.albumlive.domain.usecase

import android.net.Uri
import io.github.jtdsmz.albumlive.SaveMotionPhotoOptions
import io.github.jtdsmz.albumlive.domain.repository.AlbumRepository

internal class SaveMotionPhotoUseCase(
    private val albumRepository: AlbumRepository
) {
    operator fun invoke(motionPhotoPath: String, options: SaveMotionPhotoOptions): Result<Uri> {
        return albumRepository.saveMotionPhotoToGallery(motionPhotoPath, options)
    }
}
