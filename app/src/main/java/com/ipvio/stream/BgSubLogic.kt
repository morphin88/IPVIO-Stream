package com.ipvio.stream

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Bulgarian subtitle sites (no API, no key): search form and result page of subsunacs.net, plus what their files need
 * before Media3 can show them: archive type, choice of the file inside, Windows-1251 text and frame-based MicroDVD
 * (.sub) subtitles. Without Android and without org.json, testable; the requests are in [SubSearch].
 *
 * The site knows no TMDB ID. Movies are found by title and year, episodes by "Title 01x02" (the site's own naming).
 */
object BgSubLogic {
    const val UNACS = "SubsUnacs"
    const val UNACS_BASE = "https://subsunacs.net"
    const val UNACS_SEARCH = "$UNACS_BASE/search.php"
    /** The site's pages and form values are Windows-1251. */
    const val PAGE_CHARSET = "windows-1251"

    /** One row of the result page. [path] = "/subtitles/Name-123/" (the download), [fps] 0.0 = unknown. */
    data class Row(
        val path: String, val title: String, val year: Int?, val cds: Int, val fps: Double, val downloads: Int, val info: String
    )

    // ------------------------------------------------------------------ Search

    /** Title as the site's search wants it: no accents, no punctuation ("Dune: Part Two" → "Dune Part Two"). */
    fun plainTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKD).replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""['’`]"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** Search text: the plain title, for an episode followed by "01x02". Empty = nothing to search for. */
    fun term(title: String, season: Int, episode: Int): String {
        val t = plainTitle(title)
        if (t.isEmpty()) return ""
        return if (season > 0 && episode > 0) String.format(Locale.ROOT, "%s %02dx%02d", t, season, episode) else t
    }

    /** Body of the search form (application/x-www-form-urlencoded, Windows-1251). null = no usable title. */
    fun unacsForm(title: String, year: Int?, season: Int, episode: Int): String? {
        val m = term(title, season, episode).take(70)
        if (m.isEmpty()) return null
        val isEpisode = season > 0 && episode > 0
        val p = listOf(
            "m" to m, "l" to "0", "c" to "", "y" to (if (isEpisode || year == null) "" else year.toString()),
            "action" to "   Търси   ", "a" to "", "d" to "", "u" to "", "g" to "", "t" to "", "imdbcheck" to "1"
        )
        return p.joinToString("&") { it.first + "=" + URLEncoder.encode(it.second, PAGE_CHARSET) }
    }

    private val ROW = Regex("""(?is)<tr[^>]*\sonmouseover\s*=[^>]*>(.*?)</tr>""")
    private val CELL = Regex("""(?is)<td[^>]*>(.*?)</td>""")
    private val LINK = Regex("""(?is)<a\s+href="([^"]+)"[^>]*\stitle="([^"]*)"[^>]*>(.*?)</a>""")
    private val YEAR = Regex("""\((\d{4})\)""")
    private val TAG = Regex("""(?s)<[^>]*>""")
    private val PATH = Regex("""^/subtitles/[^/?#\s"'<>\\]{1,200}-\d{1,9}/$""")
    private val EP = Regex("""(?i)(?<!\d)0*(\d{1,3})x0*(\d{1,4})(?!\d)""")

    /** Only download paths of the site's own form; anything else (other host, query string) is dropped. */
    fun unacsPath(href: String): String? = href.trim().takeIf { PATH.matches(it) }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#039;", "'").replace("&#39;", "'").replace("&nbsp;", " ").replace(" ", " ").replace("&amp;", "&")

    private fun text(html: String) = unescape(TAG.replace(html, " ")).replace(Regex("""\s+"""), " ").trim()

    /** Rows of a result page ([html] already decoded from Windows-1251). Unknown markup = empty list. */
    fun unacsRows(html: String): List<Row> {
        val out = ArrayList<Row>()
        for (m in ROW.findAll(html)) {
            val cells = CELL.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            if (cells.size < 7) continue
            val a = LINK.find(cells[0]) ?: continue
            val path = unacsPath(unescape(a.groupValues[1])) ?: continue
            // The tooltip holds the uploader's note (release names) as escaped markup after "Инфо:"
            val tip = unescape(a.groupValues[2])
            val info = text(tip.substringAfter("Инфо:", "")).take(300)
            val after = cells[0].substring(a.range.last + 1)
            out.add(
                Row(
                    path, text(a.groupValues[3]), YEAR.find(text(after))?.groupValues?.get(1)?.toIntOrNull(),
                    text(cells[1]).toIntOrNull() ?: 1, text(cells[2]).toDoubleOrNull()?.takeIf { it in 10.0..120.0 } ?: 0.0,
                    text(cells[6]).toIntOrNull() ?: 0, info
                )
            )
        }
        return out
    }

    private fun words(s: String) = plainTitle(s).lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }

    /** Row title without the episode part: "Breaking Bad - 01x01" → "breaking bad". */
    private fun baseTitle(rowTitle: String) = words(EP.replace(rowTitle, " ").substringBefore(" - Complete Season"))

    /**
     * Rows that fit what is playing, best first. Episode: only rows named after that episode. Movie: no episode rows, no
     * multi-CD subtitles (they do not fit one stream), year at most one off. The title must be the same; more downloads
     * first. An automatic choice takes the first row, so a doubtful row is dropped rather than ranked low.
     */
    fun unacsPick(rows: List<Row>, title: String, year: Int?, season: Int, episode: Int): List<Row> {
        val want = words(title)
        if (want.isEmpty()) return emptyList()
        val isEpisode = season > 0 && episode > 0
        return rows.filter { r ->
            val ep = EP.find(r.title)
            val epOk = if (isEpisode) ep != null && ep.groupValues[1].toInt() == season && ep.groupValues[2].toInt() == episode
            else ep == null && r.cds <= 1 && (year == null || r.year == null || kotlin.math.abs(year - r.year) <= 1)
            epOk && baseTitle(r.title) == want
        }.distinctBy { it.path }.sortedByDescending { it.downloads }
    }

    /** Text for the hit list: the first release-like name from the note, otherwise the row title. */
    fun release(r: Row): String =
        Regex("""[\w.\-\[\]]*\d{3,4}p[\w.\-\[\]]*|[\w\-]+(?:\.[\w\-]+){3,}""").find(r.info)?.value?.trim('.', '-') ?: r.title

    // ------------------------------------------------------------------ Archive

    enum class Kind { ZIP, RAR4, RAR5, SEVENZ, PLAIN }

    /** Type of a downloaded file from its first bytes; [Kind.PLAIN] = not an archive (a bare subtitle file). */
    fun kind(d: ByteArray): Kind {
        fun at(vararg b: Int) = d.size >= b.size && b.indices.all { d[it] == b[it].toByte() }
        return when {
            at(0x50, 0x4B, 0x03, 0x04) -> Kind.ZIP
            at(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00) -> Kind.RAR5
            at(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) -> Kind.RAR4
            at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> Kind.SEVENZ
            else -> Kind.PLAIN
        }
    }

    private val SUB_EXT = setOf("srt", "sub", "txt", "ass", "ssa", "vtt")
    private val README = Regex("""(?i)subsunacs|sab\.bz|yavka|read.?me|procheti|прочети|изтеглено|info""")

    /**
     * Which file of an archive to show ([names] = entry paths). ".txt" read-me files are skipped, and so is a ".sub" that
     * has an ".idx" next to it (VobSub pictures, not text). The file named after the episode wins, ".srt" before the rest.
     */
    fun pickEntry(names: List<String>, season: Int = 0, episode: Int = 0): String? {
        fun base(n: String) = n.substringAfterLast('/').substringAfterLast('\\')
        fun ext(n: String) = base(n).substringAfterLast('.', "").lowercase(Locale.ROOT)
        val idx = names.filter { ext(it) == "idx" }.map { it.substringBeforeLast('.').lowercase(Locale.ROOT) }.toSet()
        val ok = names.filter { n ->
            val e = ext(n)
            e in SUB_EXT && !(e == "txt" && README.containsMatchIn(base(n))) && !(e == "sub" && n.substringBeforeLast('.').lowercase(Locale.ROOT) in idx)
        }
        val ep = if (season > 0 && episode > 0) Regex("""(?i)(?:s0*$season\W?e0*$episode|(?<!\d)0*${season}x0*$episode)(?!\d)""") else null
        fun score(n: String) = (if (ep?.containsMatchIn(base(n)) == true) 4 else 0) + (if (ext(n) == "srt") 2 else if (ext(n) == "txt") 0 else 1)
        return ok.sortedBy { base(it).lowercase(Locale.ROOT) }.maxByOrNull { score(it) }
    }

    // ------------------------------------------------------------------ Text

    /**
     * Subtitle text as a string. UTF-8 and UTF-16 (with BOM) as they are; otherwise Windows-1251 when the text is
     * Cyrillic ([cyrillic] = Bulgarian was asked for, or most non-ASCII bytes are Cyrillic letters), else Windows-1252.
     */
    fun decode(data: ByteArray, cyrillic: Boolean = false): String {
        fun b(i: Int) = data[i].toInt() and 0xFF
        if (data.size >= 3 && b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return String(data, 3, data.size - 3, Charsets.UTF_8)
        if (data.size >= 2 && b(0) == 0xFF && b(1) == 0xFE) return String(data, 2, data.size - 2, Charsets.UTF_16LE)
        if (data.size >= 2 && b(0) == 0xFE && b(1) == 0xFF) return String(data, 2, data.size - 2, Charsets.UTF_16BE)
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() }.getOrNull()
        if (utf8 != null) return utf8
        return String(data, charset(if (cyrillic || looksCyrillic(data)) "windows-1251" else "windows-1252"))
    }

    /** Windows-1251 text: letters are 0xC0–0xFF and make up a large part of the file; Western text has few such bytes. */
    fun looksCyrillic(data: ByteArray): Boolean {
        var high = 0; var letters = 0; var alpha = 0
        for (x in data) {
            val v = x.toInt() and 0xFF
            if (v >= 0x80) { high++; if (v >= 0xC0) letters++ } else if (v in 0x41..0x5A || v in 0x61..0x7A) alpha++
        }
        return high > 0 && letters * 10 >= high * 9 && letters * 2 > alpha
    }

    private val MDVD = Regex("""^\{(\d+)}\{(\d*)}(.*)$""")
    private val MDVD_STYLE = Regex("""\{[a-zA-Z]:[^}]*}""")

    /** True if [text] is MicroDVD (lines "{start frame}{end frame}text"). */
    fun isMicroDvd(text: String): Boolean = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(5).toList()
        .let { l -> l.isNotEmpty() && l.count { MDVD.matches(it) } * 2 > l.size }

    /**
     * MicroDVD to SubRip. The times follow from the frame rate: a first line "{1}{1}23.976" names it, otherwise [fps]
     * (from the site's list), otherwise 23.976. "|" separates lines, "{y:i}" becomes italics. null = no subtitle lines.
     */
    fun microDvdToSrt(text: String, fps: Double = 0.0): String? {
        var rate = if (fps in 10.0..120.0) fps else 23.976
        val sb = StringBuilder()
        var n = 0
        for (raw in text.lineSequence()) {
            val m = MDVD.find(raw.trim().trimStart('﻿')) ?: continue
            val from = m.groupValues[1].toLongOrNull() ?: continue
            var body = m.groupValues[3]
            if (n == 0 && from <= 1) { body.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 10.0..120.0 }?.let { rate = it; body = "" } }
            val to = m.groupValues[2].toLongOrNull() ?: (from + (rate * 3).toLong())
            val lines = body.split('|').map { l ->
                val italic = Regex("""(?i)\{y:[^}]*i[^}]*}""").containsMatchIn(l)
                val t = MDVD_STYLE.replace(l, "").trim().let { if (it.startsWith("/")) it.drop(1).trim() else it }
                if (italic && t.isNotEmpty()) "<i>$t</i>" else t
            }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || to <= from) continue
            n++
            sb.append(n).append('\n').append(stamp(from, rate)).append(" --> ").append(stamp(to, rate)).append('\n')
            sb.append(lines.joinToString("\n")).append("\n\n")
        }
        return if (n == 0) null else sb.toString()
    }

    private fun stamp(frame: Long, fps: Double): String {
        val ms = Math.round(frame * 1000.0 / fps)
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /**
     * A downloaded subtitle file as UTF-8 bytes Media3 can read, with the extension to store it under: MicroDVD
     * (".sub"/".txt") becomes SubRip, everything else keeps its format. null = not a text subtitle.
     */
    fun normalize(name: String, data: ByteArray, fps: Double = 0.0, cyrillic: Boolean = true): Pair<String, ByteArray>? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val s = decode(data, cyrillic)
        if (isMicroDvd(s)) return microDvdToSrt(s, fps)?.let { "srt" to it.toByteArray(Charsets.UTF_8) }
        if (ext == "sub") return null // VobSub or another binary format
        val out = if (ext in setOf("ass", "ssa", "vtt")) ext else "srt"
        if (out == "srt" && !Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(s)) return null
        return out to s.toByteArray(Charsets.UTF_8)
    }
}
