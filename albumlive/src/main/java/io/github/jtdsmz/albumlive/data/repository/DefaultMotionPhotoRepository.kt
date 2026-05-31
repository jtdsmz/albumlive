package io.github.jtdsmz.albumlive.data.repository

import io.github.jtdsmz.albumlive.CreateMotionPhotoOptions
import io.github.jtdsmz.albumlive.data.MotionPhotoCreator
import io.github.jtdsmz.albumlive.domain.repository.MotionPhotoRepository
import java.io.File

internal class DefaultMotionPhotoRepository : MotionPhotoRepository {
    override suspend fun createMotionPhoto(
        coverPath: String,
        videoPath: String,
        options: CreateMotionPhotoOptions
    ): Result<File> {
        return MotionPhotoCreator.createMotionPhoto(coverPath, videoPath, options)
    }
}
