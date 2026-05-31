package io.github.jtdsmz.albumlive

import android.content.Context
import android.net.Uri
import io.github.jtdsmz.albumlive.data.repository.AndroidAlbumRepository
import io.github.jtdsmz.albumlive.data.repository.DefaultLivePhotoRepository
import io.github.jtdsmz.albumlive.data.repository.DefaultMotionPhotoRepository
import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.LivePhotoAssets
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.model.MediaType
import io.github.jtdsmz.albumlive.domain.usecase.CheckLivePhotoUseCase
import io.github.jtdsmz.albumlive.domain.usecase.CreateMotionPhotoUseCase
import io.github.jtdsmz.albumlive.domain.usecase.ExtractLivePhotoAssetsUseCase
import io.github.jtdsmz.albumlive.domain.usecase.GetAlbumFoldersUseCase
import io.github.jtdsmz.albumlive.domain.usecase.GetMediaItemsUseCase
import io.github.jtdsmz.albumlive.domain.usecase.SaveMotionPhotoUseCase
import kotlinx.coroutines.flow.Flow
import java.io.File

class AlbumLiveApi private constructor(
    context: Context
) {
    private val albumRepository = AndroidAlbumRepository(context)
    private val livePhotoRepository = DefaultLivePhotoRepository()
    private val motionPhotoRepository = DefaultMotionPhotoRepository()
    private val getAlbumFolders = GetAlbumFoldersUseCase(albumRepository)
    private val getMediaItems = GetMediaItemsUseCase(albumRepository)
    private val checkLivePhoto = CheckLivePhotoUseCase(livePhotoRepository)
    private val extractLivePhotoAssets = ExtractLivePhotoAssetsUseCase(livePhotoRepository)
    private val createMotionPhoto = CreateMotionPhotoUseCase(motionPhotoRepository)
    private val saveMotionPhoto = SaveMotionPhotoUseCase(albumRepository)

    fun getFolders(mediaType: MediaType = MediaType.ALL): Result<List<AlbumFolder>> {
        return getAlbumFolders(mediaType)
    }

    fun getMediaItems(folder: AlbumFolder?, mediaType: MediaType = MediaType.ALL): Flow<Result<MediaItem>> {
        return getMediaItems.invoke(folder, mediaType)
    }

    fun checkLivePhoto(path: String): Boolean {
        return checkLivePhoto.invoke(path)
    }

    suspend fun extractLivePhotoAssets(
        item: MediaItem,
        outputDir: String,
        outputFilePrefix: String
    ): Result<LivePhotoAssets> {
        return extractLivePhotoAssets.invoke(item, outputDir, outputFilePrefix)
    }

    suspend fun createMotionPhoto(
        coverPath: String,
        videoPath: String,
        options: CreateMotionPhotoOptions
    ): Result<File> {
        return createMotionPhoto.invoke(coverPath, videoPath, options)
    }

    fun saveMotionPhoto(motionPhotoPath: String, options: SaveMotionPhotoOptions): Result<Uri> {
        return saveMotionPhoto.invoke(motionPhotoPath, options)
    }

    suspend fun createAndSaveMotionPhoto(
        coverPath: String,
        videoPath: String,
        createOptions: CreateMotionPhotoOptions,
        saveOptions: SaveMotionPhotoOptions
    ): Result<Uri> {
        return createMotionPhoto(coverPath, videoPath, createOptions).fold(
            onSuccess = { output -> saveMotionPhoto(output.absolutePath, saveOptions) },
            onFailure = { Result.failure(it) }
        )
    }

    companion object {
        fun create(context: Context): AlbumLiveApi {
            return AlbumLiveApi(context.applicationContext)
        }
    }
}
