package eu.kanade.tachiyomi.extension.all.hentaipaw

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaListPageDto(
    val page: Int,
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
    val mangas: List<MangaDto> = emptyList(),
)

@Serializable
data class MangaDto(
    val id: Long,
    val title: String,
    val url: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String> = emptyList(),
    val status: Int = 2,
) {
    fun toSManga(isDetails: Boolean = false): SManga = SManga.create().apply {
        title = this@MangaDto.title
        url = "/api/manga/$id"
        val rawThumb = this@MangaDto.thumbnailUrl ?: this@MangaDto.coverUrl
        thumbnail_url = when {
            rawThumb != null && rawThumb.contains("/thumbnails/cover.webp") ->
                "https://cdn.imagedeliveries.com/$id/thumbnails/cover.webp"
            else -> rawThumb
        }
        artist = this@MangaDto.artist
        author = this@MangaDto.author
        description = this@MangaDto.description
        genre = this@MangaDto.genre.joinToString(", ")
        status = this@MangaDto.status
        initialized = isDetails
    }
}

@Serializable
data class ChapterDto(
    val id: Long,
    val name: String = "Chapter 1",
    @SerialName("chapter_number") val chapterNumber: Float = 1.0f,
    val url: String = "",
    @SerialName("date_upload") val dateUpload: Long = 0L,
)

@Serializable
data class PageDto(
    val index: Int,
    val url: String = "",
    @SerialName("image_url") val imageUrl: String,
)

@Serializable
data class FilterItemDto(
    val id: Int,
    val name: String,
)

@Serializable
data class FilterOptionsDto(
    val tags: List<FilterItemDto> = emptyList(),
    val artists: List<FilterItemDto> = emptyList(),
    val characters: List<FilterItemDto> = emptyList(),
    val parodies: List<FilterItemDto> = emptyList(),
    val groups: List<FilterItemDto> = emptyList(),
    val languages: List<FilterItemDto> = emptyList(),
)
