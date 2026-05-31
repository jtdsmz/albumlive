package io.github.jtdsmz.albumlive

import java.io.File

data class CreateMotionPhotoOptions(
    val outputDir: File,
    val outputFileNamePrefix: String? = null,
    val oplusOwner: String? = null,
    val vivoLivePhotoId: String? = null
)
