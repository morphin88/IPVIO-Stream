package com.ipvio.stream

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Online subtitles in the player (see [SubSearchLogic], [BgSubLogic]): search the services, download the chosen file, keep
 * it per title and attach it to the running stream. Bulgarian is also searched on subsunacs.net and subs.sab.bz (no key needed). A subtitle chosen once is attached automatically the next time.
 */
object SubSearch {
    private val client = Net.base.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private const val MAX_FILES = 200
    private const val TAG = "IPVIO-Sub"

    /** A downloaded subtitle file attached to a title. */
    data class Sub(val file: File, val lang: String, val label: String) {
        val mime get() = SubSearchLogic.mime(file.name)
    }

    /** Search result: hits (best first) or an error text (German, gets translated). */
    class Result(val hits: List<SubSearchLogic.Hit>, val error: String?)

    fun hasKeys() = Config.OS_KEY.isNotEmpty() || Config.SD_KEY.isNotEmpty()

    private fun osReq(url: String) = Request.Builder().url(url)
        .header("Api-Key", Config.OS_KEY).header("User-Agent", SubSearchLogic.UA).header("Accept", "application/json")

    private fun get(r: Request): Pair<Int, String> = client.newCall(r).execute().use { it.code to (it.body?.string().orEmpty()) }

    suspend fun search(q: SubSearchLogic.Query): Result = withContext(Dispatchers.IO) {
        val bgWanted = "bg" in q.langs && q.title.isNotBlank()
        if (!hasKeys() && !bgWanted) return@withContext Result(emptyList(), "Kein Schlüssel für die Untertitel-Suche. Einstellungen > API-Schlüssel")
        if (q.tmdbId <= 0 && q.title.isBlank()) return@withContext Result(emptyList(), "Titel unbekannt")
        coroutineScope {
            val os = async {
                if (Config.OS_KEY.isEmpty()) null else runCatching {
                    val (code, body) = get(osReq(SubSearchLogic.osSearchUrl(q)).build())
                    if (code == 200) SubSearchLogic.osHits(body) to null else emptyList<SubSearchLogic.Hit>() to "$code"
                }.getOrElse { emptyList<SubSearchLogic.Hit>() to errName(it) }
            }
            val sd = async {
                if (Config.SD_KEY.isEmpty()) null else runCatching {
                    val (code, body) = get(Request.Builder().url(SubSearchLogic.sdSearchUrl(q, Config.SD_KEY)).build())
                    if (code == 200) {
                        val p = SubSearchLogic.sdParse(body, q)
                        // Diagnostics only: HTTP code and counts, never the address (it holds the key)
                        Log.i(TAG, "SubDL http=200 raw=${p.raw} kept=${p.hits.size} episode=${q.isEpisode} tmdb=${q.tmdbId > 0} error=${p.error}")
                        p.hits to p.error
                    } else {
                        Log.i(TAG, "SubDL http=$code")
                        emptyList<SubSearchLogic.Hit>() to SubSearchLogic.sdHttpError(code, body)
                    }
                }.getOrElse { emptyList<SubSearchLogic.Hit>() to errName(it) }
            }
            val bg = async { if (bgWanted) bgHits(q) else emptyList() }
            val parts = listOfNotNull(os.await()?.let { SubSearchLogic.OS to it }, sd.await()?.let { SubSearchLogic.SD to it }) + bg.await()
            val all = parts.flatMap { it.second.first }
            val hits = SubSearchLogic.rank(all, q, AppSettings.ccPref)
            Log.i(TAG, "search found=${all.size} wanted=${hits.size} langs=${q.langs}")
            val errs = parts.filter { it.second.second != null }.joinToString(" · ") { it.first + ": " + it.second.second }
            Result(hits, when {
                hits.isNotEmpty() -> null
                all.isNotEmpty() -> "Keine Untertitel in den gewünschten Sprachen"
                errs.isNotEmpty() -> "Suche fehlgeschlagen · $errs"
                else -> null
            })
        }
    }

    private fun errName(e: Throwable) = when (e) {
        is java.net.UnknownHostException -> "DNS"
        is java.net.SocketTimeoutException -> "Timeout"
        else -> e.javaClass.simpleName
    }

