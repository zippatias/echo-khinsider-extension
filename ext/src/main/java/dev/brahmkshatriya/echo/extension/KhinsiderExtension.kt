package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder

class KhinsiderExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, AlbumClient, TrackClient, LibraryFeedClient, LoginClient.WebView {

    private val client = OkHttpClient()
    private val noRedirectClient = OkHttpClient.Builder().followRedirects(false).build()
    private lateinit var setting: Settings
    private val audioCache = mutableMapOf<String, String>()
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    // ---------- API (mirror) ----------

    private val baseUrl = "https://khinsider.squid.wtf"

    private fun apiUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val params = query.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$baseUrl$path${if (params.isEmpty()) "" else "?$params"}"
    }

    private suspend fun getJson(url: String): JsonElement {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val body = response.body?.string() ?: throw Exception("Risposta vuota")
        return Json.parseToJsonElement(body)
    }

    private suspend fun getText(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return response.body?.string() ?: throw Exception("Risposta vuota")
    }

    private fun imageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val target = raw.replace("/thumbs_small/", "/thumbs/")
        return apiUrl("/api/image", mapOf("url" to target))
    }

    private fun downloadUrl(target: String): String =
        apiUrl("/api/download", mapOf("url" to target))

    // ---------- Helper JSON ----------

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun albumPathOf(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return Regex("/game-soundtracks/album/[^/?#]+").find(value)?.value ?: value
    }

    private fun parseDuration(raw: String?): Long? {
        val parts = raw?.trim()?.split(":")?.mapNotNull { it.toLongOrNull() } ?: return null
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            else -> null
        }
    }

    private fun qualityOf(bitrate: String?): Int {
        val kbps = bitrate?.replace("kbps", "", true)?.trim()?.toIntOrNull()
        return when {
            kbps == null -> 4
            kbps >= 320 -> 6
            kbps >= 192 -> 5
            kbps >= 128 -> 3
            else -> 0
        }
    }

    // ---------- Risoluzione audio ----------

    private suspend fun resolveAudio(pageUrl: String, format: String = "mp3"): String {
        val html = getText(downloadUrl(pageUrl))
        val ext = if (format.equals("flac", true)) "flac" else "mp3"
        val regex = Regex("href=[\"']([^\"']+\\.$ext)[\"']", RegexOption.IGNORE_CASE)
        val candidates = regex.findAll(html).map { it.groupValues[1] }.toList()
        val link = candidates.firstOrNull { it.contains("vgmtreasurechest.com") }
            ?: candidates.firstOrNull()
            ?: throw Exception("Link $ext non trovato nella pagina")
        return if (link.startsWith("http")) link else "https://downloads.khinsider.com$link"
    }

    // ---------- Conversione modelli ----------

    private fun JsonObject.toAlbumItem(): Album? {
        val title = str("title")?.takeIf { it.isNotBlank() } ?: return null
        val id = albumPathOf(str("albumId") ?: str("id") ?: str("url")) ?: return null
        val cover = imageUrl(str("icon") ?: str("image"))?.toImageHolder()
        val subtitle = listOfNotNull(str("albumType"), str("year")).joinToString(" • ").ifBlank { null }
        return Album(id = id, title = title, cover = cover, subtitle = subtitle)
    }

    private fun JsonObject.toAlbumDetails(album: Album): Album {
        val title = str("name") ?: album.title
        val year = str("year")
        val cover = imageUrl(str("coverUrl"))?.toImageHolder() ?: album.cover
        val artistName = str("albumArtist")
        val artists = artistName?.takeIf { it.isNotBlank() }?.let {
            listOf(Artist(id = it, name = it))
        } ?: emptyList()
        val trackCount = runCatching { this["tracks"]?.jsonArray?.size?.toLong() }.getOrNull()
        return Album(
            id = album.id,
            title = title,
            cover = cover,
            artists = artists,
            trackCount = trackCount,
            releaseDate = year?.toIntOrNull()?.let { EchoDate(year = it, month = 1, day = 1) },
            description = str("description") ?: str("albumType"),
            subtitle = year?.let { "Anno: $it" }
        )
    }

    private fun JsonObject.toTracks(album: Album): List<Track> {
        val albumTitle = str("name") ?: album.title
        val cover = imageUrl(str("coverUrl"))?.toImageHolder() ?: album.cover
        val artistName = str("albumArtist")
        val artists = artistName?.takeIf { it.isNotBlank() }?.let {
            listOf(Artist(id = it, name = it))
        } ?: emptyList()
        val albumModel = Album(id = album.id, title = albumTitle, cover = cover)
        val hasFlac = runCatching {
            this["availableFormats"]?.jsonArray?.any {
                it.jsonPrimitive.content.equals("flac", true)
            } ?: false
        }.getOrDefault(false)
        val tracks = runCatching { this["tracks"]?.jsonArray }.getOrNull() ?: return emptyList()
        return tracks.mapNotNull { item ->
            val o = item.jsonObject
            val title = o.str("title") ?: return@mapNotNull null
            val pageUrl = o.str("url") ?: return@mapNotNull null
            val quality = qualityOf(o.str("bitrate"))
            val streamables = listOf(
                Streamable.server(id = pageUrl, quality = quality, title = "MP3")
            ) + if (hasFlac) {
                listOf(Streamable.server(id = "$pageUrl#flac", quality = 7, title = "FLAC"))
            } else emptyList()
            Track(
                id = pageUrl,
                title = title,
                artists = artists,
                album = albumModel,
                cover = cover,
                duration = parseDuration(o.str("duration")),
                albumOrderNumber = o.str("number")?.toLongOrNull(),
                streamables = streamables
            )
        }
    }

    // ---------- LE SEZIONI DEL SITO (nuovo) ----------

    private val KHI = "https://downloads.khinsider.com"

    private val platforms = listOf(
        "NES" to "/game-soundtracks/nintendo-nes",
        "SNES" to "/game-soundtracks/nintendo-snes",
        "N64" to "/game-soundtracks/nintendo-64",
        "GC" to "/game-soundtracks/nintendo-gamecube",
        "Wii" to "/game-soundtracks/nintendo-wii",
        "Wii U" to "/game-soundtracks/nintendo-wii-u",
        "Switch" to "/game-soundtracks/nintendo-switch",
        "Switch 2" to "/game-soundtracks/switch-2",
        "GB" to "/game-soundtracks/gameboy",
        "GBA" to "/game-soundtracks/gameboy-advance",
        "DS" to "/game-soundtracks/nintendo-ds",
        "3DS" to "/game-soundtracks/nintendo-3ds",
        "PS1" to "/game-soundtracks/playstation",
        "PS2" to "/game-soundtracks/playstation-2",
        "PS3" to "/game-soundtracks/playstation-3",
        "PS4" to "/game-soundtracks/playstation-4",
        "PS5" to "/game-soundtracks/playstation-5",
        "PSP" to "/game-soundtracks/playstation-portable-psp",
        "PS Vita" to "/game-soundtracks/playstation-vita",
        "Steam" to "/game-soundtracks/steam",
        "Windows" to "/game-soundtracks/windows",
        "Xbox" to "/game-soundtracks/xbox",
        "Xbox 360" to "/game-soundtracks/xbox-360",
        "Xbox One" to "/game-soundtracks/xbox-one",
    )

    private val types = listOf(
        "Gamerips" to "/game-soundtracks/gamerips",
        "Soundtracks" to "/game-soundtracks/ost",
        "Singles" to "/game-soundtracks/singles",
        "Arrangements" to "/game-soundtracks/arrangements",
        "Remixes" to "/game-soundtracks/remixes",
        "Compilations" to "/game-soundtracks/compilations",
        "Inspired By" to "/game-soundtracks/inspired-by",
    )

    // ---------- Helper scraping ----------

    private val albumLinkRegex =
        Regex("""<a href="/game-soundtracks/album/([^"/]+)/?"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    private suspend fun khinsiderGet(url: String, cookie: String? = null): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        if (cookie != null) builder.header("Cookie", cookie)
        val response = client.newCall(builder.build()).await()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        return response.body?.string() ?: ""
    }

    private suspend fun scrapeAlbumList(url: String, limit: Int = 30, cookie: String? = null): List<Album> {
        val html = runCatching { khinsiderGet(url, cookie) }.getOrDefault("")
        val albums = LinkedHashMap<String, Album>()   // niente duplicati
        for (match in albumLinkRegex.findAll(html)) {
            val slug = match.groupValues[1].trim()
            if (slug.isBlank()) continue
            val inner = match.groupValues[2]
            val title = Regex("""<[^>]+>""").replace(inner, " ")
                .replace(Regex("""\s+"""), " ").trim()
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            val cover = Regex("""<img[^>]+src="([^"]+)"[^>]*>""").find(inner)?.groupValues?.get(1)
                ?.let { if (it.startsWith("http")) it else "$KHI$it" }
                albums[slug] = Album(
                id = "/game-soundtracks/album/$slug",
                title = title.ifBlank { slug.replace('-', ' ') },
                cover = cover?.let { imageUrl(it)?.toImageHolder() },
            )

            if (albums.size >= limit) break
        }
        return albums.values.toList()
    }

    /** Paginazione di Echo 1.0: Continuous carica le pagine una dopo l'altra */
        private fun <T : Any> continuousPaged(
        loader: suspend (page: Int) -> Pair<List<T>, Boolean>,
    ): PagedData<T> = PagedData.Continuous { key ->
        val page = key?.toIntOrNull() ?: 1
        val (items, hasMore) = loader(page)
        Page(items, if (hasMore) (page + 1).toString() else null)
    }

    /** Pagina "More" di una console/tipo: una sezione per ogni pagina del sito */
    private fun albumsMoreFeed(path: String, cookie: String? = null): Feed<Shelf> =
        Feed(emptyList()) {
            continuousPaged<Shelf> { page ->
                val url = if (page == 1) "$KHI$path" else "$KHI$path?page=$page"
                val items = scrapeAlbumList(url, 30, cookie)
                val shelves = if (items.isEmpty()) emptyList()
                else listOf(
                    Shelf.Lists.Items(
                        id = "$path-p$page",
                        title = if (page == 1) "Album" else "Pagina $page",
                        list = items,
                    )
                )
                shelves to (items.size >= 30)
            }.toFeedData()
        }

    private suspend fun albumsShelf(
        id: String, title: String, path: String,
        preview: Int = 12, cookie: String? = null, paged: Boolean = false,
    ): Shelf? {
        val albums = runCatching { scrapeAlbumList("$KHI$path", preview, cookie) }.getOrDefault(emptyList())
        if (albums.isEmpty()) return null   // sezione vuota o non raggiungibile → non mostrarla
        return Shelf.Lists.Items(
            id = id,
            title = title,
            list = albums,
            more = if (paged) albumsMoreFeed(path, cookie) else null,
        )
    }

    /** Le 24 console caricate a gruppi di 8 (scroll infinito tra le console) */
    private fun pagedConsoleShelves(): PagedData<Shelf> = continuousPaged { page ->
        val start = (page - 1) * 8
        val end = minOf(start + 8, platforms.size)
        val shelves = platforms.subList(start, end).mapNotNull { (name, path) ->
            runCatching { albumsShelf("console_${path.substringAfterLast('/')}", name, path, 12, paged = true) }
                .getOrNull()
        }
        shelves to (end < platforms.size)
    }

    // ---------- HOME ----------

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("home", "Home"),
            Tab("top", "Top"),
            Tab("console", "Console"),
            Tab("tipo", "Tipo"),
        )
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "top" -> PagedData.Single {
                    listOfNotNull(
                        albumsShelf("top40", "Top 40", "/top40", 40),
                        albumsShelf("top100", "Top 100 All Time", "/all-time-top-100", 100),
                        albumsShelf("top6m", "Top 100 Ultimi 6 Mesi", "/last-6-months-top-100", 100),
                        albumsShelf("topnew", "Top 100 Nuovi", "/top-100-newly-added", 100),
                        albumsShelf("viewed", "Attualmente Visti", "/currently-viewed", 100),
                        albumsShelf("favs", "Più Preferiti", "/most-favorites", 100),
                    )
                }.toFeedData()
                "console" -> pagedConsoleShelves().toFeedData()
                "tipo" -> PagedData.Single {
                    types.mapNotNull { (name, path) ->
                        albumsShelf("type_${path.substringAfterLast('/')}", name, path, 12, paged = true)
                    }
                }.toFeedData()
                else -> PagedData.Single {
                    listOfNotNull(
                        albumsShelf("latest", "Ultimi Arrivi", "/", 20),
                        albumsShelf("topnew", "Top 100 Nuovi", "/top-100-newly-added", 50),
                        albumsShelf("viewed", "Attualmente Visti", "/currently-viewed", 50),
                    )
                }.toFeedData()
            }
        }
    }

    // ---------- LOGIN ----------

    private var user: User? = null
    private var cookie: String? = null

    override val webViewRequest = object : WebViewRequest.Cookie<List<User>> {
        override val dontCache = true

        // Apriamo la pagina di login con ?redirect=/cp/favorites:
        // XenForo precompila il campo nascosto "redirect" della form con questo valore,
        // così dopo un login riuscito il WebView finisce su una pagina che esiste solo
        // da autenticati e il flusso si ferma SOLO a login davvero completato.
        override val initialUrl =
            "https://downloads.khinsider.com/forums/login?redirect=%2Fcp%2Ffavorites".toGetRequest()

        // Si ferma quando lasciamo la pagina di login (login completato o reindirizzato)
        override val stopUrlRegex = Regex("""https://downloads\.khinsider\.com/(?!.*login).*""")

        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            val preview = cookie.take(120)
            if (!cookie.contains("xf_session")) {
                throw Exception(
                    "Login non riuscito: nessuna sessione XenForo ricevuta. " +
                        "Cookie ricevuti: $preview. " +
                        "Assicurati di aver completato il login nella pagina web."
                )
            }

            // Il sito principale potrebbe rilasciare un cookie di sessione proprio:
            // visitiamo la home una volta per raccoglierlo prima della verifica.
            val session = warmUpSession(cookie)
            if (!verifySession(session)) {
                val hint = if (!cookie.contains("xf_user"))
                    " Suggerimento: nella pagina di login spunta \"Stay logged in\" (Resta connesso): " +
                    "senza il cookie xf_user il sito potrebbe non riconoscere la sessione."
                else ""
                throw Exception("Login non riuscito: impossibile verificare la sessione. Cookie ricevuti: $preview.$hint")
            }
            return listOf(
                User(
                    id = "khinsider",
                    name = "Khinsider",
                    subtitle = "Account khinsider",
                    extras = mapOf("cookie" to session),
                )
            )
        }
    }

    override fun setLoginUser(user: User?) {
        this.user = user
        this.cookie = user?.extras?.get("cookie")
    }

    override suspend fun getCurrentUser(): User? = user

