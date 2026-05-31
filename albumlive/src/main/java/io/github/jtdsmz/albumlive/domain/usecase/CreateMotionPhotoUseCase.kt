package io.github.jtdsmz.albumlive.domain.usecase

import io.github.jtdsmz.albumlive.CreateMotionPhotoOptions
import io.github.jtdsmz.albumlive.domain.repository.MotionPhotoRepository
import java.io.File

internal class CreateMotionPhotoUseCase(
    private val motionPhotoRepository: MotionPhotoRepository
) {
    suspend operator fun invoke(coverPath: String, videoPath: String, options: CreateMotionPhotoOptions): Result<File> {
        return motionPhotoRepository.createMotionPhoto(coverPath, videoPath, options)
    }
}
