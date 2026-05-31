package io.github.jtdsmz.albumlive.domain.usecase

import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaItem
import io.github.jtdsmz.albumlive.domain.model.MediaType
import io.github.jtdsmz.albumlive.domain.repository.AlbumRepository
import kotlinx.coroutines.flow.Flow

internal class GetMediaItemsUseCase(
    private val albumRepository: AlbumRepository
) {
    operator fun invoke(folder: AlbumFolder?, mediaType: MediaType): Flow<Result<MediaItem>> {
        return folder?.let {
            albumRepository.getMediaFlowByFolder(it, mediaType)
        } ?: albumRepository.getAllMediaFlow(mediaType)
    }
}