/** /cp/favorites: 200 con i contenuti = loggato; 200 con "you need to be registered and logged in" = ospite. */
private suspend fun verifySession(cookie: String): Boolean = runCatching {
    if (!cookie.contains("xf_session")) return@runCatching false
    val request = Request.Builder()
        .url("https://downloads.khinsider.com/cp/favorites")
        .header("User-Agent", UA)
        .header("Cookie", cookie)
        .build()
    val response = noRedirectClient.newCall(request).await()
    val code = response.code
    val body = response.body?.string() ?: ""
    response.close()
    val loggedOut = body.contains("need to be registered and logged in") ||
        body.contains("/forums/login") || body.contains(">Log In")
    code == 200 && !loggedOut
}.getOrDefault(false)

/** Visita la home del sito con i cookie del forum e restituisce i cookie aggiornati
 *  (alcuni siti rilasciano un cookie di sessione proprio sulla prima pagina del sito principale). */
private suspend fun warmUpSession(cookie: String): String = runCatching {
    var current = cookie
    var url = "https://downloads.khinsider.com/"
    repeat(5) {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", current)
            .build()
        val response = noRedirectClient.newCall(request).await()
        val setCookies = response.headers("Set-Cookie").map { it.substringBefore(";") }
        if (setCookies.isNotEmpty()) current = mergeCookies(current, setCookies)
        val location = response.header("Location")
        val code = response.code
        response.close()
        if (code !in 300..399 || location == null) return@runCatching current
        url = if (location.startsWith("http")) location else "https://downloads.khinsider.com$location"
    }
    current
}.getOrDefault(cookie)

