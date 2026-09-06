package eu.kanade.tachiyomi.extension.all.mangamillion

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class MangaMillionResponse(
    @ProtoNumber(1) val status: Int = 0,
    @ProtoNumber(2) val errorMessage: String? = null,
    @ProtoNumber(10) val homeView: HomeViewResponse? = null,
    @ProtoNumber(22) val mangaList: MangaListViewResponse? = null,
    @ProtoNumber(50) val titleDetail: TitleDetailViewResponse? = null,
    @ProtoNumber(60) val chapterList: ChapterListViewResponse? = null,
    @ProtoNumber(70) val viewer: ViewerViewResponse? = null,
    @ProtoNumber(170) val deviceTokenRegister: DeviceTokenRegisterResponse? = null,
)

@Serializable
class DeviceTokenRegisterResponse(
    @ProtoNumber(1) val token: String = "",
)

@Serializable
class HomeViewResponse(
    @ProtoNumber(1) val titles: List<OriginalTitleSummary> = emptyList(),
    @ProtoNumber(3) val tags: List<Tag> = emptyList(),
)

@Serializable
class MangaListViewResponse(
    @ProtoNumber(1) val items: List<MangaListItem> = emptyList(),
    @ProtoNumber(2) val defaultSortOrder: Int = 0,
)

@Serializable
class MangaListItem(
    @ProtoNumber(1) val originalTitle: OriginalTitleSummary,
    @ProtoNumber(2) val availableLanguageCodes: List<String> = emptyList(),
    @ProtoNumber(3) val titleCount: Int = 0,
)

@Serializable
class OriginalTitleSummary(
    @ProtoNumber(1) val originalTitleId: Int,
    @ProtoNumber(2) val thumbnailUrl: String = "",
    @ProtoNumber(3) val serviceTitleName: String = "",
    @ProtoNumber(4) val authorName: String = "",
    @ProtoNumber(5) val topCutUrl: String = "",
    @ProtoNumber(6) val colorCode: String = "",
    @ProtoNumber(7) val viewCount: Long = 0,
    @ProtoNumber(8) val prevDayViewCount: Long = 0,
    @ProtoNumber(9) val updatedAt: Long = 0,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = "/title/$originalTitleId"
        title = serviceTitleName
        author = authorName
        thumbnail_url = thumbnailUrl
    }
}

@Serializable
class TitleDetailViewResponse(
    @ProtoNumber(1) val serviceTitle: ServiceTitle? = null,
    @ProtoNumber(2) val nextChapter: ChapterInfo? = null,
    @ProtoNumber(3) val readStatus: Int = 0,
    @ProtoNumber(4) val hasServiceLanguageTranslation: Boolean = false,
    @ProtoNumber(5) val isMPlusRegion: Boolean = false,
    @ProtoNumber(6) val publishers: List<Publisher> = emptyList(),
    @ProtoNumber(7) val relatedTitles: List<OriginalTitleSummary> = emptyList(),
    @ProtoNumber(9) val languages: List<Language> = emptyList(),
)

@Serializable
class ServiceTitle(
    @ProtoNumber(1) val coverUrl: String = "",
    @ProtoNumber(2) val serviceTitleName: String = "",
    @ProtoNumber(3) val authorName: String = "",
    @ProtoNumber(4) val tags: List<Tag> = emptyList(),
    @ProtoNumber(5) val rating: Rating? = null,
    @ProtoNumber(6) val disclaimerText: String? = null,
    @ProtoNumber(7) val description: String = "",
    @ProtoNumber(8) val pageCutImageUrls: List<String> = emptyList(),
    @ProtoNumber(9) val language: Language? = null,
)

@Serializable
class ChapterListViewResponse(
    @ProtoNumber(1) val totalChapters: Int = 0,
    @ProtoNumber(2) val chapterGroups: List<ChapterGroup> = emptyList(),
    @ProtoNumber(3) val isMPlusRegion: Boolean = false,
    @ProtoNumber(4) val showMpBanner: Boolean = false,
    @ProtoNumber(5) val currentChapter: ChapterInfo? = null,
    @ProtoNumber(6) val readStatus: Int = 0,
    @ProtoNumber(7) val availableChapters: Int = 0,
)

@Serializable
class ChapterGroup(
    @ProtoNumber(1) val groupType: Int = 0,
    @ProtoNumber(2) val chapters: List<ChapterInfo> = emptyList(),
)

@Serializable
class ChapterInfo(
    @ProtoNumber(1) val number: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val translatedChapterId: Int = 0,
    @ProtoNumber(4) val commentCount: Int = 0,
    @ProtoNumber(5) val thumbnailUrl: String = "",
    @ProtoNumber(6) val read: Boolean = false,
) {
    fun toSChapter(titleId: String): SChapter = SChapter.create().apply {
        url = "/title/$titleId/chapter/$translatedChapterId"
        name = this@ChapterInfo.name.ifEmpty { "Chapter $number" }
        chapter_number = number.toFloatOrNull() ?: -1f
    }
}

@Serializable
class ViewerViewResponse(
    @ProtoNumber(1) val pages: List<ViewerPage> = emptyList(),
    @ProtoNumber(2) val chapter: ViewerChapter? = null,
    @ProtoNumber(3) val rating: Rating? = null,
    @ProtoNumber(4) val disclaimerText: String? = null,
    @ProtoNumber(5) val isLatestChapter: Boolean = false,
    @ProtoNumber(6) val publishers: List<Publisher> = emptyList(),
    @ProtoNumber(7) val aesKey: String = "",
    @ProtoNumber(8) val aesIv: String = "",
    @ProtoNumber(9) val maxImageQuality: Int = 0,
    @ProtoNumber(10) val isFromJapan: Boolean = false,
    @ProtoNumber(11) val isSnsShareAllowed: Boolean = false,
    @ProtoNumber(12) val isMPlusRegion: Boolean = false,
)

@Serializable
class ViewerPage(
    @ProtoNumber(1) val imageUrl: String = "",
    @ProtoNumber(2) val widthPx: Int = 0,
    @ProtoNumber(3) val heightPx: Int = 0,
    @ProtoNumber(4) val pageType: Int = 0,
)

@Serializable
class ViewerChapter(
    @ProtoNumber(1) val serviceTitleName: String = "",
    @ProtoNumber(2) val originalTitleId: Int = 0,
    @ProtoNumber(3) val language: Language? = null,
    @ProtoNumber(4) val number: String = "",
    @ProtoNumber(5) val prevId: Int? = null,
    @ProtoNumber(6) val nextId: Int? = null,
    @ProtoNumber(7) val commentCount: Int = 0,
    @ProtoNumber(8) val firstPagePosition: Int = 0,
    @ProtoNumber(9) val scrollDirection: Int = 0,
)

@Serializable
class Tag(
    @ProtoNumber(1) val id: Int,
    @ProtoNumber(2) val name: String,
)

@Serializable
class Rating(
    @ProtoNumber(1) val id: Int = 0,
    @ProtoNumber(2) val name: String = "",
)

@Serializable
class Language(
    @ProtoNumber(1) val localName: String = "",
    @ProtoNumber(2) val serviceName: String = "",
    @ProtoNumber(3) val code: String = "",
    @ProtoNumber(4) val count: Int = 0,
)

@Serializable
class Publisher(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(3) val countryName: String = "",
)
