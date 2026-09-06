package eu.kanade.tachiyomi.extension.all.mangamillion

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.decodeHex
import keiyoushi.utils.parseAsProto
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Source
abstract class MangaMillion : KeiSource() {

    private val apiUrl = "https://api.mangamillion.shueisha.co.jp"

    private var cachedToken: String? = null

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::authInterceptor)
        addInterceptor(::imageDecryptInterceptor)
        rateLimit(2)
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "api.mangamillion.shueisha.co.jp") {
            return chain.proceed(request)
        }

        val token = synchronized(this) {
            cachedToken ?: registerDeviceToken()
        }

        val newRequest = request.newBuilder()
            .header("Access-Token", token)
            .build()

        val response = chain.proceed(newRequest)
        if (response.code == 422 || response.code == 401) {
            response.close()
            val freshToken = synchronized(this) {
                registerDeviceToken()
            }
            val retryRequest = request.newBuilder()
                .header("Access-Token", freshToken)
                .build()
            return chain.proceed(retryRequest)
        }
        return response
    }

    private fun registerDeviceToken(): String {
        val req = POST(
            "$apiUrl/api/register?service_language=$lang",
            headers,
            "".toRequestBody(null),
        )
        val resp = network.client.newCall(req).execute()
        val proto = resp.parseAsProto<MangaMillionResponse>()
        val token = proto.deviceTokenRegister?.token
            ?: throw Exception("Failed to get device token from Manga Million")
        cachedToken = token
        return token
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.get("$apiUrl/api/home?service_language=$lang&avif_enable=false")
            .parseAsProto<MangaMillionResponse>()

        val titles = response.homeView?.titles.orEmpty()
        return MangasPage(titles.map { it.toSManga() }, hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.get("$apiUrl/api/manga_list?service_language=$lang")
            .parseAsProto<MangaMillionResponse>()

        val items = response.mangaList?.items.orEmpty()
        val mangas = items
            .map { it.originalTitle }
            .filter {
                query.isEmpty() ||
                    it.serviceTitleName.contains(query, ignoreCase = true) ||
                    it.authorName.contains(query, ignoreCase = true)
            }
            .map { it.toSManga() }

        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val titleId = manga.url.substringAfterLast("/")
        val response = client.get("$apiUrl/api/title_detail?original_title_id=$titleId&service_language=$lang")
            .parseAsProto<MangaMillionResponse>()

        val detail = response.titleDetail?.serviceTitle
            ?: throw Exception("Title details not found")

        val updatedManga = manga.apply {
            title = detail.serviceTitleName.ifEmpty { manga.title }
            author = detail.authorName.ifEmpty { manga.author }
            description = detail.description
            genre = detail.tags.joinToString(", ") { it.name }
            thumbnail_url = detail.coverUrl.ifEmpty { manga.thumbnail_url }
        }

        val nextCh = response.titleDetail?.nextChapter
        val single = nextCh?.toSChapter(titleId)

        val chListResp = try {
            client.get("$apiUrl/api/chapter_list?original_title_id=$titleId&service_language=$lang")
                .parseAsProto<MangaMillionResponse>()
        } catch (_: Exception) {
            null
        }

        val chapterList = chListResp?.chapterList?.chapterGroups.orEmpty()
            .flatMap { it.chapters }
            .map { it.toSChapter(titleId) }

        val finalChapters = if (chapterList.isNotEmpty()) chapterList else listOfNotNull(single)
        return SMangaUpdate(updatedManga, finalChapters)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/$lang" + manga.url

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/$lang" + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")
        val response = client.get("$apiUrl/api/viewer?translated_chapter_id=$chapterId&quality=middle")
            .parseAsProto<MangaMillionResponse>()

        val viewer = response.viewer
            ?: throw Exception("Viewer data not found")

        val key = viewer.aesKey
        val iv = viewer.aesIv

        return viewer.pages.mapIndexed { index, page ->
            val fragment = if (key.isNotEmpty() && iv.isNotEmpty()) "#key=$key&iv=$iv" else ""
            Page(index, imageUrl = page.imageUrl + fragment)
        }
    }

    private fun imageDecryptInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment

        if (fragment.isNullOrEmpty() || !fragment.contains("key=") || !fragment.contains("iv=")) {
            return response
        }

        val keyHex = fragment.substringAfter("key=").substringBefore("&")
        val ivHex = fragment.substringAfter("iv=").substringBefore("&")

        val keyBytes = keyHex.decodeHex()
        val ivBytes = ivHex.decodeHex()

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            val secretKey: Key = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        }

        val rawBytes = response.body.bytes()
        val decryptedBytes = cipher.doFinal(rawBytes)
        val contentType = (response.headers["Content-Type"] ?: "image/jpeg").toMediaTypeOrNull()

        return response.newBuilder()
            .body(Buffer().write(decryptedBytes).asResponseBody(contentType, decryptedBytes.size.toLong()))
            .build()
    }
}