    /** Downloads [h] and keeps it for title [itemId]. Error text (German) if that is not possible. */
    suspend fun download(c: Context, itemId: String, h: SubSearchLogic.Hit, season: Int = 0, episode: Int = 0): Pair<Sub?, String?> = withContext(Dispatchers.IO) {
        try {
            val (name, data) = when (h.provider) {
                SubSearchLogic.OS -> osFile(h) ?: return@withContext null to osError
                BgSubLogic.UNACS, BgSubLogic.SAB -> bgFile(h, season, episode)
                else -> sdFile(h, season, episode) ?: return@withContext null to "Download fehlgeschlagen"
            }
            val dir = File(c.filesDir, "subs").apply { mkdirs() }
            val ext = name.substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
            val f = File(dir, "s" + Integer.toHexString(itemId.hashCode()) + "_" + h.lang.take(5).filter(Char::isLetterOrDigit) + "." + ext)
            // Many files are not UTF-8: Windows-1251 for Cyrillic text, otherwise Windows-1252
            f.writeBytes(BgSubLogic.decode(data, h.lang.startsWith("bg")).toByteArray(Charsets.UTF_8))
            val sub = Sub(f, h.lang.take(2), SubSearchLogic.label(h))
            remember(c, itemId, sub)
            prune(dir)
            sub to null
        } catch (e: BgFail) {
            null to (e.message ?: "Download fehlgeschlagen")
        } catch (e: Throwable) {
            null to "Download fehlgeschlagen: " + errName(e)
        }
    }

    @Volatile private var osError = ""

    private fun osFile(h: SubSearchLogic.Hit): Pair<String, ByteArray>? {
        val body = """{"file_id":${h.ref.toLong()}}""".toRequestBody("application/json".toMediaType())
        val (code, text) = get(osReq(SubSearchLogic.OS_API + "/download").post(body).build())
        val o = runCatching { JSONObject(text) }.getOrNull()
        val link = o?.optString("link")?.takeIf { code == 200 && it.startsWith("https://") }
        if (link == null) {
            // e.g. 406: daily download limit reached ("message" says until when)
            osError = if (code == 406) "OpenSubtitles: Tageslimit erreicht" else "OpenSubtitles: Fehler $code"
            return null
        }
        client.newCall(Request.Builder().url(link).header("User-Agent", SubSearchLogic.UA).build()).execute().use { r ->
            if (!r.isSuccessful) { osError = "OpenSubtitles: Fehler ${r.code}"; return null }
            val bytes = r.body?.bytes() ?: return null
            return o.optString("file_name").ifBlank { "sub.srt" } to bytes
        }
    }

    private fun sdFile(h: SubSearchLogic.Hit, season: Int, episode: Int): Pair<String, ByteArray>? {
        val path = SubSearchLogic.sdPath(h.ref) ?: return null
        client.newCall(Request.Builder().url(SubSearchLogic.SD_DL + path).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return SubSearchLogic.fromZip(r.body?.bytes() ?: return null, season, episode, h.lang)
        }
    }

    // ------------------------------------------------------------------ Bulgarian sites (subsunacs.net, subs.sab.bz)

    /** The sites answer scripts without a browser-like User-Agent with an empty page. */
    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val MAX_ARCHIVE = 8_000_000L

    private fun page(r: Request): Pair<String?, String?> = client.newCall(r).execute().use { resp ->
        if (!resp.isSuccessful) null to "${resp.code}"
        else String(resp.body?.bytes() ?: ByteArray(0), charset(BgSubLogic.PAGE_CHARSET)) to null
    }

    private fun hit(r: BgSubLogic.Row) = SubSearchLogic.Hit(
        r.site, r.path, "bg", BgSubLogic.release(r), false, if (r.pack) 0 else r.downloads, if (r.pack) PACK else "archive", pack = r.pack, fps = r.fps
    )

    /** Marks a hit that is a whole season in one archive ([SubSearchLogic.Hit.format]). */
    private const val PACK = "pack"

