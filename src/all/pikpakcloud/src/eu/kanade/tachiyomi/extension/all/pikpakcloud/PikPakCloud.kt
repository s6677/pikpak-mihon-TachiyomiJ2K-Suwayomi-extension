package eu.kanade.tachiyomi.extension.all.pikpakcloud

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.zip.readZipEntry
import keiyoushi.zip.zipDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@Source
abstract class PikPakCloud :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val serverAddress: String
        get() = normalizeServer(preferences.getString(PREF_SERVER, DEFAULT_SERVER).orEmpty())

    private val username: String
        get() = preferences.getString(PREF_USERNAME, "")?.trim().orEmpty()

    private val password: String
        get() = preferences.getString(PREF_PASSWORD, "").orEmpty()

    private val rootPath: String
        get() = normalizeDirectory(preferences.getString(PREF_ROOT, DEFAULT_ROOT).orEmpty())

    private val rawClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // Cache only ZIP central-directory metadata / entry readers.
    // Mihon/J2K still owns page preloading and image caching.
    // This prevents every prefetched page from re-reading the same remote ZIP directory.
    private val archiveIndexMutex = Mutex()
    private val archiveIndexCache = LinkedHashMap<String, ArchiveIndex>(MAX_ARCHIVE_CACHE, 0.75f, true)

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host != PAGE_PROXY_HOST || request.url.encodedPath != PAGE_PROXY_PATH) {
            return@addInterceptor chain.proceed(request)
        }

        val archivePath = request.url.queryParameter("archive")
            ?.let(::decodeToken)
            ?: throw IOException("缺少 archive 参数")
        val entryName = request.url.queryParameter("entry")
            ?.let(::decodeToken)
            ?: throw IOException("缺少 entry 参数")

        val bytes = runBlocking {
            val index = getArchiveIndex(archivePath)
            val reader = index.imageReaders[entryName]
                ?: throw IOException("ZIP 内找不到图片：$entryName")
            reader()
        }

        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", guessMediaType(entryName))
            .body(bytes.toResponseBody(guessMediaType(entryName).toMediaTypeOrNull()))
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(loadArchives().map(::archiveToManga), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val needle = normalizeSearch(query)
        val items = loadArchives()
            .filter { needle.isBlank() || normalizeSearch(cleanArchiveTitle(it.name)).contains(needle) }
            .map(::archiveToManga)

        return MangasPage(items, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = null

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) mangaDetails(manga) else manga
        val updatedChapters = if (fetchChapters) chapterList(manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val ref = decodeChapterRef(chapter.url)
        val archiveIndex = getArchiveIndex(ref.archivePath)

        val pages = archiveIndex.entryNames
            .asSequence()
            .filter(::isImage)
            .filter { parentPath(it) == ref.folder }
            .sortedWith { a, b -> naturalCompare(a.substringAfterLast('/'), b.substringAfterLast('/')) }
            .toList()

        return pages.mapIndexed { index, entryName ->
            val imageUrl = HttpUrl.Builder()
                .scheme("https")
                .host(PAGE_PROXY_HOST)
                .addPathSegment(PAGE_PROXY_PATH.removePrefix("/"))
                .addQueryParameter("archive", encodeToken(ref.archivePath))
                .addQueryParameter("entry", encodeToken(entryName))
                .build()
                .toString()

            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getMangaUrl(manga: SManga): String = webDavUrl(decodeArchiveRef(manga.url)).toString()

    override fun getChapterUrl(chapter: SChapter): String = webDavUrl(decodeChapterRef(chapter.url).archivePath).toString()

    private fun mangaDetails(manga: SManga): SManga {
        val path = decodeArchiveRef(manga.url)
        val filename = path.substringAfterLast('/').ifBlank { manga.title }

        return manga.apply {
            title = cleanArchiveTitle(filename)
            status = if (filename.contains("完结", true) || filename.contains("完結", true)) {
                SManga.COMPLETED
            } else {
                SManga.UNKNOWN
            }
            description = "PikPak WebDAV 云端 ZIP/CBZ 漫画。图片通过 HTTP Range 按需读取，不需要先下载整个压缩包。 / Read your own PikPak ZIP/CBZ manga through WebDAV with HTTP Range access; the whole archive does not need to be downloaded first."
        }
    }

    private suspend fun chapterList(manga: SManga): List<SChapter> {
        val archivePath = decodeArchiveRef(manga.url)
        val archiveIndex = getArchiveIndex(archivePath)

        val folders = archiveIndex.entryNames
            .asSequence()
            .map { it.replace('\\', '/') }
            .filter(::isImage)
            .filterNot { it.startsWith("__MACOSX/") || it.contains("/.DS_Store") }
            .map(::parentPath)
            .distinct()
            .sortedWith { a, b -> naturalCompare(b, a) }
            .toList()

        return folders.mapIndexed { index, folder ->
            val displayName = folder.substringAfterLast('/').ifBlank { "整本" }
            SChapter.create().apply {
                url = encodeChapterRef(archivePath, folder)
                name = displayName
                chapter_number = extractChapterNumber(displayName) ?: (folders.size - index).toFloat()
            }
        }
    }

    private suspend fun getArchiveIndex(archivePath: String): ArchiveIndex {
        val key = archiveCacheKey(archivePath)
        synchronized(archiveIndexCache) {
            archiveIndexCache[key]
        }?.let { return it }

        return archiveIndexMutex.withLock {
            synchronized(archiveIndexCache) {
                archiveIndexCache[key]
            }?.let { return@withLock it }

            val archiveUrl = webDavUrl(archivePath).toString()
            val authHeaders = davHeaders()
            val directory = rawClient.zipDirectory(archiveUrl, authHeaders)

            val entryNames = mutableListOf<String>()
            val imageReaders = HashMap<String, suspend () -> ByteArray>()

            directory.entries.forEach { entry ->
                val entryName = entry.name
                entryNames += entryName
                if (isImage(entryName)) {
                    imageReaders[entryName] = {
                        rawClient.readZipEntry(archiveUrl, entry, authHeaders).buffer().readByteArray()
                    }
                }
            }

            val index = ArchiveIndex(entryNames, imageReaders)
            synchronized(archiveIndexCache) {
                archiveIndexCache[key] = index
                while (archiveIndexCache.size > MAX_ARCHIVE_CACHE) {
                    val eldestKey = archiveIndexCache.entries.iterator().next().key
                    archiveIndexCache.remove(eldestKey)
                }
            }
            index
        }
    }

    private fun archiveCacheKey(archivePath: String): String = buildString {
        append(serverAddress)
        append('|')
        append(username)
        append('|')
        append(password.hashCode())
        append('|')
        append(archivePath)
    }

    private fun loadArchives(): List<DavItem> {
        requireCredentials()
        return listDav(rootPath)
            .asSequence()
            .filterNot { it.isDirectory }
            .filter { isArchive(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .toList()
    }

    private fun archiveToManga(item: DavItem): SManga = SManga.create().apply {
        url = encodeArchiveRef(item.path)
        title = cleanArchiveTitle(item.name)
        status = if (item.name.contains("完结", true) || item.name.contains("完結", true)) {
            SManga.COMPLETED
        } else {
            SManga.UNKNOWN
        }
    }

    private fun listDav(directory: String): List<DavItem> {
        val normalizedDir = normalizeDirectory(directory)
        val requestBody = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname />
                <d:resourcetype />
                <d:getcontentlength />
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(webDavUrl(normalizedDir))
            .headers(davHeaders())
            .header("Depth", "1")
            .method("PROPFIND", requestBody)
            .build()

        rawClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WebDAV 连接失败：HTTP ${response.code}")
            }

            val parsed = parseDavXml(response.body.string())
            return parsed.mapNotNull { raw ->
                var name = raw.displayName.trim()
                if (name.isBlank()) {
                    name = raw.href.substringBefore('?').trimEnd('/').substringAfterLast('/')
                }
                name = decodePercent(name)
                if (name.isBlank()) return@mapNotNull null

                val itemPath = normalizedDir + name + if (raw.isDirectory) "/" else ""
                if (itemPath.trimEnd('/') == normalizedDir.trimEnd('/')) return@mapNotNull null

                DavItem(name, itemPath, raw.isDirectory)
            }.distinctBy { it.path }
        }
    }

    private fun parseDavXml(xml: String): List<RawDavItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val responses = document.getElementsByTagNameNS("*", "response")
        val result = ArrayList<RawDavItem>(responses.length)

        for (index in 0 until responses.length) {
            val response = responses.item(index) as? Element ?: continue
            val href = firstElementText(response, "href")
            val displayName = firstElementText(response, "displayname")
            val resourceType = response.getElementsByTagNameNS("*", "resourcetype").item(0) as? Element
            val isDirectory = resourceType
                ?.getElementsByTagNameNS("*", "collection")
                ?.length
                ?.let { it > 0 }
                ?: false

            result += RawDavItem(
                href = href,
                displayName = displayName,
                isDirectory = isDirectory,
            )
        }

        return result
    }

    private fun firstElementText(parent: Element, localName: String): String {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return ""
        return nodes.item(0)?.textContent.orEmpty()
    }

    private fun decodePercent(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun davHeaders(): Headers {
        requireCredentials()
        return Headers.Builder()
            .add("Authorization", Credentials.basic(username, password))
            .add("Accept-Encoding", "identity")
            .build()
    }

    private fun requireCredentials() {
        if (username.isBlank() || password.isBlank()) {
            throw IOException("请先打开 PikPak Cloud 来源设置并填写 WebDAV 用户名和密码。 / Open PikPak Cloud source settings and enter your WebDAV username and password.")
        }
    }

    private fun webDavUrl(path: String): HttpUrl {
        val builder = serverAddress.toHttpUrl().newBuilder()
        val normalized = path.trim('/')

        if (normalized.isNotBlank()) {
            normalized.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        }
        if (path.endsWith('/')) builder.addPathSegment("")

        return builder.build()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPreference = EditTextPreference(screen.context).apply {
            key = PREF_SERVER
            title = "WebDAV 地址 / WebDAV URL"
            summary = """
                中文：先在 PikPak「设置 → 访问与集成 → WebDAV」启用 WebDAV 并创建凭证。通常保持默认地址：https://dav.pikpak.ai/
                English: In PikPak, open Settings → Access & Integrations → WebDAV, enable WebDAV and create credentials. Usually keep the default URL: https://dav.pikpak.ai/
            """.trimIndent()
            setDefaultValue(DEFAULT_SERVER)
        }

        val usernamePreference = EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = "WebDAV 用户名 / WebDAV Username"
            summary = """
                中文：填写 PikPak WebDAV 页面显示的用户名，不是 PikPak 登录邮箱或手机号。
                English: Enter the username shown on the PikPak WebDAV page, not your PikPak login email or phone number.
            """.trimIndent()
            setDefaultValue("")
        }

        val passwordPreference = EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "WebDAV 密码 / WebDAV Password"
            summary = """
                中文：填写 PikPak WebDAV 页面生成的密码，不是你的 PikPak 账号登录密码。不要把这个密码分享给别人。
                English: Enter the password generated on the PikPak WebDAV page, not your PikPak account password. Do not share this credential.
            """.trimIndent()
            setDefaultValue("")
            try {
                setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            } catch (_: RuntimeException) {
                // Suwayomi runs Android preference stubs on the JVM.
            }
        }

        val rootPreference = EditTextPreference(screen.context).apply {
            key = PREF_ROOT
            title = "漫画根目录 / Manga Root Folder"
            summary = """
                中文：填写 PikPak 中存放漫画 ZIP/CBZ 的文件夹路径，例如 /漫画/。插件只列出该目录第一层的 ZIP/CBZ，每个压缩包作为一本漫画。填写 / 可扫描 PikPak 根目录第一层。
                English: Enter the PikPak folder containing your manga ZIP/CBZ files, for example /漫画/. Only ZIP/CBZ files directly inside this folder are listed; each archive becomes one manga. Use / to scan the first level of your PikPak root.
            """.trimIndent()
            setDefaultValue(DEFAULT_ROOT)
        }

        screen.addPreference(serverPreference)
        screen.addPreference(usernamePreference)
        screen.addPreference(passwordPreference)
        screen.addPreference(rootPreference)
    }

    private fun normalizeServer(value: String): String {
        var normalized = value.trim()
        if (normalized.isBlank()) normalized = DEFAULT_SERVER
        if (!normalized.startsWith("http://", true) && !normalized.startsWith("https://", true)) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/') + "/"
    }

    private fun normalizeDirectory(value: String): String {
        val stripped = value.trim().replace('\\', '/').trim('/')
        return if (stripped.isBlank()) "/" else "/$stripped/"
    }

    private fun isArchive(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".zip") || lower.endsWith(".cbz")
    }

    private fun cleanArchiveTitle(filename: String): String = filename
        .replace(ARCHIVE_EXTENSION_REGEX, "")
        .trim()

    private fun normalizeSearch(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private fun isImage(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "gif", "avif", "jxl" -> true
        else -> false
    }

    private fun parentPath(name: String): String = name.substringBeforeLast('/', "")

    private fun extractChapterNumber(name: String): Float? = NUMBER_REGEX
        .findAll(name)
        .lastOrNull()
        ?.value
        ?.toFloatOrNull()

    private fun naturalCompare(a: String, b: String): Int {
        val aa = NATURAL_TOKEN_REGEX.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val bb = NATURAL_TOKEN_REGEX.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        val count = minOf(aa.size, bb.size)

        for (i in 0 until count) {
            val left = aa[i]
            val right = bb[i]
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val comparison = if (leftNumber != null && rightNumber != null) {
                leftNumber.compareTo(rightNumber)
            } else {
                left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }

        return aa.size.compareTo(bb.size)
    }

    private fun encodeArchiveRef(path: String): String = "pikpak:${encodeToken(path)}"

    private fun decodeArchiveRef(ref: String): String {
        if (!ref.startsWith("pikpak:")) throw IOException("无效的漫画引用")
        return decodeToken(ref.removePrefix("pikpak:"))
    }

    private fun encodeChapterRef(archivePath: String, folder: String): String = "pikpakchapter:${encodeToken(archivePath)}:${encodeToken(folder)}"

    private fun decodeChapterRef(ref: String): ChapterRef {
        if (!ref.startsWith("pikpakchapter:")) throw IOException("无效的章节引用")
        val body = ref.removePrefix("pikpakchapter:")
        val split = body.indexOf(':')
        if (split < 0) throw IOException("无效的章节引用")

        return ChapterRef(
            archivePath = decodeToken(body.substring(0, split)),
            folder = decodeToken(body.substring(split + 1)),
        )
    }

    private fun encodeToken(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeToken(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private fun guessMediaType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "jxl" -> "image/jxl"
        else -> "application/octet-stream"
    }

    private class ArchiveIndex(
        val entryNames: List<String>,
        val imageReaders: Map<String, suspend () -> ByteArray>,
    )

    private class RawDavItem(
        var href: String = "",
        var displayName: String = "",
        var isDirectory: Boolean = false,
    )

    private class DavItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
    )

    private class ChapterRef(
        val archivePath: String,
        val folder: String,
    )

    companion object {
        private const val PREF_SERVER = "webdav_server"
        private const val PREF_USERNAME = "webdav_username"
        private const val PREF_PASSWORD = "webdav_password"
        private const val PREF_ROOT = "manga_root"
        private const val DEFAULT_SERVER = "https://dav.pikpak.ai/"
        private const val DEFAULT_ROOT = "/漫画/"

        private const val PAGE_PROXY_HOST = "127.0.0.1"
        private const val PAGE_PROXY_PATH = "/pikpak-page"
        private const val MAX_ARCHIVE_CACHE = 3

        private val ARCHIVE_EXTENSION_REGEX = Regex("(?i)\\.(zip|cbz)$")
        private val NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)")
        private val NATURAL_TOKEN_REGEX = Regex("\\d+|\\D+")
    }
}
