package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date as EchoDate
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class KhinsiderExtension : ExtensionClient, HomeFeedClient, SearchFeedClient, AlbumClient, TrackClient {

    private val client = OkHttpClient()
    private lateinit var setting: Settings

    override suspend fun getSettingItems(): List<Setting> = emptyList()

    override fun setSettings(settings: Settings) {
        setting = settings
    }

    // ---------- API ----------

    private val baseUrl = "https://khinsider.squid.wtf"

    private fun apiUrl(path: String, query: Map<String, String> = emptyMap()): String {
        val params = query.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "$baseUrl$path${if (params.isEmpty()) "" else "?$params"}"
    }

    private suspend fun getJson(url: String): JsonObject {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
        val body = response.body?.string() ?: throw Exception("Risposta vuota")
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun imageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return apiUrl("/api/image", mapOf("url" to raw))
    }

    private fun downloadUrl(mp3: String): String =
        apiUrl("/api/download", mapOf("url" to mp3))

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

    // ---------- Conversione modelli ----------

    private fun JsonObject.toAlbumItem(): Album? {
        val title = str("title")?.takeIf { it.isNotBlank() } ?: return null
        val id = albumPathOf(str("albumId") ?: str("id") ?: str("url")) ?: return null
        val cover = imageUrl(str("icon") ?: str("image"))
        val subtitle = listOfNotNull(str("albumType"), str("year")).joinToString(" • ").ifBlank { null }
        return Album(id = id, title = title, cover = cover, subtitle = subtitle)
    }

    private fun JsonObject.toAlbumDetails(album: Album): Album {
        val title = str("name") ?: album.title
        val year = str("year")
        val cover = imageUrl(str("coverUrl")) ?: album.cover
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
        val cover = imageUrl(str("coverUrl")) ?: album.cover
        val artistName = str("albumArtist")
        val artists = artistName?.takeIf { it.isNotBlank() }?.let {
            listOf(Artist(id = it, name = it))
        } ?: emptyList()
        val albumModel = Album(id = album.id, title = albumTitle, cover = cover)
        val tracks = runCatching { this["tracks"]?.jsonArray }.getOrNull() ?: return emptyList()
        return tracks.mapNotNull { item ->
            val o = item.jsonObject
            val title = o.str("title") ?: return@mapNotNull null
            val mp3 = o.str("url") ?: return@mapNotNull null
            val quality = qualityOf(o.str("bitrate"))
            Track(
                id = mp3,
                title = title,
                artists = artists,
                album = albumModel,
                cover = cover,
                duration = parseDuration(o.str("duration")),
                albumOrderNumber = o.str("number")?.toLongOrNull(),
                streamables = listOf(
                    Streamable.server(id = mp3, quality = quality, title = "MP3")
                )
            )
        }
    }

    // ---------- Home ----------

    private suspend fun latestShelves(): List<Shelf> {
        val json = getJson(apiUrl("/api/latest-home"))
        val albums = runCatching {
            json.jsonArray.mapNotNull { it.jsonObject.toAlbumItem() }
        }.getOrDefault(emptyList())
        return if (albums.isEmpty()) emptyList()
        else listOf(Shelf.Lists.Items(id = "latest", title = "Ultimi arrivi", list = albums))
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> = latestShelves().toFeed()

    // ---------- Ricerca ----------

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return Feed(listOf()) { latestShelves().toFeedData() }
        }
        val json = getJson(apiUrl("/api/search", mapOf("q" to query)))
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
            getJson(apiUrl("/api/album", mapOf("url" to id)))
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
    ): Streamable.Media = downloadUrl(streamable.id).toGetRequest().toServerMedia()

    override suspend fun loadFeed(track: Track): Feed<Shelf> = emptyList<Shelf>().toFeed()
}
