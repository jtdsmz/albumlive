package io.github.jtdsmz.albumlive.domain.usecase

import io.github.jtdsmz.albumlive.domain.model.AlbumFolder
import io.github.jtdsmz.albumlive.domain.model.MediaType
import io.github.jtdsmz.albumlive.domain.repository.AlbumRepository

internal class GetAlbumFoldersUseCase(
    private val albumRepository: AlbumRepository
) {
    operator fun invoke(mediaType: MediaType): Result<List<AlbumFolder>> {
        return albumRepository.getFolders(mediaType)
    }
}
