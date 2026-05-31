package io.github.jtdsmz.albumlive.data.repository

import android.content.Context
import android.net.Uri
import io.github.jtdsmz.albumlive.SaveMotionPhotoOptions
import io.github.jtdsmz.albumlive.data.AlbumManager
import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.model.MediaType
import io.github.jtdsmz.albumlive.domain.repository.AlbumRepository
import kotlinx.coroutines.flow.Flow

internal class AndroidAlbumRepository(
    context: Context
) : AlbumRepository {
    private val appContext = context.applicationContext

    override fun getFolders(mediaType: MediaType): Result<List<AlbumFolder>> {
        return AlbumManager.getFolders(appContext, mediaType)
    }

    override fun getAllMediaFlow(mediaType: MediaType): Flow<Result<MediaItem>> {
        return AlbumManager.getAllMediaFlow(appContext, mediaType)
    }

    override fun getMediaFlowByFolder(folder: AlbumFolder, mediaType: MediaType): Flow<Result<MediaItem>> {
        return AlbumManager.getMediaFlowByFolder(appContext, folder, mediaType)
    }

    override fun saveMotionPhotoToGallery(motionPhotoPath: String, options: SaveMotionPhotoOptions): Result<Uri> {
        return AlbumManager.saveMotionPhotoToGallery(appContext, motionPhotoPath, options)
    }
}
