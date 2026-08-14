package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
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
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.absoluteValue
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder

class KhinsiderExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, AlbumClient, TrackClient, LibraryFeedClient, LoginClient.WebView, LikeClient, SaveClient, ShareClient {

    private val client = OkHttpClient()
    private val noRedirectClient = OkHttpClient.Builder().followRedirects(false).build()
    private var setting: Settings? = null
    private val audioCache = mutableMapOf<String, String>()
    private val coverCache = mutableMapOf<String, String>()          // albumId -> URL originale (taglia applicata all'uso)
    private val coverEnrichLimit = 30                                 // max copertine dal mirror per scaffale
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    // ---------- Preferiti (sito) ----------
    private val favoriteSlugs = mutableSetOf<String>()          // id album (slug) attualmente nei preferiti
    private val slugToAlbumId = mutableMapOf<String, String>()  // slug -> albumid numerico (dai preferiti del sito)
    private var favoritesLoaded = false                          // set già sincronizzato con /cp/favorites?
    private val albumIdCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 20
    }

    // ---------- Cronologia locale (volatile, in memoria) ----------
    private val localHistory = LinkedHashMap<String, Pair<Long, Album>>()   // id -> (timestamp, album)
    private val historyMax = 40

    // ---------- Impostazioni copertine ----------

    override suspend fun getSettingItems(): List<Setting> {
        val options = listOf("Piccola", "Media", "Grande")
        val values = listOf("small", "medium", "full")
        return listOf(
            SettingList(
                "Copertine nelle liste",
                "cover_size_shelves",
                "Home, Top, Console, Tipi, Libreria e Ricerca. Piccola è la più veloce da caricare, Grande è l'immagine originale.",
                options,
                values,
                sizeIndex(shelfCoverSize),
            ),
            SettingList(
                "Copertina pagina album",
                "cover_size_album",
                "L'immagine grande mostrata quando apri un album.",
                options,
                values,
                sizeIndex(albumCoverSize),
            ),
            SettingList(
                "Copertine dei brani",
                "cover_size_tracks",
                "Le miniature nella lista tracce e l'immagine nella schermata di riproduzione.",
                options,
                values,
                sizeIndex(trackCoverSize),
            ),
        )
    }

    private val shelfCoverSize get() = sizeSetting("cover_size_shelves", "medium")
    private val albumCoverSize get() = sizeSetting("cover_size_album", "full")
    private val trackCoverSize get() = sizeSetting("cover_size_tracks", "full")

    /** Legge la taglia salvata, con fallback sul valore di default. */
    private fun sizeSetting(id: String, default: String): String {
        val raw = runCatching { setting?.getString(id) }.getOrNull()
        return when (raw) {
            "small", "medium", "full" -> raw
            "0" -> "small"   // vecchi valori salvati come indice
            "1" -> "medium"
            "2" -> "full"
            else -> default
        }
    }

    private fun sizeIndex(value: String): Int = when (value) {
        "small" -> 0
        "full" -> 2
        else -> 1
    }

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

    /**
     * URL immagine via proxy del mirror, nella dimensione richiesta:
     * "small" = /thumbs_small/, "medium" = /thumbs/, "full" = originale.
     * La stessa taglia produce sempre lo stesso URL: se due impostazioni
     * coincidono, Echo riusa l'immagine già scaricata (cache per URL).
     */
    private fun imageUrl(raw: String?, size: String): String? {
        if (raw.isNullOrBlank()) return null
        // Toglie l'eventuale segmento di dimensione già presente, così qualsiasi
        // sorgente (HTML, API) viene normalizzata prima di applicare la taglia.
        val clean = raw.replace("/thumbs_small/", "/").replace("/thumbs/", "/")
        val target = when (size) {
            "small" -> Regex("""(soundtracks/[^/]+)/""").replace(clean, "$1/thumbs_small/")
            "full" -> clean
            else -> Regex("""(soundtracks/[^/]+)/""").replace(clean, "$1/thumbs/")
        }
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
        val cover = imageUrl(str("icon") ?: str("image"), shelfCoverSize)?.toImageHolder()
        val subtitle = listOfNotNull(str("albumType"), str("year")).joinToString(" • ").ifBlank { null }
        return Album(id = id, title = title, cover = cover, subtitle = subtitle, isLikeable = true, isShareable = true)
    }

    private fun JsonObject.toAlbumDetails(album: Album): Album {
        val title = str("name") ?: album.title
        val year = str("year")
        val cover = imageUrl(str("coverUrl"), albumCoverSize)?.toImageHolder() ?: album.cover
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
            subtitle = year?.let { "Anno: $it" },
            isLikeable = true,
            isShareable = true,
        )
    }

    private fun JsonObject.toTracks(album: Album): List<Track> {
        val albumTitle = str("name") ?: album.title
        val cover = imageUrl(str("coverUrl"), trackCoverSize)?.toImageHolder() ?: album.cover
        val artistName = str("albumArtist")
        val artists = artistName?.takeIf { it.isNotBlank() }?.let {
            listOf(Artist(id = it, name = it))
        } ?: emptyList()
        val albumModel = Album(id = album.id, title = albumTitle, cover = cover, isLikeable = true, isShareable = true)
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
                isShareable = true,
                streamables = streamables
            )
        }
    }

    // ---------- LE SEZIONI DEL SITO ----------

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

    /**
     * Cerca la copertina di un album: prima dentro l'anchor (caso più semplice),
     * poi nell'intera riga di tabella che contiene il link (la copertina è in una
     * cella diversa da quella del titolo) e infine in una finestra attorno al link.
     */
    private fun coverFromContext(html: String, matchStart: Int, matchEnd: Int): String? {
        val trStart = html.lastIndexOf("<tr", matchStart)
        val trEnd = html.indexOf("</tr>", matchEnd)
        val region = if (trStart >= 0 && trEnd > matchEnd) {
            html.substring(trStart, trEnd)
        } else {
            html.substring(maxOf(0, matchStart - 1200), minOf(html.length, matchEnd + 1200))
        }
        val imgs = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(region).map { it.groupValues[1] }.toList()
        // Preferisce le immagini di copertina (path con "soundtracks" o "thumbs"),
        // per non prendere per sbaglio loghi o icone della pagina.
        return imgs.lastOrNull { it.contains("soundtracks", true) || it.contains("thumbs", true) }
    }

    /** Copertina originale dal mirror API, con cache in memoria (taglia applicata all'uso). */
    private suspend fun coverFromApi(id: String): String? {
        synchronized(coverCache) {
            coverCache[id]?.let { return it.ifEmpty { null } }
        }
        val cover = runCatching {
            val json = getJson(apiUrl("/api/album", mapOf("url" to id))).jsonObject
            json["coverUrl"]?.jsonPrimitive?.content
                ?: json["imagesThumbs"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        }.getOrNull()
        synchronized(coverCache) {
            coverCache[id] = cover ?: ""
        }
        return cover
    }

    /**
     * Completa le copertine mancanti usando il mirror API, con richieste in parallelo
     * (a gruppi di 6) su Dispatchers.Default per non bloccare la UI. Limitato a
     * `coverEnrichLimit` album per chiamata, così il primo caricamento resta veloce.
     */
    private suspend fun enrichWithCovers(albums: List<Album>): List<Album> {
        val missing = albums.filter { it.cover == null }.take(coverEnrichLimit)
        if (missing.isEmpty()) return albums
        return withContext(Dispatchers.Default) {
            coroutineScope {
                val enriched = missing.chunked(6).flatMap { chunk ->
                    chunk.map { album ->
                        async {
                            coverFromApi(album.id)?.let { url ->
                                album.copy(cover = imageUrl(url, shelfCoverSize)?.toImageHolder())
                            } ?: album
                        }
                    }.awaitAll()
                }
                val byId = enriched.associateBy { it.id }
                albums.map { byId[it.id] ?: it }
            }
        }
    }

    /** Estrae gli album da HTML già scaricato (niente richieste extra). */
    private fun parseAlbumList(html: String, limit: Int = 30, skipFirst: Int = 0): List<Album> {
        val albums = LinkedHashMap<String, Album>()   // niente duplicati
        for (match in albumLinkRegex.findAll(html)) {
            val slug = match.groupValues[1].trim()
            if (slug.isBlank()) continue
            val inner = match.groupValues[2]
            val title = Regex("""<[^>]+>""").replace(inner, " ")
                .replace(Regex("""\s+"""), " ").trim()
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            val coverInAnchor = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)
            val cover = (coverInAnchor ?: coverFromContext(html, match.range.first, match.range.last + 1))
                ?.let { if (it.startsWith("http")) it else "$KHI$it" }
            albums[slug] = Album(
                id = "/game-soundtracks/album/$slug",
                title = title.ifBlank { slug.replace('-', ' ') },
                cover = cover?.let { imageUrl(it, shelfCoverSize)?.toImageHolder() },
                isLikeable = true,
                isShareable = true,
            )

            if (albums.size >= skipFirst + limit) break
        }
        val list = albums.values.toList()
        return if (skipFirst > 0) list.drop(skipFirst) else list
    }

    private suspend fun scrapeAlbumList(
        url: String,
        limit: Int = 30,
        cookie: String? = null,
        skipFirst: Int = 0,
    ): List<Album> {
        val html = runCatching { khinsiderGet(url, cookie) }.getOrDefault("")
        // Le pagine "Top 100..." non hanno immagini: le prendiamo dal mirror API.
        return enrichWithCovers(parseAlbumList(html, limit, skipFirst))
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

    // ---------- CONSOLE (Top 12 + elenco paginato senza la Top 12) ----------

    /** Le due sezioni di una console: "Top 12 X Albums" e l'elenco completo dal 13° album. */
    private suspend fun consoleShelves(name: String, path: String): List<Shelf> {
        val all = runCatching { scrapeAlbumList("$KHI$path", 12 + 30, null, 0) }.getOrDefault(emptyList())
        if (all.isEmpty()) return emptyList()
        val top12 = all.take(12)
        val rest = all.drop(12).take(30)
        val topShelf = Shelf.Lists.Items(
            id = "console_${path.substringAfterLast('/')}_top",
            title = "Top 12 $name Albums",
            list = top12,
        )
        val restShelf = if (rest.isEmpty()) null
        else Shelf.Lists.Items(
            id = "console_${path.substringAfterLast('/')}_albums",
            title = name,
            list = rest,
            more = consoleMoreFeed(path),
        )
        return listOfNotNull(topShelf, restShelf)
    }

    /**
     * Pagine "More" di una console: chunk da 30 album DENTRO ogni pagina del sito,
     * saltando la Top 12. La chiave è "paginaSito_offset" (es. "1_42", "2_0"):
     * la prima pagina "More" riparte dal 43° album (dopo i 30 già mostrati).
     */
    private fun consoleMoreFeed(path: String): Feed<Shelf> =
        Feed(emptyList()) {
            PagedData.Continuous<Shelf> { key ->
                val parts = key?.split("_") ?: listOf("1", "42")
                val sitePage = parts[0].toIntOrNull() ?: 1
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val url = if (sitePage == 1) "$KHI$path" else "$KHI$path?page=$sitePage"
                val items = scrapeAlbumList(url, 30, null, offset)
                val next = when {
                    items.size >= 30 -> "${sitePage}_${offset + 30}"
                    items.isNotEmpty() -> "${sitePage + 1}_0"
                    else -> null
                }
                val shelves = if (items.isEmpty()) emptyList<Shelf>()
                else listOf(
                    Shelf.Lists.Items(
                        id = "console-${path.substringAfterLast('/')}-p$sitePage-$offset",
                        title = "Pagina",
                        list = items,
                    )
                )
                Page(shelves, next)
            }.toFeedData()
        }

    /** Le 24 console caricate a gruppi di 8 (scroll infinito tra le console) */
    private fun pagedConsoleShelves(): PagedData<Shelf> = continuousPaged { page ->
        val start = (page - 1) * 8
        val end = minOf(start + 8, platforms.size)
        val shelves = platforms.subList(start, end).flatMap { (name, path) ->
            runCatching { consoleShelves(name, path) }.getOrDefault(emptyList())
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
            "https://downloads.khinsider.com/forums/login?redirect=%2Fcp%2Ffavorites"
                .toGetRequest(mapOf("User-Agent" to UA))

        // IMPORTANTE: Echo confronta questa regex con TUTTE le richieste della WebView,
        // anche CSS/JS/immagini. Combacia quindi SOLO con la destinazione post-login:
        // css.php, js/... e la pagina di login non la attivano mai, altrimenti
        // la WebView si chiuderebbe in pochi millisecondi prima di mostrare il modulo.
        override val stopUrlRegex =
            Regex("""https://downloads\.khinsider\.com/(cp/favorites|forums)(/|(\?.*))?$""")

        override suspend fun onStop(url: NetworkRequest, cookie: String): List<User> {
            val preview = cookie.take(120)
            if (!cookie.contains("xf_session")) {
                throw Exception(
                    "Login non riuscito: nessuna sessione XenForo ricevuta. " +
                        "Cookie ricevuti: $preview. " +
                        "Assicurati di aver completato il login nella pagina web."
                )
            }

            // Visita la home per eventuali cookie di sessione del sito principale.
            val session = warmUpSession(cookie)

            // Verifica: /cp/favorites risponde 200 con i contenuti solo se loggati.
            val (loggedIn, mainHtml) = checkMainSession(session)
            if (!loggedIn) {
                val hint = if (!cookie.contains("xf_user"))
                    " Suggerimento: nella pagina di login spunta \"Stay logged in\" (Resta connesso)."
                else ""
                throw Exception("Login non riuscito: impossibile verificare la sessione. Cookie ricevuti: $preview.$hint")
            }

            // Nome utente reale (es. "Zippatias") invece del nome fisso "Khinsider".
            val accountHtml = runCatching {
                getPageWithCookie("https://downloads.khinsider.com/forums/index.php?account/", session)
            }.getOrDefault("")
            val name = parseUsername(mainHtml, accountHtml) ?: "Khinsider"

            return listOf(
                User(
                    id = "khinsider",
                    name = name,
                    subtitle = "Account khinsider",
                    extras = mapOf("cookie" to session),
                )
            )
        }
    }

    override fun setLoginUser(user: User?) {
        this.user = user
        this.cookie = user?.extras?.get("cookie")
        // Cambio utente/logout: la prossima volta i preferiti verranno riscaricati.
        synchronized(favoriteSlugs) {
            favoriteSlugs.clear()
            favoritesLoaded = false
        }
    }

    override suspend fun getCurrentUser(): User? = user

    /** GET con cookie, senza seguire i redirect. Restituisce (loggato?, HTML della pagina). */
    private suspend fun checkMainSession(cookie: String): Pair<Boolean, String> = runCatching {
        if (!cookie.contains("xf_session")) return@runCatching false to ""
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
        (code == 200 && !loggedOut) to body
    }.getOrDefault(false to "")

    /** GET con cookie, seguendo i redirect (per la pagina account del forum). */
    private suspend fun getPageWithCookie(url: String, cookie: String): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", cookie)
            .build()
        val response = client.newCall(request).await()
        val body = response.body?.string() ?: ""
        response.close()
        return body
    }

    /** Cerca il nome utente vero nell'HTML della pagina account XenForo o del sito principale. */
    private fun parseUsername(mainHtml: String, accountHtml: String): String? {
        Regex("""class="username">([^<]+)</span>""").find(accountHtml)?.let {
            val n = it.groupValues[1].trim()
            if (n.isNotBlank()) return n
        }
        Regex("""class="menu-header-main">([^<]+)</span>""").find(accountHtml)?.let {
            val n = it.groupValues[1].trim()
            if (n.isNotBlank()) return n
        }
        Regex("""href="[^"]*/user(?:s)?/([^"/?]+)""", RegexOption.IGNORE_CASE).find(mainHtml)?.let {
            val n = it.groupValues[1].trim()
            if (n.isNotBlank()) return n
        }
        Regex("""Welcome(?:\s+back)?,\s*([^<!"']+)""", RegexOption.IGNORE_CASE).find(mainHtml)?.let {
            val n = it.groupValues[1].trim()
            if (n.isNotBlank()) return n
        }
        return null
    }

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

    // ---------- PREFERITI (mi piace / salva in libreria → favorites del sito) ----------

    /** Carica i preferiti dal sito una volta sola per sessione, per lo stato di cuori e salvataggi. */
    private suspend fun ensureFavorites() {
        val c = cookie ?: return
        synchronized(favoriteSlugs) { if (favoritesLoaded) return }
        refreshFavorites(c)
    }

    /**
     * Sincronizza lo stato locale con /cp/favorites. Nella pagina ogni album ha
     * l'icona di rimozione con l'albumid numerico come attributo:
     *   <i class="material-icons albumDelete" albumid="70421">delete_forever</i>
     * quindi qui costruiamo anche la mappa slug -> albumid, così rimuovere un
     * preferito non richiede di scaricare la pagina album.
     * Restituisce gli album della pagina per riusarli nella sezione Libreria.
     */
    private suspend fun refreshFavorites(c: String): List<Album> {
        val html = runCatching { khinsiderGet("$KHI/cp/favorites", c) }.getOrDefault("")
        val loggedOut = html.contains("need to be registered and logged in") ||
            html.contains("/forums/login") || html.contains(">Log In")
        val favs = if (loggedOut) emptyList() else parseAlbumList(html, 30)

        // Posizione di ogni album e di ogni albumid nella pagina (con o senza
        // virgolette): a ogni album assegniamo l'albumid più vicino (la sua riga).
        val albumPos = albumLinkRegex.findAll(html)
            .map { it.range.first to "/game-soundtracks/album/${it.groupValues[1].trim()}" }
            .toList()
        val idPos = Regex("""albumid\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.range.first to it.groupValues[1] }
            .toList()

        synchronized(favoriteSlugs) {
            favoriteSlugs.clear()
            favoriteSlugs.addAll(favs.map { it.id })
            slugToAlbumId.clear()
            for ((pos, slugPath) in albumPos) {
                idPos.minByOrNull { (it.first - pos).absoluteValue }?.second?.let {
                    slugToAlbumId[slugPath] = it
                }
            }
            favoritesLoaded = true
        }
        return favs
    }

    /**
     * L'endpoint di toggle usa l'albumid NUMERICO (es. 102359), che non è nei dati
     * del mirror API: lo cerchiamo prima nella mappa ricavata da /cp/favorites,
     * poi nella pagina album del sito (con più pattern e cache LRU).
     */
    private suspend fun albumIdOf(albumId: String, c: String): String {
        synchronized(albumIdCache) { albumIdCache[albumId]?.let { return it } }
        synchronized(slugToAlbumId) { slugToAlbumId[albumId]?.let { return it } }

        val html = runCatching { khinsiderGet("$KHI$albumId", c) }.getOrDefault("")
        if (html.contains(">Log In") || html.contains("/forums/login")) throw ClientException.LoginRequired()
        val id = findAlbumIdInPage(html)
            ?: throw Exception("albumid non trovato per $albumId: il sito potrebbe aver cambiato struttura")
        synchronized(albumIdCache) { albumIdCache[albumId] = id }
        return id
    }

    /**
     * Cerca l'albumid numerico nella pagina album. La stellina è solo
     * <i class="material-icons">favorite_border</i> e il click fa
     * $.get('/cp/album_favorite_toggle', {albumid: N}) con N iniettato da PHP:
     * il posto affidabile in cui l'id compare nell'HTML è il link di modifica
     * /cp/edit_album_details?albumid=N (senza virgolette).
     */
    private fun findAlbumIdInPage(html: String): String? {
        // 1) URL letterale del toggle, se il bottone fosse un link diretto
        Regex("""album_favorite_toggle\?albumid=(\d+)""").find(html)?.let { return it.groupValues[1] }
        // 2) Parametro query "albumid=NNN" senza virgolette: il link di modifica
        //    /cp/edit_album_details?albumid=102359 è presente in ogni pagina album
        Regex("""albumid=(\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        // 3) Attributo con virgolette, come sulle icone di /cp/favorites (albumid="70421")
        Regex("""albumid\s*=\s*["'](\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        // 4) Attributo data-album-id / data-albumid
        Regex("""data-album-?id\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        // 5) Input nascosto di un form
        Regex("""name=["']albumid["'][^>]*?value=["'](\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""value=["'](\d+)["'][^>]*?name=["']albumid["']""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        // 6) Variabile/oggetto JS tipo: {albumid:102359} o var album_id = 102359;
        Regex("""(?:album_?id|ALBUM_ID)\s*[:=]\s*["']?(\d{4,7})""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        // 7) Chiamata JS tipo: toggleFavorite(102359) / setFav(102359)
        Regex("""(?:favorite|fav)\w*\s*\(\s*["']?(\d{4,7})""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        // 8) Ultima spiaggia: il JS del sito è impacchettato e l'albumid compare come
        //    token del dizionario subito dopo "album_favorite_toggle"
        //    (es. ...|album_favorite_toggle|102359|albumFavorite|...)
        Regex("""album_favorite_toggle\|(\d{4,7})""").find(html)?.let { return it.groupValues[1] }
        return null
    }

    private suspend fun isFavorite(item: EchoMediaItem): Boolean {
        if (item !is Album) return false
        if (cookie == null) return false
        ensureFavorites()
        return synchronized(favoriteSlugs) { favoriteSlugs.contains(item.id) }
    }

    /** Toggle sul sito SOLO se lo stato locale differisce da quello richiesto (evita doppi toggle). */
    private suspend fun setFavorite(item: EchoMediaItem, should: Boolean) {
        if (item !is Album) throw Exception("Solo gli album possono essere aggiunti ai preferiti")
        val c = cookie ?: throw ClientException.LoginRequired()
        ensureFavorites()
        val already = synchronized(favoriteSlugs) { favoriteSlugs.contains(item.id) == should }
        if (already) return
        val albumId = albumIdOf(item.id, c)
        val request = Request.Builder()
            .url("$KHI/cp/album_favorite_toggle?albumid=$albumId")
            .header("User-Agent", UA)
            .header("Cookie", c)
            .build()
        val response = client.newCall(request).await()
        val code = response.code
        val body = response.body?.string() ?: ""
        response.close()
        if (code !in 200..399) throw Exception("Impossibile aggiornare i preferiti (HTTP $code)")
        if (body.contains(">Log In") || body.contains("/forums/login")) throw ClientException.LoginRequired()
        synchronized(favoriteSlugs) {
            if (should) {
                favoriteSlugs.add(item.id)
                slugToAlbumId[item.id] = albumId   // per i prossimi "rimuovi" senza rifetch
            } else {
                favoriteSlugs.remove(item.id)
            }
        }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) = setFavorite(item, shouldLike)

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = isFavorite(item)

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) = setFavorite(item, shouldSave)

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = isFavorite(item)

    // ---------- CONDIVISIONE ----------

    override suspend fun onShare(item: EchoMediaItem): String {
        val path = item.id
        return if (path.startsWith("http")) path else "$KHI$path"
}

    // ---------- CRONOLOGIA (locale + sito integrate) ----------

    /** Registra l'apertura di un album nella cronologia locale (in memoria, volatile). */
    private fun recordHistory(album: Album) {
        synchronized(localHistory) {
            localHistory.remove(album.id)
            localHistory[album.id] = System.currentTimeMillis() to album
            while (localHistory.size > historyMax) {
                val oldest = localHistory.entries.minByOrNull { it.value.first } ?: break
                localHistory.remove(oldest.key)
            }
        }
    }

    private fun relativeTime(ts: Long): String {
        val minutes = (System.currentTimeMillis() - ts) / 60_000
        return when {
            minutes < 1 -> "adesso"
            minutes < 60 -> "$minutes min fa"
            minutes < 60 * 24 -> "${minutes / 60} ore fa"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)} giorni fa"
            else -> "${minutes / (60 * 24 * 7)} sett. fa"
        }
    }

    /**
     * Cronologia integrata: prima gli album aperti nell'app (con tempo relativo),
     * poi quelli registrati dal sito non ancora presenti, senza duplicati.
     * Funziona anche senza login (mostra solo la parte locale).
     */
    private suspend fun historyShelf(): Shelf? {
        val local: List<Album> = synchronized(localHistory) {
            localHistory.values.sortedByDescending { it.first }.map { (ts, a) ->
                a.copy(subtitle = relativeTime(ts), isLikeable = true)
            }
        }
        val merged = LinkedHashMap<String, Album>()
        local.forEach { merged[it.id] = it }
        val c = cookie
        if (c != null) {
            val site = runCatching { scrapeAlbumList("$KHI/cp/history", 30, c) }.getOrDefault(emptyList())
            site.forEach { merged.putIfAbsent(it.id, it) }
        }
        if (merged.isEmpty()) return null
        return Shelf.Lists.Items(id = "lib_history", title = "Cronologia", list = merged.values.toList())
    }

    // ---------- LIBRERIA ----------

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val c = cookie
        if (c != null) {
            // I preferiti vengono scaricati una volta sola e riusati sia per la
            // sezione sia per lo stato di cuore/salvataggio (niente doppia richiesta).
            val favs = runCatching { refreshFavorites(c) }.getOrDefault(emptyList())
            val favsWithCovers = enrichWithCovers(favs)
            if (favsWithCovers.isNotEmpty()) {
                shelves += Shelf.Lists.Items(id = "lib_favs", title = "I Miei Preferiti", list = favsWithCovers)
            }
        }
        // Cronologia: locale + sito in un'unica sezione, visibile anche senza login.
        historyShelf()?.let { shelves += it }
        if (c != null) {
            albumsShelf("lib_uploads", "I Miei Album", "/cp/uploads", 30, c)?.let { shelves += it }
        }
        return Feed(emptyList()) {
            PagedData.Single { shelves }.toFeedData()
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

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> = withContext(Dispatchers.Default) {
        if (query.isBlank()) {
            return@withContext Feed(listOf()) { latestShelves().toFeedData() }
        }
        // 1) ricerca normale sul mirror
        val albums = runCatching {
            getJson(apiUrl("/api/search", mapOf("q" to query))).jsonObject
                .get("items")?.jsonArray?.mapNotNull { it.jsonObject.toAlbumItem() }
        }.getOrNull().orEmpty()

        // 2) impariamo i titoli visti (per le prossime ricerche fuzzy)
        albums.forEach { item -> FuzzyIndex.add(item.id, item.title) }

        // 3) se l'API non trova nulla, fallback fuzzy sul catalogo locale
        val results = if (albums.isNotEmpty()) albums else FuzzyIndex.search(query)

        val shelves = if (results.isEmpty()) emptyList()
        else listOf(Shelf.Lists.Items(id = "albums", title = "Album", list = results))
        shelves.toFeed()
    }

    // ---------- Album ----------

    // Cache LRU (max 20 album) dei metadati: evita di riscaricare l'album
    // a ogni tap quando si alternano pochi album di fila.
    private val albumMetaCache = object : LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?): Boolean = size > 20
    }

    private suspend fun albumMeta(id: String): JsonObject {
        synchronized(albumMetaCache) { albumMetaCache[id]?.let { return it } }
        val json = runCatching {
            getJson(apiUrl("/api/album", mapOf("url" to id))).jsonObject
        }.getOrElse {
            buildJsonObject { put("name", "") }
        }
        synchronized(albumMetaCache) { albumMetaCache[id] = json }
        return json
    }

    override suspend fun loadAlbum(album: Album): Album {
        recordHistory(album)   // apertura album → cronologia locale
        return albumMeta(album.id).toAlbumDetails(album)
    }

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

/** Indice fuzzy in memoria dei titoli degli album. Thread-safe. */
object FuzzyIndex {
    private class Entry(val id: String, val title: String, val norm: String)
    private val entries = mutableListOf<Entry>()

    fun add(id: String, title: String) {
        synchronized(this) {
            if (entries.none { it.id == id }) entries += Entry(id, title, normalize(title))
        }
    }

    fun search(query: String, limit: Int = 20): List<Album> {
        val q = normalize(query)
        if (q.length < 3) return emptyList()
        val qBigrams = q.windowed(2).toSet()
        val scored = mutableListOf<Pair<Double, Entry>>()
        synchronized(this) {
            for (e in entries) {
                if (qBigrams.none { e.norm.contains(it) }) continue // prefilter veloce
                val sim = similarity(q, e.norm)
                if (sim >= 0.55) scored += sim to e
            }
        }
        return scored.sortedByDescending { it.first }
            .take(limit)
                        .map { (_, e) -> Album(e.id, e.title, isLikeable = true, isShareable = true) }
    }

    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            val d = when (c) {
                'à', 'á', 'â', 'ä', 'ã', 'å' -> 'a'
                'è', 'é', 'ê', 'ë' -> 'e'
                'ì', 'í', 'î', 'ï' -> 'i'
                'ò', 'ó', 'ô', 'ö', 'õ' -> 'o'
                'ù', 'ú', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                else -> c
            }
            if (d in 'a'..'z' || d in '0'..'9' || d == ' ') sb.append(d)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun similarity(a: String, b: String): Double {
        val dist = damerauLevenshtein(a, b)
        return 1.0 - dist.toDouble() / maxOf(a.length, b.length, 1)
    }

    /** Damerau-Levenshtein, variante Optimal String Alignment. */
    private fun damerauLevenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val d = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) d[i][0] = i
        for (j in 0..n) d[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[m][n]
    }
}