private fun mergeCookies(base: String, additional: List<String>): String {
    val map = LinkedHashMap<String, String>()
    (listOf(base) + additional).forEach { part ->
        part.split(";").forEach { kv ->
            val i = kv.indexOf('=')
            if (i > 0) map[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
    }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

    // ---------- LIBRERIA ----------

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        if (cookie == null) return emptyList<Shelf>().toFeed()
        return Feed(emptyList()) {
            PagedData.Single {
                listOfNotNull(
                    albumsShelf("lib_favs", "I Miei Preferiti", "/cp/favorites", 30, cookie),
                    albumsShelf("lib_history", "La Mia Cronologia", "/cp/history", 30, cookie),
                    albumsShelf("lib_uploads", "I Miei Album", "/cp/uploads", 30, cookie),
                )
            }.toFeedData()
        }
    }

    // ---------- Ricerca ----------

    private suspend fun latestShelves(): List<Shelf> {
        val json = getJson(apiUrl("/api/latest-home"))
        val albums = runCatching {
            json.jsonArray.mapNotNull { it.jsonObject.toAlbumItem() }
        }.getOrDefault(emptyList())
        return if (albums.isEmpty()) emptyList()
        else listOf(Shelf.Lists.Items(id = "latest", title = "Ultimi arrivi", list = albums))
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return Feed(listOf()) { latestShelves().toFeedData() }
        }
        val json = getJson(apiUrl("/api/search", mapOf("q" to query))).jsonObject
        val albums = runCatching {
            json["items"]?.jsonArray?.mapNotNull { it.jsonObject.toAlbumItem() }
        }.getOrNull().orEmpty()
        val shelves = if (albums.isEmpty()) emptyList()
        else listOf(Shelf.Lists.Items(id = "albums", title = "Album", list = albums))
        return shelves.toFeed()
    }

    // ---------- Album ----------

    private var cacheAlbumId: String? = null
    private var cacheAlbumJson: JsonObject? = null

    private suspend fun albumMeta(id: String): JsonObject {
        if (cacheAlbumId == id && cacheAlbumJson != null) return cacheAlbumJson!!
        val json = runCatching {
            getJson(apiUrl("/api/album", mapOf("url" to id))).jsonObject
        }.getOrElse {
            buildJsonObject { put("name", "") }
        }
        cacheAlbumId = id
        cacheAlbumJson = json
        return json
    }

    override suspend fun loadAlbum(album: Album): Album =
        albumMeta(album.id).toAlbumDetails(album)

    override suspend fun loadTracks(album: Album): Feed<Track>? =
        albumMeta(album.id).toTracks(album).toFeed()

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ---------- Traccia ----------

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        val decoded = URLDecoder.decode(streamable.id, "UTF-8")
        val isFlac = decoded.endsWith("#flac")
        val pageUrl = if (isFlac) decoded.removeSuffix("#flac") else decoded
        val cacheKey = if (isFlac) "$pageUrl#flac" else pageUrl
        val direct = audioCache[cacheKey] ?: runCatching {
            resolveAudio(pageUrl, if (isFlac) "flac" else "mp3")
        }.getOrElse {
            if (isFlac) resolveAudio(pageUrl, "mp3") else throw it
        }.also { audioCache[cacheKey] = it }
        return downloadUrl(direct).toServerMedia()
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf> = emptyList<Shelf>().toFeed()
}