    /** The sites filter by the exact year; a playlist year that is one off would find nothing, so the year is optional. */
    private fun unacsRows(title: String, q: SubSearchLogic.Query): Pair<List<BgSubLogic.Row>, String?> {
        fun run(year: Int?): Pair<List<BgSubLogic.Row>, String?> {
            val form = BgSubLogic.unacsForm(title, year, q.season, q.episode) ?: return emptyList<BgSubLogic.Row>() to null
            val (html, err) = page(
                Request.Builder().url(BgSubLogic.UNACS_SEARCH).header("User-Agent", BROWSER_UA)
                    .header("Referer", BgSubLogic.UNACS_BASE + "/index.php")
                    .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
            )
            return (html?.let { BgSubLogic.pick(BgSubLogic.unacsRows(it), title, q.year, q.season, q.episode) } ?: emptyList()) to err
        }
        val first = run(q.year)
        return if (first.first.isEmpty() && first.second == null && q.year != null && !q.isEpisode) run(null) else first
    }

    private fun sabRows(title: String, q: SubSearchLogic.Query): Pair<List<BgSubLogic.Row>, String?> {
        fun run(year: Int?): Pair<List<BgSubLogic.Row>, String?> {
            val url = BgSubLogic.sabUrl(title, year, q.isEpisode) ?: return emptyList<BgSubLogic.Row>() to null
            val (html, err) = page(Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Referer", BgSubLogic.SAB_BASE + "/").build())
            return (html?.let { BgSubLogic.pick(BgSubLogic.sabRows(it), title, q.year, q.season, q.episode) } ?: emptyList()) to err
        }
        val first = run(q.year)
        return if (first.first.isEmpty() && first.second == null && q.year != null && !q.isEpisode) run(null) else first
    }

    /**
     * English and original title of a TMDB entry (the sites list those, playlists often carry a translated title).
     * Empty without a TMDB key or ID, and on any error.
     */
    private fun tmdbTitles(q: SubSearchLogic.Query): List<String> {
        if (q.tmdbId <= 0 || Config.TMDB_KEY.isBlank()) return emptyList()
        return runCatching {
            val type = if (q.isEpisode) "tv" else "movie"
            val (code, body) = get(Request.Builder().url("https://api.themoviedb.org/3/$type/${q.tmdbId}?api_key=${Config.TMDB_KEY}&language=en-US").build())
            if (code != 200) return emptyList()
            val o = JSONObject(body)
            listOf(o.optString(if (q.isEpisode) "name" else "title"), o.optString(if (q.isEpisode) "original_name" else "original_title")).filter { it.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    /**
     * Both Bulgarian sites at once. The titles are tried one after the other (TMDB's first, then the playlist's) until
     * one of them finds something. Returns the hits per site name with its error (null = answered).
     */
    private suspend fun bgHits(q: SubSearchLogic.Query): List<Pair<String, Pair<List<SubSearchLogic.Hit>, String?>>> = coroutineScope {
        var last = emptyList<Pair<String, Pair<List<SubSearchLogic.Hit>, String?>>>()
        for (title in BgSubLogic.titles(q.title, tmdbTitles(q)).take(3)) {
            val sites = listOf<Pair<String, (String, SubSearchLogic.Query) -> Pair<List<BgSubLogic.Row>, String?>>>(
                BgSubLogic.UNACS to ::unacsRows, BgSubLogic.SAB to ::sabRows
            ).map { (name, f) ->
                async {
                    // Throwable: a pattern or class problem on one device must not take the whole search down
                    name to try { f(title, q).let { it.first.map(::hit) to it.second } } catch (e: Throwable) { emptyList<SubSearchLogic.Hit>() to errName(e) }
                }
            }
            last = sites.map { it.await() }
            if (last.any { it.second.first.isNotEmpty() }) break
        }
        last
    }

    /** A failure with a reason the person can read (shown in the search panel or as a notice). */
    private class BgFail(message: String) : Exception(message)

    /**
     * Bytes of [url]. subsunacs.net and subs.sab.bz send the file only to a request that carries a Referer from the
     * site, and subsunacs.net sends its details page (not the file) to a request that has none: callers choose.
     */
    private fun fetchBytes(url: String, referer: String?, site: String, max: Long): ByteArray {
        val rb = Request.Builder().url(url).header("User-Agent", BROWSER_UA)
        if (referer != null) rb.header("Referer", referer)
        return client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw BgFail("$site: HTTP ${resp.code}")
            val b = resp.body ?: throw BgFail("$site: empty answer")
            if (b.contentLength() > max) throw BgFail("$site: file too big")
            val s = b.source()
            s.request(max + 1)
            if (s.buffer.size > max) throw BgFail("$site: file too big")
            s.buffer.readByteArray()
        }
    }

    /**
     * subsunacs.net: the details page lists the files of the archive and each can be downloaded alone, so no archive is
     * unpacked. null = the page has no such list (changed markup): the caller falls back to the archive.
     */
    private fun unacsEntryFile(h: SubSearchLogic.Hit, season: Int, episode: Int): Pair<String, ByteArray>? {
        val path = BgSubLogic.unacsPath(h.ref) ?: throw BgFail("SubsUnacs: bad link")
        val html = String(fetchBytes(BgSubLogic.UNACS_BASE + path, null, "SubsUnacs", 2_000_000), charset(BgSubLogic.PAGE_CHARSET))
        val left = BgSubLogic.unacsEntries(html).toMutableList()
        if (left.isEmpty()) return null
        val total = left.size
        var tries = 0
        while (left.isNotEmpty() && tries++ < 3) {
            val name = BgSubLogic.pickEntry(left.map { it.second }, season, episode, h.format == PACK) ?: break
            val e = left.removeAt(left.indexOfFirst { it.second == name })
            val bytes = fetchBytes(BgSubLogic.UNACS_BASE + e.first, null, "SubsUnacs", 4_000_000)
            BgSubLogic.normalize(name, bytes, h.fps)?.let { return "sub." + it.first to it.second }
        }
        throw BgFail("SubsUnacs: no usable subtitle file ($total files listed)")
    }

    /** Downloads the file of [h] and returns the subtitle as UTF-8 ("sub.srt" etc.). Throws [BgFail] with the reason. */
    private fun bgFile(h: SubSearchLogic.Hit, season: Int, episode: Int): Pair<String, ByteArray> {
        val site = if (h.provider == BgSubLogic.SAB) "subs.sab.bz" else "SubsUnacs"
        if (h.provider != BgSubLogic.SAB) unacsEntryFile(h, season, episode)?.let { return it }
        val bytes = if (h.provider == BgSubLogic.SAB) {
            fetchBytes(BgSubLogic.sabDownload(h.ref) ?: throw BgFail("$site: bad link"), BgSubLogic.SAB_BASE + "/", site, MAX_ARCHIVE)
        } else {
            fetchBytes(BgSubLogic.UNACS_BASE + (BgSubLogic.unacsPath(h.ref) ?: throw BgFail("$site: bad link")), BgSubLogic.UNACS_SEARCH, site, MAX_ARCHIVE)
        }
        val pack = h.format == PACK
        val kind = BgSubLogic.kind(bytes)
        if (kind == BgSubLogic.Kind.PLAIN) {
            if (pack) throw BgFail("$site: season pack is not an archive")
            return BgSubLogic.normalize("sub.srt", bytes, h.fps)?.let { "sub." + it.first to it.second }
                ?: throw BgFail("$site: the site sent no subtitle file")
        }
        val files = SubArchive.subtitles(bytes).toMutableList()
        if (files.isEmpty()) throw BgFail("$site: ${kind.name} archive could not be read")
        val total = files.size
        // Best entry first; if it is not a text subtitle (e.g. a picture-based .sub), try the next one
        while (files.isNotEmpty()) {
            val name = BgSubLogic.pickEntry(files.map { it.first }, season, episode, pack) ?: break
            val i = files.indexOfFirst { it.first == name }
            BgSubLogic.normalize(name, files[i].second, h.fps)?.let { return "sub." + it.first to it.second }
            files.removeAt(i)
        }
        throw BgFail("$site: no usable subtitle file in the archive ($total files)")
    }

    // ------------------------------------------------------------------ Automatic (Bulgarian)

    /** Titles already searched without success in this app run (no new search on every start of the same title). */
    private val autoMiss = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Automatic download is on when Bulgarian is the subtitle language and subtitles are not switched off. */
    fun autoWanted(): Boolean = AppSettings.subMode != TrackLogic.SUB_OFF && AppSettings.subLangEffective().take(2).lowercase() == "bg"

    /**
     * Searches Bulgarian subtitles for [itemId] and downloads the best hit (the next ones if a download fails).
     * Returns the subtitle, or null with a reason to show; null without reason = nothing to do (already chosen earlier or
     * searched in this app run). Does not touch the player.
     */
    suspend fun auto(c: Context, itemId: String, q: SubSearchLogic.Query): Pair<Sub?, String?> {
        if (q.title.isBlank() || saved(c, itemId) != null || itemId in autoMiss) return null to null
        val r = search(q.copy(langs = listOf("bg")))
        // A network error is not remembered: the next start searches again
        if (r.hits.isEmpty()) return null to (r.error ?: "nothing found for \u201e${q.title}\u201c").also { if (r.error == null) autoMiss.add(itemId) }
        var last: String? = null
        for (h in r.hits.take(3)) {
            val (s, err) = download(c, itemId, h, q.season, q.episode)
            if (s != null) return s to null
            last = err
        }
        autoMiss.add(itemId)
        return null to (last ?: "download failed")
    }

    // ------------------------------------------------------------------ Per title

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ipvio_subs", Context.MODE_PRIVATE)

    private fun remember(c: Context, itemId: String, s: Sub) {
        prefs(c).edit().putString(itemId, listOf(s.file.name, s.lang, s.label.replace('\t', ' ')).joinToString("\t")).apply()
    }

    /** Subtitle chosen earlier for [itemId] (file still there), otherwise null. */
    fun saved(c: Context, itemId: String): Sub? {
        val p = prefs(c).getString(itemId, null)?.split('\t') ?: return null
        if (p.size != 3 || p[0].contains('/')) return null
        val f = File(File(c.filesDir, "subs"), p[0])
        return if (f.isFile) Sub(f, p[1], p[2]) else null
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    // ------------------------------------------------------------------ Player

    fun config(s: Sub): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s.file))
            .setMimeType(s.mime).setLanguage(s.lang).setLabel(s.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()

    /** Media item for [url] with the subtitle chosen earlier for [itemId]. */
    fun item(c: Context, url: String, itemId: String?): MediaItem {
        val s = itemId?.let { saved(c, it) } ?: return MediaItem.fromUri(url)
        return MediaItem.Builder().setUri(url).setSubtitleConfigurations(listOf(config(s))).build()
    }

    /** Attach [s] to the running stream at the current position and switch it on. */
    fun attach(player: Player, s: Sub): Boolean {
        val cur = player.currentMediaItem ?: return false
        if (cur.localConfiguration == null) return false
        val others = cur.localConfiguration!!.subtitleConfigurations.filter { it.uri != Uri.fromFile(s.file) }
        val item = cur.buildUpon().setSubtitleConfigurations(others + config(s)).build()
        val pos = player.currentPosition
        val play = player.playWhenReady
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguages(s.lang).build()
        player.setMediaItem(item, pos)
        player.prepare()
        player.playWhenReady = play
        return true
    }

    // ------------------------------------------------------------------ Key check (Settings)

    suspend fun checkOs(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Searching works with any key; a download of a file that does not exist tells whether the key is known
            val r = Request.Builder().url(SubSearchLogic.OS_API + "/download")
                .header("Api-Key", SubSearchLogic.cleanKey(key)).header("User-Agent", SubSearchLogic.UA).header("Accept", "application/json")
                .post("""{"file_id":1}""".toRequestBody("application/json".toMediaType())).build()
            val (code, body) = get(r)
            if (code == 200 || body.contains("Invalid file_id", true)) null else if (code in setOf(401, 403, 503)) "Schlüssel ungültig" else "Fehler $code"
        }.getOrElse { "Keine Verbindung: " + errName(it) }
    }

    suspend fun checkSd(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val q = SubSearchLogic.Query("", null, 27205, 0, 0, listOf("en"))
            val (code, body) = get(Request.Builder().url(SubSearchLogic.sdSearchUrl(q, key)).build())
            if (code == 200 && body.contains("\"status\":true")) null
            else if (body.contains("api_key", true) || code == 401 || code == 403) "Schlüssel ungültig" else "Fehler $code"
        }.getOrElse { "Keine Verbindung: " + errName(it) }
    }
}
