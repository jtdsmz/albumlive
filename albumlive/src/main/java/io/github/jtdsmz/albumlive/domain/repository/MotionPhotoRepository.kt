package io.github.jtdsmz.albumlive.domain.repository

import io.github.jtdsmz.albumlive.CreateMotionPhotoOptions
import java.io.File

internal interface MotionPhotoRepository {
    suspend fun createMotionPhoto(
        coverPath: String,
        videoPath: String,
        options: CreateMotionPhotoOptions
    ): Result<File>
}
