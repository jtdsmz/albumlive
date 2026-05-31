package io.github.jtdsmz.albumlive.domain.repository

import android.net.Uri
import io.github.jtdsmz.albumlive.SaveMotionPhotoOptions
import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

internal interface AlbumRepository {
    fun getFolders(mediaType: MediaType = MediaType.ALL): Result<List<AlbumFolder>>
    fun getAllMediaFlow(mediaType: MediaType = MediaType.ALL): Flow<Result<MediaItem>>
    fun getMediaFlowByFolder(folder: AlbumFolder, mediaType: MediaType = MediaType.ALL): Flow<Result<MediaItem>>
    fun saveMotionPhotoToGallery(motionPhotoPath: String, options: SaveMotionPhotoOptions): Result<Uri>
}
