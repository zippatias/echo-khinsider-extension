package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditPrivacyClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
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
import kotlinx.coroutines.delay
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class KhinsiderExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, AlbumClient, TrackClient, LibraryFeedClient, LoginClient.CustomInput, LikeClient, SaveClient, ShareClient, PlaylistClient, PlaylistEditClient, PlaylistEditPrivacyClient {

    // Timeout generosi: le pagine di browse arrivano a 300KB+ (es. Android, 327KB)
    // e con i timeout di default OkHttp (10s) scattava il timeout -> "vuoto" sistematico.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val noRedirectClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var setting: Settings? = null
    private val audioCache = mutableMapOf<String, String>()
    private val coverCache = mutableMapOf<String, String>()          // albumId -> URL originale (taglia applicata all'uso)
    private val coverEnrichLimit = 30                                 // max copertine dal mirror per scaffale
    private val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    // ---------- Preferiti (sito) ----------
    private val favoriteSlugs = mutableSetOf<String>()          // id album (slug) attualmente nei preferiti
    private val slugToAlbumId = mutableMapOf<String, String>()  // slug -> albumid numerico (dai preferiti del sito)
    private val cachedFavorites = mutableListOf<Album>()        // lista completa dei preferiti per la Libreria (cache)
    private var favoritesLoaded = false                          // set già sincronizzato con /cp/favorites?
    private val albumIdCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 20
    }

    // ---------- Playlist (sito, PRO) ----------
    private val cachedPlaylists = mutableListOf<Playlist>()
    private var playlistsLoaded = false
    private val songIdCache = mutableMapOf<String, String>()    // path traccia normalizzato -> songid numerico

    // ---------- Cronologia locale (volatile, in memoria) ----------
    private val localHistory = LinkedHashMap<String, Pair<Long, Album>>()   // id -> (timestamp, album)
    private val historyMax = 40

    // ---------- Impostazioni copertine ----------

    override suspend fun getSettingItems(): List<Setting> {
        val options = listOf(t("small"), t("medium"), t("large"))
        val values = listOf("small", "medium", "full")
        return listOf(
            SettingList(
                t("lang_label"),
                "lang",
                t("lang_desc"),
                listOf("Italiano", "English", "日本語"),
                listOf("it", "en", "ja"),
                langIndex(),
            ),
            SettingList(
                t("cover_shelves"),
                "cover_size_shelves",
                t("cover_shelves_desc"),
                options,
                values,
                sizeIndex(shelfCoverSize),
            ),
            SettingList(
                t("cover_album"),
                "cover_size_album",
                t("cover_album_desc"),
                options,
                values,
                sizeIndex(albumCoverSize),
            ),
            SettingList(
                t("cover_tracks"),
                "cover_size_tracks",
                t("cover_tracks_desc"),
                options,
                values,
                sizeIndex(trackCoverSize),
            ),
        )
    }

    // ---------- Lingua interfaccia (IT / EN / JA) ----------

    private val uiStrings: Map<String, Map<String, String>> = mapOf(
        "it" to mapOf(
            "home" to "Home", "top" to "Top", "console" to "Console", "tipo" to "Tipo",
            "latest" to "Ultimi Arrivi", "top1000" to "Top 1000 All Time",
            "top6m" to "Top 100 Ultimi 6 Mesi", "topnew" to "Top 100 Nuovi",
            "viewed" to "Attualmente Visti", "favs" to "Più Preferiti",
            "my_favs" to "I Miei Preferiti", "history" to "Cronologia", "my_uploads" to "I Miei Album",
            "playlists" to "Le Mie Playlist", "tracks" to "tracce", "login_ok" to "Login effettuato",
            "login_user" to "Nome utente", "login_pass" to "Password", "login_label" to "Account khinsider",
            "album" to "Album", "page" to "Pagina", "latest_search" to "Ultimi arrivi",
            "year" to "Anno",
            "lang_label" to "Lingua interfaccia",
            "lang_desc" to "Italiano (default), Inglese o Giapponese.",
            "cover_shelves" to "Copertine nelle liste",
            "cover_shelves_desc" to "Home, Top, Console, Tipi, Libreria e Ricerca. Piccola è la più veloce da caricare, Grande è l'immagine originale.",
            "cover_album" to "Copertina pagina album",
            "cover_album_desc" to "L'immagine grande mostrata quando apri un album.",
            "cover_tracks" to "Copertine dei brani",
            "cover_tracks_desc" to "Le miniature nella lista tracce e l'immagine nella schermata di riproduzione.",
            "small" to "Piccola", "medium" to "Media", "large" to "Grande",
        ),
        "en" to mapOf(
            "home" to "Home", "top" to "Top", "console" to "Consoles", "tipo" to "By Type",
            "latest" to "Latest", "top1000" to "Top 1000 All Time",
            "top6m" to "Top 100 Last 6 Months", "topnew" to "Top 100 Newly Added",
            "viewed" to "Currently Viewed", "favs" to "Most Favorites",
            "my_favs" to "My Favorites", "history" to "History", "my_uploads" to "My Albums",
            "playlists" to "My Playlists", "tracks" to "tracks", "login_ok" to "Logged in",
            "login_user" to "Username", "login_pass" to "Password", "login_label" to "Khinsider account",
            "album" to "Album", "page" to "Page", "latest_search" to "Latest additions",
            "year" to "Year",
            "lang_label" to "Interface language",
            "lang_desc" to "Italian (default), English or Japanese.",
            "cover_shelves" to "Covers in lists",
            "cover_shelves_desc" to "Home, Top, Consoles, Types, Library and Search. Small is the fastest to load, Large is the original image.",
            "cover_album" to "Album page cover",
            "cover_album_desc" to "The large image shown when you open an album.",
            "cover_tracks" to "Track covers",
            "cover_tracks_desc" to "The thumbnails in the track list and the image in the player screen.",
            "small" to "Small", "medium" to "Medium", "large" to "Large",
        ),
        "ja" to mapOf(
            "home" to "ホーム", "top" to "トップ", "console" to "コンソール", "tipo" to "タイプ",
            "latest" to "最新の追加", "top1000" to "全期間トップ1000",
            "top6m" to "過去6ヶ月トップ100", "topnew" to "新着トップ100",
            "viewed" to "現在視聴中", "favs" to "お気に入り上位",
            "my_favs" to "マイお気に入り", "history" to "履歴", "my_uploads" to "マイアルバム",
            "playlists" to "マイプレイリスト", "tracks" to "曲", "login_ok" to "ログイン済み",
            "login_user" to "ユーザー名", "login_pass" to "パスワード", "login_label" to "khinsiderアカウント",
            "album" to "アルバム", "page" to "ページ", "latest_search" to "最新の追加",
            "year" to "年",
            "lang_label" to "インターフェース言語",
            "lang_desc" to "イタリア語（既定）、英語、日本語。",
            "cover_shelves" to "リストのカバー",
            "cover_shelves_desc" to "ホーム・トップ・コンソール・タイプ・ライブラリ・検索。小は読み込みが最速、大は元画像。",
            "cover_album" to "アルバムページのカバー",
            "cover_album_desc" to "アルバムを開いたときに表示される大きな画像。",
            "cover_tracks" to "トラックのカバー",
            "cover_tracks_desc" to "トラック一覧のサムネイルと再生画面の画像。",
            "small" to "小", "medium" to "中", "large" to "大",
        ),
    )

    private val lang get() = runCatching { setting?.getString("lang") }.getOrNull() ?: "it"

    private fun langIndex(): Int = when (lang) { "en" -> 1; "ja" -> 2; else -> 0 }

    private fun t(key: String): String = uiStrings[lang]?.get(key) ?: uiStrings["it"]?.get(key) ?: key

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

    /** GET di sola verifica: restituisce lo status code (HTTP 2xx/3xx = ok). */
    private suspend fun httpGetStatus(url: String, cookie: String?): Int {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        if (cookie != null) builder.header("Cookie", cookie)
        val response = client.newCall(builder.build()).await()
        val code = response.code
        response.close()
        return code
    }

    /** Retry con backoff: 3 tentativi, attesa 500ms/1s tra i tentativi. */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) delay(500L * (i + 1))
            }
        }
        throw last ?: Exception("Errore sconosciuto")
    }

    /**
     * Normalizza un URL già percent-encoded (possibilmente DUE volte, come li
     * restituisce il sito: %20 -> %2520 nell'HTML). Due decodifiche sono sicure
     * anche su URL singolarmente codificati (la seconda non cambia nulla).
     */
    private fun decodeAll(url: String): String = URLDecoder.decode(URLDecoder.decode(url, "UTF-8"), "UTF-8")

    /** Path di una traccia normalizzato (senza scheme/host, decodificato): usato per i confronti. */
    private fun trackPath(url: String): String? =
        Regex("""/game-soundtracks/album/[^?#]+""").find(decodeAll(url))?.value

    /**
     * URL immagine via proxy del mirror, nella dimensione richiesta:
     * "small" = /thumbs_small/, "medium" = /thumbs/, "full" = originale.
     * La stessa taglia produce sempre lo stesso URL: se due impostazioni
     * coincidono, Echo riusa l'immagine già scaricata (cache per URL).
     */
    private fun imageUrl(raw: String?, size: String): String? {
        if (raw.isNullOrBlank()) return null
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

    private fun stripHtml(raw: String): String =
        Regex("""<[^>]+>""").replace(raw, " ").replace(Regex("""\s+"""), " ").trim()

    // ---------- Risoluzione audio ----------

    /**
     * Trova il link diretto (MP3/FLAC) di una traccia. Prima prova la pagina
     * download via mirror, poi la pagina traccia direttamente sul sito.
     * Con retry e messaggio diagnostico (URL + status) in caso di fallimento.
     */
    private suspend fun resolveAudio(pageUrl: String, format: String = "mp3"): String {
        val ext = if (format.equals("flac", true)) "flac" else "mp3"
        val regex = Regex("href=[\"']([^\"']+\\.$ext)[\"']", RegexOption.IGNORE_CASE)
        val html = withRetry {
            runCatching { getText(downloadUrl(pageUrl)) }.getOrElse {
                val direct = khinsiderGet("$KHI$pageUrl")   // pagina traccia sul sito (fallback)
                if (direct.isBlank()) throw Exception("Pagina download vuota per $pageUrl")
                direct
            }
        }
        val candidates = regex.findAll(html).map { it.groupValues[1] }.toList()
        val link = candidates.firstOrNull { it.contains("vgmtreasurechest.com") }
            ?: candidates.firstOrNull()
            ?: throw Exception("Link $ext non trovato nella pagina $pageUrl (HTTP ok ma struttura cambiata?)")
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
            subtitle = year?.let { "${t("year")}: $it" },
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

    /** Lista completa delle piattaforme (fonte: /console-list, 65 voci). */
    private val platforms = listOf(
        "3DO" to "/game-soundtracks/3do",
        "3DS" to "/game-soundtracks/nintendo-3ds",
        "Amiga" to "/game-soundtracks/amiga",
        "Android" to "/game-soundtracks/android",
        "Anime" to "/game-soundtracks/anime",
        "Arcade" to "/game-soundtracks/arcade",
        "Atari 8-Bit" to "/game-soundtracks/atari-8bit",
        "Atari Jaguar" to "/game-soundtracks/atari-jaguar",
        "Atari ST" to "/game-soundtracks/atari-st",
        "CD-i" to "/game-soundtracks/cd-i",
        "Commodore 64" to "/game-soundtracks/commodore-64",
        "Dreamcast" to "/game-soundtracks/sega-dreamcast",
        "DS" to "/game-soundtracks/nintendo-ds",
        "Family Computer" to "/game-soundtracks/family-computer",
        "FDS" to "/game-soundtracks/famicom-disk-system",
        "FM Towns" to "/game-soundtracks/fm-towns",
        "Fujitsu FM77AV" to "/game-soundtracks/fujitsu-fm77av",
        "Game Gear" to "/game-soundtracks/sega-game-gear",
        "GB" to "/game-soundtracks/gameboy",
        "GBA" to "/game-soundtracks/gameboy-advance",
        "GC" to "/game-soundtracks/nintendo-gamecube",
        "Genesis/Mega Drive" to "/game-soundtracks/sega-mega-drive-genesis",
        "IBM PC" to "/game-soundtracks/ibm-pc",
        "IBM PC/AT" to "/game-soundtracks/ibm-pc-at",
        "iOS" to "/game-soundtracks/ios",
        "Linux" to "/game-soundtracks/linux",
        "MacOS" to "/game-soundtracks/mac-os",
        "Master System" to "/game-soundtracks/sega-master-system",
        "Mobile" to "/game-soundtracks/mobile",
        "Movie" to "/game-soundtracks/movie",
        "MS-DOS" to "/game-soundtracks/ms-dos",
        "MSX" to "/game-soundtracks/msx",
        "MSX2" to "/game-soundtracks/msx2",
        "N64" to "/game-soundtracks/nintendo-64",
        "Neo Geo" to "/game-soundtracks/neo-geo",
        "NES" to "/game-soundtracks/nintendo-nes",
        "Online" to "/game-soundtracks/online",
        "PC-88" to "/game-soundtracks/pc-8801",
        "PC-98" to "/game-soundtracks/pc-9801",
        "PC-9821" to "/game-soundtracks/pc-9821",
        "PC-FX" to "/game-soundtracks/pc-fx",
        "PS Vita" to "/game-soundtracks/playstation-vita",
        "PS1" to "/game-soundtracks/playstation",
        "PS2" to "/game-soundtracks/playstation-2",
        "PS3" to "/game-soundtracks/playstation-3",
        "PS4" to "/game-soundtracks/playstation-4",
        "PS5" to "/game-soundtracks/playstation-5",
        "PSP" to "/game-soundtracks/playstation-portable-psp",
        "Saturn" to "/game-soundtracks/sega-saturn",
        "Sharp X1" to "/game-soundtracks/sharp-x1",
        "SNES" to "/game-soundtracks/nintendo-snes",
        "Spectrum" to "/game-soundtracks/spectrum",
        "Stadia" to "/game-soundtracks/stadia",
        "Steam" to "/game-soundtracks/steam",
        "Switch" to "/game-soundtracks/nintendo-switch",
        "Switch 2" to "/game-soundtracks/switch-2",
        "TurboGrafx-16" to "/game-soundtracks/turbografx-16",
        "Virtual Boy" to "/game-soundtracks/virtual-boy",
        "VR" to "/game-soundtracks/virtual-reality",
        "Wii" to "/game-soundtracks/nintendo-wii",
        "Wii U" to "/game-soundtracks/nintendo-wii-u",
        "Windows" to "/game-soundtracks/windows",
        "X68000" to "/game-soundtracks/x68000",
        "Xbox" to "/game-soundtracks/xbox",
        "Xbox 360" to "/game-soundtracks/xbox-360",
        "Xbox One" to "/game-soundtracks/xbox-one",
        "Xbox Series X/S" to "/game-soundtracks/xbox-series-x",
    )

    /** Voci "Tipo", più la sezione Anime Soundtracks (piattaforma con paginazione). */
    private val types = listOf(
        "Gamerips" to "/game-soundtracks/gamerips",
        "Soundtracks" to "/game-soundtracks/ost",
        "Singles" to "/game-soundtracks/singles",
        "Arrangements" to "/game-soundtracks/arrangements",
        "Remixes" to "/game-soundtracks/remixes",
        "Compilations" to "/game-soundtracks/compilations",
        "Inspired By" to "/game-soundtracks/inspired-by",
        "Anime Soundtracks" to "/game-soundtracks/anime",
    )

    // ---------- Helper scraping ----------

    private val albumLinkRegex =
        Regex("""<a href="/game-soundtracks/album/([^"/]+)/?"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    /** Numero totale di pagine dal footer della pagina ("Page 1 of N" o max dei link ?page=). */
    private fun footerMaxPage(html: String): Int? =
        Regex("""Page\s+\d+\s+of\s+(\d+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""\?page=(\d+)""").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()

    /**
     * GET del sito. LANCIA su errore (HTTP non-2xx, timeout, rete): così chi chiama
     * può distinguere "richiesta fallita" da "nessun risultato" (vuoto reale).
     */
    private suspend fun khinsiderGet(url: String, cookie: String? = null): String {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        if (cookie != null) builder.header("Cookie", cookie)
        val response = client.newCall(builder.build()).await()
        if (!response.isSuccessful) {
            println("khinsider-http: ${response.code} per $url")
            throw Exception("HTTP ${response.code} per $url")
        }
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
            val title = stripHtml(inner).replace("&amp;", "&").replace("&quot;", "\"")
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

    /**
     * Scarica e parsifica una lista di album. LANCIA su errore di rete/HTTP (distinto
     * dal "vuoto vero": se il server risponde ma non ci sono album, ritorna lista vuota
     * e scrive un log di debug).
     */
    private suspend fun scrapeAlbumList(
        url: String,
        limit: Int = 30,
        cookie: String? = null,
        skipFirst: Int = 0,
    ): List<Album> {
        val html = khinsiderGet(url, cookie)
        if (html.isBlank()) throw Exception("Risposta vuota per $url")
        val parsed = parseAlbumList(html, limit, skipFirst)
        if (parsed.isEmpty()) {
            println("khinsider-scrape: 0 album da $url (${html.length} bytes)")
        }
        return enrichWithCovers(parsed)
    }

    private suspend fun albumsShelf(
        id: String, title: String, path: String,
        preview: Int = 12, cookie: String? = null,
    ): Shelf? {
        val albums = runCatching { scrapeAlbumList("$KHI$path", preview, cookie) }.getOrDefault(emptyList())
        if (albums.isEmpty()) return null   // sezione vuota o non raggiungibile → non mostrarla
        return Shelf.Lists.Items(
            id = id,
            title = title,
            list = albums,
        )
    }

    // ---------- CATEGORIE (Console / Tipo) — griglia di bottoni ----------

    /**
     * Griglia compatta di voci testuali (Shelf.Lists.Categories). Ogni voce è una
     * Shelf.Category con feed on-demand: il contenuto viene caricato SOLO al tap,
     * niente precaricamento di tutte le piattaforme.
     */
    private fun platformCategories(id: String, title: String, entries: List<Pair<String, String>>): Shelf.Lists.Categories =
        Shelf.Lists.Categories(
            id = id,
            title = title,
            list = entries.map { (name, path) ->
                Shelf.Category(
                    id = "cat_${path.substringAfterLast('/')}",
                    title = name,
                    feed = platformFeed(path),
                )
            },
            type = Shelf.Lists.Type.Grid,
        )

    /**
     * Album di una pagina piattaforma/tipo, UN BLOCCO per pagina del sito,
     * etichettato "Pagina N", con TUTTI gli album reali di quella pagina
     * (il sito ne mostra ~460 per pagina; prima ne caricavamo solo 30 → troncamento).
     * La "Top 12" del sito compare in cima a OGNI pagina: viene sempre saltata.
     * Le pagine successive si caricano scorrendo fino in fondo (on-demand:
     * per Windows = 96 pagine, caricare tutto subito non è sensato).
     * Errori di rete/HTTP si propagano → Echo mostra "Errore di caricamento".
     */
    private fun platformPaged(path: String): PagedData<Shelf> {
        var maxPage = 1
        var firstPage = true
        var prevFirst: String? = null
        return PagedData.Continuous { key ->
            val sitePage = key?.toIntOrNull() ?: 1
            val url = if (sitePage == 1) "$KHI$path" else "$KHI$path?page=$sitePage"
            val html = khinsiderGet(url)
            if (firstPage) {
                firstPage = false
                maxPage = footerMaxPage(html) ?: 1
            }
            val items = enrichWithCovers(parseAlbumList(html, 600, 12))
            val first = items.firstOrNull()?.id
            val shelves: List<Shelf> = if (items.isEmpty()) emptyList()
            else listOf(
                Shelf.Lists.Items(
                    id = "pf-${path.substringAfterLast('/')}-p$sitePage",
                    title = "${t("page")} $sitePage",
                    list = items,
                )
            )
            val next = when {
                first == null || first == prevFirst -> null          // pagina vuota o ripetuta
                sitePage < maxPage -> { prevFirst = first; (sitePage + 1).toString() }
                else -> null
            }
            if (first != null) prevFirst = first
            Page(shelves, next)
        }
    }

    /** Feed di una sezione con lente di ricerca nativa in alto a destra (filtra gli album caricati). */
    private fun platformFeed(path: String): Feed<Shelf> =
        Feed(emptyList()) {
            platformPaged(path).toFeedData(
                buttons = Feed.Buttons(showSearch = true, showSort = false, showPlayAndShuffle = false)
            )
        }

    // ---------- TOP 100 / elenchi con "vedi tutto" ----------

    /** Shelf di anteprima (20 voci) con feed "vedi tutto" paginato per la lista completa. */
    private suspend fun topShelf(id: String, title: String, path: String, preview: Int = 20): Shelf? {
        val albums = runCatching { scrapeAlbumList("$KHI$path", preview) }.getOrDefault(emptyList())
        if (albums.isEmpty()) return null
        return Shelf.Lists.Items(id = id, title = title, list = albums, more = listMoreFeed(path, preview))
    }

    /**
     * "Vedi tutto" di una lista: continua dalla fine dell'anteprima con TUTTI gli
     * album di ogni pagina del sito, etichettati "Pagina N". Ferma su pagina vuota/ripetuta.
     */
    private fun listMorePaged(path: String, preview: Int): PagedData<Shelf> {
        var maxPage = 1
        var firstPage = true
        var prevFirst: String? = null
        return PagedData.Continuous { key ->
            val sitePage = key?.toIntOrNull() ?: 1
            val url = if (sitePage == 1) "$KHI$path" else "$KHI$path?page=$sitePage"
            val html = khinsiderGet(url)
            if (firstPage) {
                firstPage = false
                maxPage = footerMaxPage(html) ?: 1
            }
            val items = enrichWithCovers(parseAlbumList(html, 600, if (sitePage == 1) preview else 0))
            val first = items.firstOrNull()?.id
            val shelves: List<Shelf> = if (items.isEmpty()) emptyList()
            else listOf(
                Shelf.Lists.Items(
                    id = "top-${path.substringAfterLast('/')}-p$sitePage",
                    title = "${t("page")} $sitePage",
                    list = items,
                )
            )
            val next = when {
                first == null || first == prevFirst -> null          // pagina vuota o ripetuta
                sitePage < maxPage -> { prevFirst = first; (sitePage + 1).toString() }
                else -> null
            }
            if (first != null) prevFirst = first
            Page(shelves, next)
        }
    }

    private fun listMoreFeed(path: String, preview: Int): Feed<Shelf> =
        Feed(emptyList()) { listMorePaged(path, preview).toFeedData() }

    // ---------- HOME ----------

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("home", t("home")),
            Tab("top", t("top")),
            Tab("console", t("console")),
            Tab("tipo", t("tipo")),
        )
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "top" -> PagedData.Single {
                    listOfNotNull(
                        topShelf("top40", "Top 40", "/top40"),
                        topShelf("top100", t("top1000"), "/all-time-top-100"),
                        topShelf("top6m", t("top6m"), "/last-6-months-top-100"),
                        topShelf("topnew", t("topnew"), "/top-100-newly-added"),
                        topShelf("viewed", t("viewed"), "/currently-viewed"),
                        topShelf("favs", t("favs"), "/most-favorites"),
                    )
                }.toFeedData()
                "console" -> PagedData.Single {
                    listOf<Shelf>(platformCategories("console", t("console"), platforms))
                }.toFeedData(buttons = Feed.Buttons(showSearch = true, showSort = false, showPlayAndShuffle = false))
                "tipo" -> PagedData.Single {
                    listOf<Shelf>(platformCategories("tipo", t("tipo"), types))
                }.toFeedData(buttons = Feed.Buttons(showSearch = true, showSort = false, showPlayAndShuffle = false))
                else -> PagedData.Single {
                    listOfNotNull(
                        albumsShelf("latest", t("latest"), "/", 20),
                        albumsShelf("topnew", t("topnew"), "/top-100-newly-added", 50),
                        albumsShelf("viewed", t("viewed"), "/currently-viewed", 50),
                    )
                }.toFeedData()
            }
        }
    }

    // ---------- LOGIN (CustomInput: login programmatico XenForo, senza WebView) ----------

    private var user: User? = null
    private var cookie: String? = null

    override val forms: List<LoginClient.Form>
        get() = LoginClient.Form(
            key = "khinsider",
            label = t("login_label"),
            icon = LoginClient.InputField.Type.Username,
            inputFields = listOf(
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Username,
                    key = "username",
                    label = t("login_user"),
                    isRequired = true,
                ),
                LoginClient.InputField(
                    type = LoginClient.InputField.Type.Password,
                    key = "password",
                    label = t("login_pass"),
                    isRequired = true,
                )
            )
        ).let { listOf(it) }

    override suspend fun getCurrentUser(): User? = user

    /**
     * Login programmatico su XenForo:
     * 1) GET della pagina di login -> cookie di sessione + _xfToken del form
     * 2) POST delle credenziali + token -> Set-Cookie con xf_user se riuscito
     * 3) verifica sessione su /cp/favorites
     */
    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val username = data["username"]?.trim().orEmpty()
        val password = data["password"].orEmpty()
        if (username.isEmpty() || password.isEmpty()) throw Exception("Inserisci username e password")

        // 1) GET pagina di login
        val loginPageUrl = "$KHI/forums/index.php?login/&redirect=%2Fcp%2Favorites"
        val firstReq = Request.Builder().url(loginPageUrl).header("User-Agent", UA).build()
        val firstRes = client.newCall(firstReq).await()
        val loginHtml = firstRes.body?.string() ?: ""
        val sessionCookies = firstRes.headers("Set-Cookie").map { it.substringBefore(";") }
        firstRes.close()

        val token = Regex("""<input[^>]+name=["']_xfToken["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(loginHtml)?.value
            ?.let { Regex("""value=["']([^"']+)""").find(it)?.groupValues?.get(1) }
            ?: Regex("""<input[^>]+value=["']([^"']+)["'][^>]*name=["']_xfToken["']""", RegexOption.IGNORE_CASE)
                .find(loginHtml)?.groupValues?.get(1)
            ?: throw Exception(
                "Login: token del form non trovato. Il sito probabilmente mostra una verifica " +
                    "anti-bot (Cloudflare): riprova tra qualche minuto."
            )

        // 2) POST credenziali
        val body = "login=${URLEncoder.encode(username, "UTF-8")}" +
            "&password=${URLEncoder.encode(password, "UTF-8")}" +
            "&remember=1" +
            "&_xfToken=${URLEncoder.encode(token, "UTF-8")}" +
            "&_xfRedirect=%2Fcp%2Favorites"
        val postReq = Request.Builder()
            .url("$KHI/forums/index.php?login/login")
            .header("User-Agent", UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", sessionCookies.joinToString("; "))
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val postRes = noRedirectClient.newCall(postReq).await()
        val code = postRes.code
        val postCookies = postRes.headers("Set-Cookie").map { it.substringBefore(";") }
        val postHtml = postRes.body?.string() ?: ""
        postRes.close()

        if (postHtml.contains("challenge-platform") || postHtml.contains("cf-challenge")) {
            throw Exception("Login bloccato dal sistema anti-bot del sito (Cloudflare). Riprova tra qualche minuto.")
        }
        val session = mergeCookies(sessionCookies.joinToString("; "), postCookies)
        if (!session.contains("xf_user")) {
            throw Exception("Login non riuscito (HTTP $code). Controlla username e password.")
        }

        // 3) verifica sessione sul sito principale
        val session2 = warmUpSession(session)
        val (loggedIn, mainHtml) = checkMainSession(session2)
        if (!loggedIn) throw Exception("Login non riuscito: impossibile verificare la sessione.")

        // Nome utente reale (se estraibile con certezza), altrimenti etichetta generica.
        val accountHtml = runCatching {
            getPageWithCookie("$KHI/forums/index.php?account/", session2)
        }.getOrDefault("")
        val name = parseUsername(mainHtml, accountHtml)
        println("khinsider-login: nome rilevato = ${name ?: "NONE (fallback)"}")
        val displayName = name ?: t("login_ok")

        return listOf(
            User(
                id = "khinsider",
                name = displayName,
                subtitle = "Account khinsider",
                extras = mapOf("cookie" to session2),
            )
        )
    }

    override fun setLoginUser(user: User?) {
        this.user = user
        this.cookie = user?.extras?.get("cookie")
        // Cambio utente/logout: la prossima volta preferiti e playlist verranno riscaricati.
        synchronized(favoriteSlugs) {
            favoriteSlugs.clear()
            cachedFavorites.clear()
            favoritesLoaded = false
        }
        synchronized(cachedPlaylists) {
            cachedPlaylists.clear()
            playlistsLoaded = false
        }
    }

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

    /**
     * Cerca il nome utente reale. SOLO pattern specifici e verificati: il link profilo
     * XenForo /members/{nome}.{id}/ e gli elementi con class="username". NIENTE regex
     * generiche su "menu-header" (avevano agganciato "Direct Messages" — un elemento
     * di navigazione, non lo username).
     */
    private fun parseUsername(mainHtml: String, accountHtml: String): String? {
        val memberRe = Regex("""href="[^"]*/members/([^"/.]+)\.\d+/"""", RegexOption.IGNORE_CASE)
        for (html in listOf(accountHtml, mainHtml)) {
            memberRe.find(html)?.let {
                val n = it.groupValues[1].trim()
                if (n.isNotBlank()) {
                    println("khinsider-login: username da members link: $n")
                    return n
                }
            }
        }
        val usernameRe = Regex("""class="username">([^<]+)</span>""")
        for (html in listOf(accountHtml, mainHtml)) {
            usernameRe.find(html)?.let {
                val n = it.groupValues[1].trim()
                if (n.isNotBlank()) {
                    println("khinsider-login: username da class=username: $n")
                    return n
                }
            }
        }
        val headerRe = Regex("""class="menu-header-main">([^<]+)</span>""")
        for (html in listOf(accountHtml, mainHtml)) {
            headerRe.find(html)?.let {
                val n = it.groupValues[1].trim()
                if (n.isNotBlank()) {
                    println("khinsider-login: username da menu-header-main: $n")
                    return n
                }
            }
        }
        Regex("""Welcome(?:\s+back)?,\s*([^<!"']+)""", RegexOption.IGNORE_CASE).find(mainHtml)?.let {
            val n = it.groupValues[1].trim()
            if (n.isNotBlank()) {
                println("khinsider-login: username da Welcome: $n")
                return n
            }
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

    /** Estrae album + mappa slug->albumid da UNA pagina di /cp/favorites. */
    private fun parseFavoritesPage(html: String): Pair<List<Album>, Map<String, String>> {
        val favs = parseAlbumList(html, Int.MAX_VALUE)   // TUTTI gli album della pagina, niente limite 30
        val albumPos = albumLinkRegex.findAll(html)
            .map { it.range.first to "/game-soundtracks/album/${it.groupValues[1].trim()}" }
            .toList()
        val idPos = Regex("""albumid\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.range.first to it.groupValues[1] }
            .toList()
        val map = mutableMapOf<String, String>()
        for ((pos, slugPath) in albumPos) {
            idPos.minByOrNull { (it.first - pos).absoluteValue }?.second?.let { map[slugPath] = it }
        }
        return favs to map
    }

    /**
     * Sincronizza lo stato locale con /cp/favorites scaricando TUTTE le pagine
     * (il sito pagina la lista: "Page 1 of N" con link ?page=N). Oltre la prima
     * pagina gli album resterebbero invisibili in Libreria e col cuore spento.
     * Se il sito non paginasse, il comportamento resta identico a prima.
     * Restituisce la lista completa per la sezione Libreria.
     */
    private suspend fun refreshFavorites(c: String): List<Album> {
        synchronized(favoriteSlugs) { if (favoritesLoaded) return cachedFavorites.toList() }

        val allAlbums = LinkedHashMap<String, Album>()
        val slugToId = mutableMapOf<String, String>()
        var page = 1
        var maxPage = 1
        var first = true
        while (true) {
            val url = if (page == 1) "$KHI/cp/favorites" else "$KHI/cp/favorites?page=$page"
            val html = runCatching { khinsiderGet(url, c) }.getOrDefault("")
            val loggedOut = html.contains("need to be registered and logged in") ||
                html.contains("/forums/login") || html.contains(">Log In")
            if (loggedOut) {
                synchronized(favoriteSlugs) {
                    favoriteSlugs.clear()
                    slugToAlbumId.clear()
                    cachedFavorites.clear()
                    favoritesLoaded = true
                }
                return emptyList()
            }
            if (first) {
                first = false
                maxPage = footerMaxPage(html) ?: 1
            }
            val (favs, ids) = parseFavoritesPage(html)
            favs.forEach { allAlbums.putIfAbsent(it.id, it) }
            slugToId.putAll(ids)
            if (page >= maxPage) break
            page++
        }

        val result = allAlbums.values.toList()
        synchronized(favoriteSlugs) {
            favoriteSlugs.clear()
            favoriteSlugs.addAll(result.map { it.id })
            slugToAlbumId.clear()
            slugToAlbumId.putAll(slugToId)
            cachedFavorites.clear()
            cachedFavorites.addAll(result)
            favoritesLoaded = true
        }
        return result
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
        Regex("""album_favorite_toggle\?albumid=(\d+)""").find(html)?.let { return it.groupValues[1] }
        Regex("""albumid=(\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""albumid\s*=\s*["'](\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""data-album-?id\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""name=["']albumid["'][^>]*?value=["'](\d+)""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""value=["'](\d+)["'][^>]*?name=["']albumid["']""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""(?:album_?id|ALBUM_ID)\s*[:=]\s*["']?(\d{4,7})""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        Regex("""(?:favorite|fav)\w*\s*\(\s*["']?(\d{4,7})""", RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
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
                if (cachedFavorites.none { it.id == item.id }) cachedFavorites.add(0, item as Album)
            } else {
                favoriteSlugs.remove(item.id)
                cachedFavorites.removeAll { it.id == item.id }
            }
        }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) = setFavorite(item, shouldLike)

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean = isFavorite(item)

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) = setFavorite(item, shouldSave)

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean = isFavorite(item)

    // ---------- PLAYLIST (richiede account PRO sul sito) ----------

    /** Playlist lette una volta per sessione (come i preferiti). */
    private suspend fun ensurePlaylists(c: String): List<Playlist> {
        synchronized(cachedPlaylists) { if (playlistsLoaded) return cachedPlaylists.toList() }
        return refreshPlaylists(c)
    }

    private suspend fun refreshPlaylists(c: String): List<Playlist> {
        val html = runCatching { khinsiderGet("$KHI/playlist/browse", c) }.getOrDefault("")
        if (html.contains(">Log In") || html.contains("/forums/login")) return emptyList()
        val parsed = parsePlaylistPage(html)
        synchronized(cachedPlaylists) {
            cachedPlaylists.clear()
            cachedPlaylists.addAll(parsed)
            playlistsLoaded = true
        }
        return parsed
    }

    /** Estrae le playlist dalla pagina /playlist/browse (righe di tabella). */
    private fun parsePlaylistPage(html: String): List<Playlist> {
        val result = mutableListOf<Playlist>()
        for (tr in Regex("""<tr>.*?</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val row = tr.value
            val href = Regex("""/playlist/([^"/]+)""").find(row)?.groupValues?.get(1) ?: continue
            val name = Regex("""<td><a href="/playlist/[^"/]+"[^>]*>(.*?)</a></td>""", RegexOption.DOT_MATCHES_ALL)
                .find(row)?.groupValues?.get(1)
                ?.let { stripHtml(it) }
                ?: continue
            if (name.isBlank() || name.all { it.isDigit() }) continue   // ignora la cella del conteggio
            val count = Regex("""<td><a href="/playlist/[^"/]+"[^>]*>(\d+)</a></td>""")
                .find(row)?.groupValues?.get(1)?.toLongOrNull()
            val cover = Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(row)?.groupValues?.get(1)
            result += Playlist(
                id = "/playlist/$href",
                title = name,
                isEditable = true,
                isPrivate = true,          // le playlist del sito sono private (link condivisibile)
                cover = cover?.let { imageUrl(it, shelfCoverSize)?.toImageHolder() },
                trackCount = count,
                subtitle = count?.let { "$it ${t("tracks")}" },
                isShareable = true,
            )
        }
        return result
    }

    /** L'id NUMERICO della playlist ("/playlist/53984ydko" -> "53984"), usato da tutti gli endpoint. */
    private fun playlistNumericId(playlistId: String): String? =
        Regex("""/playlist/(\d+)""").find(playlistId)?.groupValues?.get(1)

    // ---------- PlaylistClient ----------

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val c = cookie ?: return playlist
        val cached = runCatching { ensurePlaylists(c) }.getOrDefault(emptyList())
        return cached.firstOrNull { it.id == playlist.id } ?: playlist
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val c = cookie ?: throw ClientException.LoginRequired()
        val html = runCatching { khinsiderGet("$KHI${playlist.id}", c) }.getOrDefault("")
        return parsePlaylistTracks(html, playlist).toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    /**
     * Parser delle tracce della pagina playlist (struttura verificata):
     * righe <tr songid="..." playlistid="..."> con link al file .mp3
     * (doppio-encodato %2520, normalizzato da decodeAll all'uso),
     * link album, durata e copertina.
     */
    private fun parsePlaylistTracks(html: String, playlist: Playlist): List<Track> {
        val out = mutableListOf<Track>()
        val pid = playlistNumericId(playlist.id) ?: ""
        val rowRe = Regex("""<tr songid="(\d+)"[^>]*>.*?</tr>""", RegexOption.DOT_MATCHES_ALL)
        for (tr in rowRe.findAll(html)) {
            val row = tr.value
            val sid = tr.groupValues[1]
            val songCell = Regex("""<td class="clickable-row">(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                .find(row)?.groupValues?.get(1) ?: continue
            val aLinks = Regex("""<a href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(songCell).toList()
            if (aLinks.size < 2) continue
            val trackHref = aLinks[0].groupValues[1]
            if (!trackHref.endsWith(".mp3")) continue
            val title = stripHtml(aLinks[0].groupValues[2]).ifBlank {
                trackHref.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
            }
            val albumHref = aLinks[1].groupValues[1]
            val albumTitle = stripHtml(aLinks[1].groupValues[2]).ifBlank {
                albumHref.substringAfterLast('/').replace('-', ' ')
            }
            val cover = Regex("""<td class="albumIcon">.*?<img src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .find(row)?.groupValues?.get(1)
            val duration = Regex("""style="font-weight:normal;">(\d+:\d+)</a>""")
                .find(row)?.groupValues?.get(1)?.let { parseDuration(it) }
            val number = Regex("""<td align="right" style="padding-right: 8px;">(\d+)\.</td>""")
                .find(row)?.groupValues?.get(1)?.toLongOrNull()
            val album = Album(
                id = albumHref,
                title = albumTitle,
                cover = cover?.let { imageUrl(it, trackCoverSize)?.toImageHolder() },
                isLikeable = true,
                isShareable = true,
            )
            out += Track(
                id = trackHref,
                title = title,
                album = album,
                cover = album.cover,
                duration = duration,
                albumOrderNumber = number,
                isShareable = true,
                streamables = listOf(Streamable.server(id = trackHref, quality = 4, title = "MP3")),
                extras = mapOf("songid" to sid, "playlistid" to pid),
            )
        }
        return out
    }

    // ---------- PlaylistEditClient ----------

    override suspend fun listEditablePlaylists(track: Track?): List<Pair<Playlist, Boolean>> {
        val c = cookie ?: throw ClientException.LoginRequired()
        val list = runCatching { ensurePlaylists(c) }.getOrDefault(emptyList())
        // "già nella playlist?" richiederebbe lo scan di ogni playlist:
        // per ora false (il sito gestisce comunque i duplicati).
        return list.map { it to false }
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        val c = cookie ?: throw ClientException.LoginRequired()
        val url = "$KHI/playlist/add?name=${URLEncoder.encode(title, "UTF-8")}"
        val request = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Cookie", c)
            .build()
        val response = noRedirectClient.newCall(request).await()
        val code = response.code
        val location = response.header("Location")
        response.close()
        synchronized(cachedPlaylists) { playlistsLoaded = false }   // la lista va ricaricata
        if (location != null) {
            val id = Regex("""/playlist/([^"/?]+)""").find(location)?.groupValues?.get(1)
            if (id != null) {
                return Playlist(
                    id = "/playlist/$id", title = title,
                    isEditable = true, isPrivate = true, isShareable = true,
                )
            }
        }
        // Fallback: il sito non ha rediretto (o Location non è leggibile):
        // ricarica la lista e cerca la nuova playlist per titolo.
        val list = refreshPlaylists(c)
        return list.firstOrNull { it.title == title }
            ?: throw Exception("Creazione playlist: non trovo '$title' dopo la creazione (HTTP $code)")
    }

    override suspend fun deletePlaylist(playlist: Playlist) {
        val c = cookie ?: throw ClientException.LoginRequired()
        val pid = playlistNumericId(playlist.id) ?: throw Exception("id playlist non valido: ${playlist.id}")
        val code = httpGetStatus("$KHI/playlist/delete?playlistid=$pid", c)
        if (code !in 200..399) throw Exception("Eliminazione playlist fallita (HTTP $code)")
        synchronized(cachedPlaylists) { playlistsLoaded = false }
    }

    override suspend fun editPlaylistMetadata(playlist: Playlist, title: String, description: String?) {
        val c = cookie ?: throw ClientException.LoginRequired()
        val pid = playlistNumericId(playlist.id) ?: throw Exception("id playlist non valido: ${playlist.id}")
        val url = "$KHI/playlist/edit?playlistid=$pid&name=${URLEncoder.encode(title, "UTF-8")}"
        val code = httpGetStatus(url, c)
        if (code !in 200..399) throw Exception("Rinomina playlist fallita (HTTP $code)")
        synchronized(cachedPlaylists) { playlistsLoaded = false }
    }

    /**
     * Aggiunge tracce a una playlist. Endpoint dedotto dal naming del sito
     * (song_delete, song_order_update): /playlist/song_add?playlistid=X&songid=Y.
     * Se il sito lo chiamasse diversamente, dimmelo (o cattura la richiesta da
     * DevTools) e cambio il nome dell'endpoint.
     */
    /**
     * Aggiunge tracce a una playlist. Endpoint dedotto dal naming del sito
     * (song_delete, song_order_update): /playlist/song_add?playlistid=X&songid=Y.
     * Il successo è confermato dal corpo della risposta (il JS del sito controlla
     * che il JSON sia "1"); ogni fallimento lancia con URL e corpo della risposta
     * per una diagnosi immediata.
     */
    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>,
    ) {
        val c = cookie ?: throw ClientException.LoginRequired()
        val pid = playlistNumericId(playlist.id) ?: throw Exception("id playlist non valido: ${playlist.id}")
        for (track in new) {
            val sid = songIdOf(track, c)
            val url = "$KHI/playlist/song_add?playlistid=$pid&songid=$sid"
            val request = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Cookie", c)
                .build()
            val response = client.newCall(request).await()
            val code = response.code
            val body = response.body?.string() ?: ""
            response.close()
            println("khinsider-playlist: add -> HTTP $code, body=${body.trim().take(150)}")
            if (code !in 200..399 || body.trim() != "1") {
                throw Exception(
                    "Aggiunta traccia '${track.title}' fallita (HTTP $code): " +
                        "$url -> risposta: ${body.trim().take(120)}"
                )
            }
        }
        synchronized(cachedPlaylists) { playlistsLoaded = false }
    }


    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>,
    ) {
        val c = cookie ?: throw ClientException.LoginRequired()
        val pid = playlistNumericId(playlist.id) ?: throw Exception("id playlist non valido: ${playlist.id}")
        for (i in indexes.sortedDescending()) {
            val track = tracks.getOrNull(i) ?: continue
            val sid = track.extras["songid"]
                ?: throw Exception("songid mancante per la traccia '${track.title}' (riapri la playlist)")
            val code = httpGetStatus("$KHI/playlist/song_delete?playlistid=$pid&songid=$sid", c)
            if (code !in 200..399) throw Exception("Rimozione traccia fallita (HTTP $code)")
        }
        synchronized(cachedPlaylists) { playlistsLoaded = false }
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int,
    ) {
        val c = cookie ?: throw ClientException.LoginRequired()
        val pid = playlistNumericId(playlist.id) ?: throw Exception("id playlist non valido: ${playlist.id}")
        val track = tracks.getOrNull(fromIndex)
            ?: throw Exception("Traccia non trovata all'indice $fromIndex")
        val sid = track.extras["songid"]
            ?: throw Exception("songid mancante per la traccia '${track.title}' (riapri la playlist)")
        // Lo script del sito invia order = posizione della riga (1-based, con header).
        val code = httpGetStatus("$KHI/playlist/song_order_update?order=${toIndex + 1}&songid=$sid&playlistid=$pid", c)
        if (code !in 200..399) throw Exception("Riordino traccia fallito (HTTP $code)")
        synchronized(cachedPlaylists) { playlistsLoaded = false }
    }

    // ---------- PlaylistEditPrivacyClient ----------

    override suspend fun setPrivacy(playlist: Playlist, isPrivate: Boolean) {
        // Le playlist di khinsider sono sempre private (URL condivisibile con hash).
        throw Exception("Privacy playlist: il sito non offre playlist pubbliche")
    }

    /**
     * songid numerico di una traccia, letto dalla pagina album (div playlistAddTo).
     * Fallback: se l'URL non combacia (formato diverso mirror/sito, album mancante
     * nel modello), prova per POSIZIONE usando albumOrderNumber.
     */
    private suspend fun songIdOf(track: Track, c: String): String {
        // Se la traccia ha già il songid (es. letta da una playlist), usalo direttamente.
        track.extras["songid"]?.let { return it }

        val key = trackPath(track.id)
            ?: throw Exception("URL traccia non valido: ${track.id}")
        synchronized(songIdCache) { songIdCache[key]?.let { return it } }

        // Album dalla traccia, o derivato dall'URL se il modello non lo porta.
        val albumId = track.album?.id
            ?: "/game-soundtracks/album/${key.removePrefix("/game-soundtracks/album/").substringBefore('/')}"
        val html = runCatching { khinsiderGet("$KHI$albumId", c) }.getOrDefault("")
        val entries = parseAlbumSongIds(html)   // (pathNormalizzato, songid) in ordine di riga
        val sid = entries.firstOrNull { it.first == key }?.second
            ?: track.albumOrderNumber?.let { n -> entries.getOrNull(n.toInt() - 1)?.second }
            ?: throw Exception("songid non trovato per '${track.title}' (serve un account PRO)")
        synchronized(songIdCache) { songIdCache[key] = sid }
        return sid
    }

    /** (path normalizzato, songid) per ogni riga della pagina album, in ordine. */
    private fun parseAlbumSongIds(html: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (tr in Regex("""<tr>.*?</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val row = tr.value
            val sid = Regex("""playlistAddTo"\s+songid="(\d+)"""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1) ?: continue
            val href = Regex("""href="(/game-soundtracks/album/[^"/]+/[^"]+\.mp3)"""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1) ?: continue
            trackPath(href)?.let { out += it to sid }
        }
        return out
    }

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
        return Shelf.Lists.Items(id = "lib_history", title = t("history"), list = merged.values.toList())
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
                shelves += Shelf.Lists.Items(id = "lib_favs", title = t("my_favs"), list = favsWithCovers)
            }
        }
        // Cronologia: locale + sito in un'unica sezione, visibile anche senza login.
        historyShelf()?.let { shelves += it }
        if (c != null) {
            albumsShelf("lib_uploads", t("my_uploads"), "/cp/uploads", 30, c)?.let { shelves += it }
            val playlists = runCatching { ensurePlaylists(c) }.getOrDefault(emptyList())
            if (playlists.isNotEmpty()) {
                shelves += Shelf.Lists.Items(id = "lib_playlists", title = t("playlists"), list = playlists)
            }
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
        else listOf(Shelf.Lists.Items(id = "latest", title = t("latest_search"), list = albums))
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
        else listOf(Shelf.Lists.Items(id = "albums", title = t("album"), list = results))
        shelves.toFeed()
    }

    // ---------- Album ----------

    // Cache LRU (max 20 album) dei metadati: evita di riscaricare l'album
    // a ogni tap quando si alternano pochi album di fila. Solo le risposte
    // riuscite vengono memorizzate (niente "0 tracce" da fallimenti transitori).
    private val albumMetaCache = object : LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?): Boolean = size > 20
    }

    private suspend fun albumMeta(id: String): JsonObject {
        synchronized(albumMetaCache) { albumMetaCache[id]?.let { return it } }
        val json = runCatching {
            getJson(apiUrl("/api/album", mapOf("url" to id))).jsonObject
        }.getOrNull() ?: return buildJsonObject { put("name", "") }
        synchronized(albumMetaCache) { albumMetaCache[id] = json }
        return json
    }

    override suspend fun loadAlbum(album: Album): Album {
        recordHistory(album)   // apertura album → cronologia locale
        return albumMeta(album.id).toAlbumDetails(album)
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val mirror = runCatching { albumMeta(album.id).toTracks(album) }.getOrDefault(emptyList())
        if (mirror.isNotEmpty()) return mirror.toFeed()
        // Fallback: mirror vuoto/assente -> tracce dalla pagina album del sito,
        // così l'album si apre SEMPRE con le sue tracce (mai "0 tracce").
        val html = runCatching { khinsiderGet("$KHI${album.id}") }.getOrDefault("")
        return parseAlbumPageTracks(html, album).toFeed()
    }

    /** Tracce dalla pagina album del sito (struttura verificata sulla pagina reale). */
    private fun parseAlbumPageTracks(html: String, album: Album): List<Track> {
        val out = mutableListOf<Track>()
        for (tr in Regex("""<tr>.*?</tr>""", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val row = tr.value
            val mp3 = Regex("""href="(/game-soundtracks/album/[^"/]+/[^"]+\.mp3)"""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1) ?: continue
            val flac = Regex("""href="(/game-soundtracks/album/[^"/]+/[^"]+\.flac)"""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1)
            val title = Regex("""<td class="clickable-row">\s*<a href="[^"]+\.mp3"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .find(row)?.groupValues?.get(1)?.let { stripHtml(it) }
                ?: continue
            val duration = Regex("""style="font-weight:normal;">(\d+:\d+)</a>""")
                .find(row)?.groupValues?.get(1)?.let { parseDuration(it) }
            val number = Regex("""<td align="right" style="padding-right: 8px;">(\d+)\.</td>""")
                .find(row)?.groupValues?.get(1)?.toLongOrNull()
            out += Track(
                id = mp3,
                title = title,
                album = album,
                cover = album.cover,
                duration = duration,
                albumOrderNumber = number,
                isShareable = true,
                streamables = listOf(Streamable.server(id = mp3, quality = 4, title = "MP3")) +
                    (if (flac != null) listOf(Streamable.server(id = flac, quality = 7, title = "FLAC")) else emptyList()),
            )
        }
        return out
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ---------- Traccia ----------

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        // Normalizza l'id (decodifica doppia: il sito emette URL con %2520).
        val decoded = decodeAll(streamable.id)
        val isFlac = decoded.endsWith("#flac")
        val pageUrl = if (isFlac) decoded.removeSuffix("#flac") else decoded
        val cacheKey = if (isFlac) "$pageUrl#flac" else pageUrl
        val direct = audioCache[cacheKey] ?: runCatching {
            resolveAudio(pageUrl, if (isFlac) "flac" else "mp3")
        }.getOrElse {
            if (isFlac) resolveAudio(pageUrl, "mp3") else throw it
        }.also { audioCache[cacheKey] = it }
        // Il link diretto va decodificato PRIMA del proxy, altrimenti il mirror
        // riceve un URL con doppia codifica (%2520) e il download fallisce.
        return downloadUrl(decodeAll(direct)).toServerMedia()
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
