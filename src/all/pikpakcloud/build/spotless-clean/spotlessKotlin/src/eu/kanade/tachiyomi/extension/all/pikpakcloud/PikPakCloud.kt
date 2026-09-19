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

    // Cache ZIP central-directory metadata only.
    // Mihon/J2K/Suwayomi still owns page preloading and normal image caching.
    private val archiveIndexMutex = Mutex()
    private val archiveIndexCache = LinkedHashMap<String, ArchiveIndex>(MAX_ARCHIVE_CACHE, 0.75f, true)

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host != PAGE_PROXY_HOST || request.url.encodedPath != PAGE_PROXY_PATH) {
            return@addInterceptor chain.proceed(request)
        }

        val directFile = request.url.queryParameter("file")?.let(::decodeToken)

        val payload = if (directFile != null) {
            fetchDirectImage(directFile)
        } else {
            val archivePath = request.url.queryParameter("archive")
                ?.let(::decodeToken)
                ?: throw IOException("缺少 archive 参数 / Missing archive parameter")
            val entryName = request.url.queryParameter("entry")
                ?.let(::decodeToken)
                ?: throw IOException("缺少 entry 参数 / Missing entry parameter")

            val bytes = runBlocking {
                val index = getArchiveIndex(archivePath)
                val reader = index.imageReaders[entryName]
                    ?: throw IOException("ZIP 内找不到图片 / Image not found in ZIP: $entryName")
                reader()
            }
            ProxyPayload(bytes, guessMediaType(entryName))
        }

        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", payload.mediaType)
            .body(payload.bytes.toResponseBody(payload.mediaType.toMediaTypeOrNull()))
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(loadLibraryItems().map(::libraryItemToManga), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val needle = normalizeSearch(query)
        val items = loadLibraryItems()
            .filter { needle.isBlank() || normalizeSearch(cleanItemTitle(it.name)).contains(needle) }
            .map(::libraryItemToManga)

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

        return when (ref.kind) {
            ChapterKind.ARCHIVE_FOLDER -> {
                val archiveIndex = getArchiveIndex(ref.path)
                val pages = archiveIndex.entryNames
                    .asSequence()
                    .filter { isImage(it) }
                    .filterNot { isIgnoredPath(it) }
                    .filter { parentPath(it) == ref.folder }
                    .sortedWith { a, b ->
                        naturalCompare(a.substringAfterLast('/'), b.substringAfterLast('/'))
                    }
                    .toList()

                pages.mapIndexed { index, entryName ->
                    Page(index, imageUrl = archivePageUrl(ref.path, entryName))
                }
            }

            ChapterKind.DIRECTORY -> {
                val pages = listDav(ref.path)
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .filterNot { isIgnoredName(it.name) }
                    .filter { isImage(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                    .toList()

                pages.mapIndexed { index, item ->
                    Page(index, imageUrl = directPageUrl(item.path))
                }
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = webDavUrl(decodeMangaRef(manga.url).path).toString()

    override fun getChapterUrl(chapter: SChapter): String = webDavUrl(decodeChapterRef(chapter.url).path).toString()

    private fun mangaDetails(manga: SManga): SManga {
        val ref = decodeMangaRef(manga.url)
        val filename = ref.path.trimEnd('/').substringAfterLast('/').ifBlank { manga.title }

        return manga.apply {
            title = cleanItemTitle(filename)
            status = if (filename.contains("完结", true) || filename.contains("完結", true)) {
                SManga.COMPLETED
            } else {
                SManga.UNKNOWN
            }
            description = when (ref.kind) {
                MangaKind.ARCHIVE ->
                    "PikPak 云端 ZIP/CBZ 漫画。插件自动识别压缩包内的卷/章节并通过 HTTP Range 按页读取。 / PikPak ZIP/CBZ manga. Volumes and chapters are detected automatically and pages are read on demand with HTTP Range."

                MangaKind.DIRECTORY ->
                    "PikPak 云端漫画文件夹。插件会自动识别图片目录、子目录以及目录内的 ZIP/CBZ。 / PikPak manga folder. Image folders, nested folders, and ZIP/CBZ archives inside the folder are detected automatically."
            }
        }
    }

    private suspend fun chapterList(manga: SManga): List<SChapter> {
        val ref = decodeMangaRef(manga.url)

        val descriptors = when (ref.kind) {
            MangaKind.ARCHIVE -> archiveChapterDescriptors(ref.path)
            MangaKind.DIRECTORY -> directoryChapterDescriptors(ref.path)
        }

        val sorted = descriptors
            .distinctBy { encodeChapterRef(it) }
            .sortedWith { a, b -> naturalCompare(b.displayName, a.displayName) }

        return sorted.mapIndexed { index, descriptor ->
            SChapter.create().apply {
                url = encodeChapterRef(descriptor)
                name = descriptor.displayName
                chapter_number = extractChapterNumber(descriptor.displayName) ?: (sorted.size - index).toFloat()
            }
        }
    }

    private suspend fun archiveChapterDescriptors(archivePath: String): List<ChapterDescriptor> {
        val archiveTitle = cleanItemTitle(archivePath.substringAfterLast('/'))
        val groups = archiveFolderGroups(archivePath)

        if (groups.isEmpty()) return emptyList()

        return groups.map { group ->
            val display = if (
                groups.size == 1 &&
                (
                    group.folder.isBlank() ||
                        normalizeSearch(group.displayName) == normalizeSearch(archiveTitle)
                    )
            ) {
                FULL_BOOK
            } else {
                group.displayName.ifBlank { FULL_BOOK }
            }

            ChapterDescriptor(
                kind = ChapterKind.ARCHIVE_FOLDER,
                path = archivePath,
                folder = group.folder,
                displayName = display,
                pageCountHint = group.pageCount,
            )
        }
    }

    private suspend fun directoryChapterDescriptors(mangaRoot: String): List<ChapterDescriptor> {
        val collected = mutableListOf<ChapterDescriptor>()
        val visited = HashSet<String>()

        collectDirectoryChapters(
            mangaRoot = normalizeDirectory(mangaRoot),
            currentPath = normalizeDirectory(mangaRoot),
            depth = 0,
            out = collected,
            visited = visited,
        )

        var result = collected.distinctBy { encodeChapterRef(it) }

        // A few loose images next to real chapter folders are usually cover/poster files.
        if (result.size > 1) {
            result = result.filterNot {
                it.kind == ChapterKind.DIRECTORY &&
                    normalizeDirectory(it.path) == normalizeDirectory(mangaRoot) &&
                    it.pageCountHint in 1..MAX_ROOT_COVER_IMAGES
            }
        }

        if (result.isEmpty()) return emptyList()

        if (result.size == 1) {
            return result.map { it.copy(displayName = FULL_BOOK) }
        }

        return stripCommonChapterPrefix(result)
    }

    private suspend fun collectDirectoryChapters(
        mangaRoot: String,
        currentPath: String,
        depth: Int,
        out: MutableList<ChapterDescriptor>,
        visited: MutableSet<String>,
    ) {
        if (depth > MAX_DIRECTORY_DEPTH) return

        val normalizedCurrent = normalizeDirectory(currentPath)
        if (!visited.add(normalizedCurrent)) return

        val items = listDav(normalizedCurrent)
            .filterNot { isIgnoredName(it.name) }

        val images = items
            .filterNot { it.isDirectory }
            .filter { isImage(it.name) }

        if (images.isNotEmpty()) {
            val relative = relativeDirectoryLabel(mangaRoot, normalizedCurrent)
            out += ChapterDescriptor(
                kind = ChapterKind.DIRECTORY,
                path = normalizedCurrent,
                folder = "",
                displayName = relative.ifBlank { FULL_BOOK },
                pageCountHint = images.size,
            )
        }

        val archives = items
            .filterNot { it.isDirectory }
            .filter { isArchive(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }

        for (archive in archives) {
            val groups = archiveFolderGroups(archive.path)
            if (groups.isEmpty()) continue

            val relativeParent = relativeDirectoryLabel(mangaRoot, normalizedCurrent)
            val archiveTitle = cleanItemTitle(archive.name)

            if (groups.size == 1) {
                out += ChapterDescriptor(
                    kind = ChapterKind.ARCHIVE_FOLDER,
                    path = archive.path,
                    folder = groups.first().folder,
                    displayName = joinChapterLabel(relativeParent, archiveTitle),
                    pageCountHint = groups.first().pageCount,
                )
            } else {
                groups.forEach { group ->
                    val groupName = group.displayName.ifBlank { FULL_BOOK }
                    out += ChapterDescriptor(
                        kind = ChapterKind.ARCHIVE_FOLDER,
                        path = archive.path,
                        folder = group.folder,
                        displayName = joinChapterLabel(relativeParent, "$archiveTitle/$groupName"),
                        pageCountHint = group.pageCount,
                    )
                }
            }
        }

        if (depth >= MAX_DIRECTORY_DEPTH) return

        val directories = items
            .filter { it.isDirectory }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }

        for (directory in directories) {
            collectDirectoryChapters(
                mangaRoot = mangaRoot,
                currentPath = directory.path,
                depth = depth + 1,
                out = out,
                visited = visited,
            )
        }
    }

    private suspend fun archiveFolderGroups(archivePath: String): List<ArchiveFolderGroup> {
        val index = getArchiveIndex(archivePath)

        val grouped = index.entryNames
            .asSequence()
            .filter { isImage(it) }
            .filterNot { isIgnoredPath(it) }
            .groupBy { parentPath(it) }
            .toMutableMap()

        // If an archive has real chapter folders plus only 1-3 images at its root,
        // those root images are commonly cover/poster files rather than a chapter.
        if (grouped.size > 1 && (grouped[""]?.size ?: 0) in 1..MAX_ROOT_COVER_IMAGES) {
            grouped.remove("")
        }

        if (grouped.isEmpty()) return emptyList()

        val folders = grouped.keys.sortedWith { a, b -> naturalCompare(a, b) }
        val displayNames = shortenedFolderNames(folders)

        return folders.map { folder ->
            ArchiveFolderGroup(
                folder = folder,
                displayName = displayNames[folder].orEmpty(),
                pageCount = grouped[folder]?.size ?: 0,
            )
        }
    }

    private fun shortenedFolderNames(folders: List<String>): Map<String, String> {
        if (folders.isEmpty()) return emptyMap()

        if (folders.size == 1) {
            val only = folders.first()
            return mapOf(
                only to only.substringAfterLast('/').ifBlank { FULL_BOOK },
            )
        }

        val nonEmptyParts = folders.map { folder ->
            folder.split('/').filter { it.isNotBlank() }
        }

        var commonCount = 0
        while (true) {
            val candidate = nonEmptyParts.firstOrNull()?.getOrNull(commonCount) ?: break
            if (nonEmptyParts.all { parts ->
                    parts.size > commonCount + 1 && parts.getOrNull(commonCount) == candidate
                }
            ) {
                commonCount++
            } else {
                break
            }
        }

        return folders.associateWith { folder ->
            if (folder.isBlank()) {
                FULL_BOOK
            } else {
                val parts = folder.split('/').filter { it.isNotBlank() }
                parts.drop(commonCount).joinToString("/").ifBlank {
                    parts.lastOrNull().orEmpty().ifBlank { FULL_BOOK }
                }
            }
        }
    }

    private fun stripCommonChapterPrefix(
        descriptors: List<ChapterDescriptor>,
    ): List<ChapterDescriptor> {
        if (descriptors.size < 2) return descriptors

        val parts = descriptors.map {
            it.displayName.split('/').map { part -> part.trim() }.filter { part -> part.isNotBlank() }
        }

        var commonCount = 0
        while (true) {
            val candidate = parts.firstOrNull()?.getOrNull(commonCount) ?: break
            if (parts.all { value ->
                    value.size > commonCount + 1 && value.getOrNull(commonCount) == candidate
                }
            ) {
                commonCount++
            } else {
                break
            }
        }

        if (commonCount == 0) return descriptors

        return descriptors.mapIndexed { index, descriptor ->
            val stripped = parts[index].drop(commonCount).joinToString("/")
            descriptor.copy(displayName = stripped.ifBlank { descriptor.displayName })
        }
    }

    private fun relativeDirectoryLabel(root: String, current: String): String {
        val normalizedRoot = normalizeDirectory(root)
        val normalizedCurrent = normalizeDirectory(current)

        if (normalizedCurrent == normalizedRoot) return ""

        return normalizedCurrent
            .removePrefix(normalizedRoot)
            .trim('/')
    }

    private fun joinChapterLabel(prefix: String, name: String): String = if (prefix.isBlank()) name else "$prefix/$name"

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
                val entryName = entry.name.replace('\\', '/')
                entryNames += entryName
                if (isImage(entryName) && !isIgnoredPath(entryName)) {
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

    private fun fetchDirectImage(path: String): ProxyPayload {
        val request = Request.Builder()
            .url(webDavUrl(path))
            .headers(davHeaders())
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        rawClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("图片读取失败 / Failed to read image: HTTP ${response.code}")
            }

            val mediaType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.takeIf { it.startsWith("image/", true) }
                ?: guessMediaType(path)

            return ProxyPayload(response.body.bytes(), mediaType)
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

    private fun loadLibraryItems(): List<LibraryItem> {
        requireCredentials()

        return listDav(rootPath)
            .asSequence()
            .filterNot { isIgnoredName(it.name) }
            .filter { it.isDirectory || isArchive(it.name) }
            .map {
                LibraryItem(
                    name = it.name,
                    path = it.path,
                    kind = if (it.isDirectory) MangaKind.DIRECTORY else MangaKind.ARCHIVE,
                )
            }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .toList()
    }

    private fun libraryItemToManga(item: LibraryItem): SManga = SManga.create().apply {
        url = encodeMangaRef(item.kind, item.path)
        title = cleanItemTitle(item.name)
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
                if (response.code == 404 && normalizedDir == rootPath) {
                    throw IOException(
                        "找不到漫画根目录：$normalizedDir。请确认 PikPak 中存在这个路径，并确保扩展里的“漫画根目录”与 PikPak 中的实际路径一致。 / " +
                            "Manga root folder not found: $normalizedDir. Make sure this path exists in PikPak and that the Manga Root Folder setting matches the actual PikPak folder path.",
                    )
                }
                throw IOException("WebDAV 连接失败 / WebDAV request failed: HTTP ${response.code}")
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
            throw IOException(
                "请先打开 PikPak Cloud 来源设置并填写 WebDAV 用户名和密码。 / " +
                    "Open PikPak Cloud source settings and enter your WebDAV username and password.",
            )
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
                中文：默认 /Comics/。你可以使用 PikPak 中任何已有文件夹作为漫画根目录。请确保这里填写的路径与 PikPak 中的实际文件夹路径一致。填写 / 表示直接使用 PikPak 根目录。
                English: Default: /Comics/. You may use any existing PikPak folder as your comic/manga root. Make sure this setting matches the actual folder path in PikPak. Use / to read directly from the PikPak root.
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

    private fun cleanItemTitle(filename: String): String = filename
        .trimEnd('/')
        .replace(ARCHIVE_EXTENSION_REGEX, "")
        .trim()

    private fun normalizeSearch(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private fun isImage(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp", "gif", "avif", "jxl" -> true
        else -> false
    }

    private fun isIgnoredName(name: String): Boolean {
        val value = name.trim().trimEnd('/')
        if (value.isBlank()) return true
        if (value.startsWith(".")) return true

        return value.equals("__MACOSX", true) ||
            value.equals("Thumbs.db", true) ||
            value.equals("desktop.ini", true) ||
            value.equals(".DS_Store", true) ||
            value.equals("@eaDir", true)
    }

    private fun isIgnoredPath(path: String): Boolean = path.replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
        .any { isIgnoredName(it) }

    private fun parentPath(name: String): String = name.replace('\\', '/').substringBeforeLast('/', "")

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

    private fun archivePageUrl(archivePath: String, entryName: String): String = HttpUrl.Builder()
        .scheme("https")
        .host(PAGE_PROXY_HOST)
        .addPathSegment(PAGE_PROXY_PATH.removePrefix("/"))
        .addQueryParameter("archive", encodeToken(archivePath))
        .addQueryParameter("entry", encodeToken(entryName))
        .build()
        .toString()

    private fun directPageUrl(filePath: String): String = HttpUrl.Builder()
        .scheme("https")
        .host(PAGE_PROXY_HOST)
        .addPathSegment(PAGE_PROXY_PATH.removePrefix("/"))
        .addQueryParameter("file", encodeToken(filePath))
        .build()
        .toString()

    private fun encodeMangaRef(kind: MangaKind, path: String): String = "pikpakmanga:${kind.code}:${encodeToken(path)}"

    private fun decodeMangaRef(ref: String): MangaRef {
        // Compatibility with v0.10 and earlier archive refs.
        if (ref.startsWith("pikpak:")) {
            return MangaRef(MangaKind.ARCHIVE, decodeToken(ref.removePrefix("pikpak:")))
        }

        if (!ref.startsWith("pikpakmanga:")) throw IOException("无效的漫画引用 / Invalid manga reference")
        val parts = ref.split(':', limit = 3)
        if (parts.size != 3) throw IOException("无效的漫画引用 / Invalid manga reference")

        return MangaRef(
            kind = MangaKind.fromCode(parts[1]),
            path = decodeToken(parts[2]),
        )
    }

    private fun encodeChapterRef(descriptor: ChapterDescriptor): String = when (descriptor.kind) {
        ChapterKind.ARCHIVE_FOLDER ->
            "pikpakchapter:a:${encodeToken(descriptor.path)}:${encodeToken(descriptor.folder)}"

        ChapterKind.DIRECTORY ->
            "pikpakchapter:d:${encodeToken(descriptor.path)}"
    }

    private fun decodeChapterRef(ref: String): ChapterRef {
        if (!ref.startsWith("pikpakchapter:")) throw IOException("无效的章节引用 / Invalid chapter reference")

        val body = ref.removePrefix("pikpakchapter:")

        // Compatibility with v0.10 and earlier refs:
        // pikpakchapter:<archive-token>:<folder-token>
        if (!body.startsWith("a:") && !body.startsWith("d:")) {
            val split = body.indexOf(':')
            if (split < 0) throw IOException("无效的章节引用 / Invalid chapter reference")
            return ChapterRef(
                kind = ChapterKind.ARCHIVE_FOLDER,
                path = decodeToken(body.substring(0, split)),
                folder = decodeToken(body.substring(split + 1)),
            )
        }

        if (body.startsWith("d:")) {
            return ChapterRef(
                kind = ChapterKind.DIRECTORY,
                path = decodeToken(body.removePrefix("d:")),
                folder = "",
            )
        }

        val archiveBody = body.removePrefix("a:")
        val split = archiveBody.indexOf(':')
        if (split < 0) throw IOException("无效的章节引用 / Invalid chapter reference")

        return ChapterRef(
            kind = ChapterKind.ARCHIVE_FOLDER,
            path = decodeToken(archiveBody.substring(0, split)),
            folder = decodeToken(archiveBody.substring(split + 1)),
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

    private enum class MangaKind(val code: String) {
        ARCHIVE("a"),
        DIRECTORY("d"),
        ;

        companion object {
            fun fromCode(code: String): MangaKind = when (code) {
                "a" -> ARCHIVE
                "d" -> DIRECTORY
                else -> throw IOException("未知漫画类型 / Unknown manga type")
            }
        }
    }

    private enum class ChapterKind {
        ARCHIVE_FOLDER,
        DIRECTORY,
    }

    private data class MangaRef(
        val kind: MangaKind,
        val path: String,
    )

    private data class ChapterRef(
        val kind: ChapterKind,
        val path: String,
        val folder: String,
    )

    private data class LibraryItem(
        val name: String,
        val path: String,
        val kind: MangaKind,
    )

    private data class ChapterDescriptor(
        val kind: ChapterKind,
        val path: String,
        val folder: String,
        val displayName: String,
        val pageCountHint: Int,
    )

    private data class ArchiveFolderGroup(
        val folder: String,
        val displayName: String,
        val pageCount: Int,
    )

    private data class ProxyPayload(
        val bytes: ByteArray,
        val mediaType: String,
    )

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

    companion object {
        private const val PREF_SERVER = "webdav_server"
        private const val PREF_USERNAME = "webdav_username"
        private const val PREF_PASSWORD = "webdav_password"
        private const val PREF_ROOT = "manga_root"
        private const val DEFAULT_SERVER = "https://dav.pikpak.ai/"
        private const val DEFAULT_ROOT = "/Comics/"

        private const val PAGE_PROXY_HOST = "127.0.0.1"
        private const val PAGE_PROXY_PATH = "/pikpak-page"
        private const val MAX_ARCHIVE_CACHE = 24
        private const val MAX_DIRECTORY_DEPTH = 4
        private const val MAX_ROOT_COVER_IMAGES = 3

        private const val FULL_BOOK = "整本 / Full book"

        private val ARCHIVE_EXTENSION_REGEX = Regex("(?i)\\.(zip|cbz)$")
        private val NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)")
        private val NATURAL_TOKEN_REGEX = Regex("\\d+|\\D+")
    }
}
