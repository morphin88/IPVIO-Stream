package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BgSubLogicTest {
    /** Markup as subsunacs.net sent it on 14 Nov 2026 (two rows, shortened notes). */
    private fun row(path: String, title: String, year: String, cds: Int, fps: String, dl: Int, note: String) =
        """<tr bgcolor="#333333" onmouseover="this.style.backgroundColor='#454545'" onmouseout="this.style.backgroundColor='#333333'">""" +
            """<td class="tdMovie"><a href="$path" class="tooltip" title="&lt;img width=&quot;120&quot; src=&quot;/ii/big/1.jpg&quot;&gt;""" +
            """&lt;div&gt;&lt;b&gt;Филм: $title&lt;/b&gt;&lt;br&gt;&lt;b&gt;Формат: &lt;/b&gt;SRT &lt;br&gt;&lt;b&gt;Инфо: &lt;/b&gt;&lt;br&gt;$note&lt;/div&gt;">""" +
            """$title</a><span class="smGray">&nbsp;($year)</span></td><td>$cds</td><td>$fps</td><td><a href="$path!" target="_blank">""" +
            "\r\n" + """<img src="/r/r5.gif" width="16" height="10" alt="5.00" title="5.00"></a></td><td><a href="$path!#comments">1</a></td>""" +
            """<td><a href="/search.php?t=1&memid=1">someone</a></td><td>$dl</td><td><a href="$path!"><span class="sm">Инфо</span></a></td>""" +
            """<td align="center">---</td><td><input class="filesChk" type="checkbox" name="ids[]" value="1" /></td></tr>"""

    private val page = "<table><tbody>" +
        row("/subtitles/Inception-65218/", "Inception", "2010", 2, "23.976", 12572, "Inception.RERiP.DVDRiP.XviD-ARROW&lt;br&gt;Final edit.") +
        row("/subtitles/Inception-65217/", "Inception", "2010", 1, "23.976", 12370, "Inception.2010.DVDRip.XviD.AC3-ViSiON") +
        row("/subtitles/Inception-64816/", "Inception", "2010", 1, "25.000", 5692, "") +
        row("/subtitles/Inception_Making_Of-1/", "Inception - The Cobol Job", "2010", 1, "23.976", 99999, "") +
        row("/subtitles/Breaking_Bad_01x01-85212/", "Breaking Bad - 01x01", "2008", 1, "23.976", 400, "Breaking.Bad.S01E01.720p.HDTV.x264-BiA") +
        row("/subtitles/Breaking_Bad_01x02-85258/", "Breaking Bad - 01x02", "2008", 1, "", 900, "") +
        row("https://evil.tld/subtitles/x-1/", "Inception", "2010", 1, "23.976", 5, "") +
        "</tbody></table>"

    @Test fun searchTerm() {
        assertEquals("Dune Part Two", BgSubLogic.term("Dune: Part Two", 0, 0))
        assertEquals("Shogun 01x09", BgSubLogic.term("Shōgun", 1, 9))
        assertEquals("Schindlers List", BgSubLogic.term("Schindler's List", 0, 0))
        assertEquals("", BgSubLogic.term(" - ", 1, 2))
    }

    @Test fun form() {
        val f = BgSubLogic.unacsForm("Dune: Part Two", 2024, 0, 0)!!
        assertTrue(f.startsWith("m=Dune+Part+Two&l=0&c=&y=2024&action=+++%D2%FA%F0%F1%E8+++&"))
        assertTrue(BgSubLogic.unacsForm("Breaking Bad", 2008, 1, 1)!!.startsWith("m=Breaking+Bad+01x01&l=0&c=&y=&"))
        assertNull(BgSubLogic.unacsForm("", 2000, 0, 0))
    }

    @Test fun rows() {
        val r = BgSubLogic.unacsRows(page)
        assertEquals(6, r.size) // the foreign link is dropped
        assertEquals(BgSubLogic.Row("/subtitles/Inception-65218/", "Inception", 2010, 2, 23.976, 12572, "Inception.RERiP.DVDRiP.XviD-ARROW Final edit."), r[0])
        assertEquals(0.0, r[5].fps, 0.0)
        assertEquals(emptyList<BgSubLogic.Row>(), BgSubLogic.unacsRows("<html>blocked</html>"))
        assertNull(BgSubLogic.unacsPath("/subtitles/a-1/?x=1"))
        assertNull(BgSubLogic.unacsPath("/subtitles/../a-1/x/"))
    }

    @Test fun pickMovieAndEpisode() {
        val r = BgSubLogic.unacsRows(page)
        // two CDs and another title are out; more downloads first
        assertEquals(listOf("/subtitles/Inception-65217/", "/subtitles/Inception-64816/"), BgSubLogic.pick(r, "Inception", 2010, 0, 0).map { it.path })
        assertEquals(emptyList<BgSubLogic.Row>(), BgSubLogic.pick(r, "Inception", 1999, 0, 0))
        assertEquals(listOf("/subtitles/Breaking_Bad_01x01-85212/"), BgSubLogic.pick(r, "Breaking Bad", null, 1, 1).map { it.path })
        assertEquals(emptyList<BgSubLogic.Row>(), BgSubLogic.pick(r, "Breaking Bad", null, 0, 0))
        assertEquals("Breaking.Bad.S01E01.720p.HDTV.x264-BiA", BgSubLogic.release(r[4]))
        assertEquals("Inception", BgSubLogic.release(r[2]))
    }

    private val sabRow = """<tr class="subs-row">
		<td class="c1field">Форум</td>
        <td class="c1trailer">&nbsp;</td>
		<td class="c1notice">&nbsp;</td><td class="c2field"><a href="http://subs.sab.bz/index.php?act=download&attach_id=TID" target="_blank" onMouseover="ddrivetip('<div id=\\'tooltip1\\'><img src=\\'http://st.subs.sab.bz/x.jpg\\' border=\\'0\\' /></div><span class=\\'tooltip_title\\'><b>TTITLE</b> </span><br/><b>Формат</b>: Srt<br/>&lt;b&gt;Режисьор&lt;/b&gt;: Someone&lt;br /&gt;&lt;b&gt;Доп. инфо&lt;/b&gt;: &lt;br /&gt;TINFO<br/><br/><div><b>Забележки от екипа:</b><br/>• x</div>','#555')" onMouseout="hideddrivetip()">TTITLE</a> (TYEAR)</td>
		<td>14 Nov 2010</td>
		<td>&#1041;&#1098;&#1083;&#1075;</td>
		<td>TCDS</td>
        <td>23.976</td>
		<td><a href="http://subs.sab.bz/forum/index.php?showuser=1" target="_blank">ArcMage</a></td>
		<td><a href="http://www.imdb.com/title/tt1375666/" target="_blank">URL</a></td>
		<td>TDL</td>
		<td><a href="http://subs.sab.bz/index.php?act=details&amp;sid=1&amp;type=ratings"><img src="x.gif" alt="Rating: 0"/></a></td>
	  </tr>"""

    private fun sab(id: Int, title: String, year: Int, cds: Int, dl: Int, info: String = "") = sabRow.replace("TID", id.toString())
        .replace("TTITLE", title).replace("TYEAR", year.toString()).replace("TCDS", cds.toString()).replace("TDL", dl.toString()).replace("TINFO", info)

    @Test fun sabSearch() {
        assertEquals("http://subs.sab.bz/index.php?act=search&movie=Inception&select-language=2&upldr=&yr=2010&release=", BgSubLogic.sabUrl("Inception", 2010, false))
        assertTrue(BgSubLogic.sabUrl("The Last of Us", 2023, true)!!.contains("movie=The+Last+of+Us&select-language=2&upldr=&yr=&"))
        assertEquals("http://subs.sab.bz/index.php?act=download&attach_id=51764", BgSubLogic.sabDownload("51764"))
        assertNull(BgSubLogic.sabDownload("1&x=2"))
        val page = sab(51764, "Inception", 2010, 1, 7239, "Inception.2010.DVDRip.XviD.AC3-ViSiON&lt;br /&gt;Inception.2010.DVDRip.XviD.AC3-KiNGS") +
            sab(108333, "The Last of Us - Season 2", 2025, 7, 5959) + sab(104500, "The Last of Us - Season 1", 2023, 9, 16257) +
            sab(108332, "The Last of Us - 02x07", 2025, 1, 9394) + sab(108285, "The Last of Us - 02x06", 2025, 1, 10297)
        val r = BgSubLogic.sabRows(page)
        assertEquals(5, r.size)
        assertEquals(
            BgSubLogic.Row("51764", "Inception", 2010, 1, 23.976, 7239, "Inception.2010.DVDRip.XviD.AC3-ViSiON Inception.2010.DVDRip.XviD.AC3-KiNGS", BgSubLogic.SAB), r[0]
        )
        assertEquals("Inception.2010.DVDRip.XviD.AC3-ViSiON", BgSubLogic.release(r[0]))
        assertEquals(listOf("51764"), BgSubLogic.pick(r, "Inception", 2010, 0, 0).map { it.path })
        // the episode itself first, then the pack of its season; other seasons and episodes are out
        val ep = BgSubLogic.pick(r, "The Last of Us", null, 2, 7)
        assertEquals(listOf("108332" to false, "108333" to true), ep.map { it.path to it.pack })
        assertEquals(listOf("104500" to true), BgSubLogic.pick(r, "The Last of Us", null, 1, 3).map { it.path to it.pack })
        assertEquals(emptyList<BgSubLogic.Row>(), BgSubLogic.pick(r, "The Last of Us", 2023, 0, 0))
        assertEquals(2, BgSubLogic.packSeason("The Last of Us - Season 2"))
        assertEquals(1, BgSubLogic.packSeason("Breaking Bad - 01 - Complete Season"))
        assertNull(BgSubLogic.packSeason("Dune - Part Two"))
    }

    @Test fun playlistTitles() {
        assertEquals("Inception", BgSubLogic.cleanTitle("BG| Inception [FHD]"))
        assertEquals("Inception", BgSubLogic.cleanTitle("[BG] Inception (BG AUDIO)"))
        assertEquals("Inception", BgSubLogic.cleanTitle("Начало / Inception"))
        assertEquals("The Uprising", BgSubLogic.cleanTitle("The Uprising"))
        assertEquals("Начало", BgSubLogic.cleanTitle("БГ: Начало"))
        assertEquals(listOf("Inception", "Начало", "BG| Начало"), BgSubLogic.titles("BG| Начало", listOf("Inception", "inception")))
        assertEquals(listOf("Dune: Part Two"), BgSubLogic.titles("Dune: Part Two"))
    }

    @Test fun packEntry() {
        val names = listOf("The.Last.of.Us.S02E06.WEB.srt", "The.Last.of.Us.S02E07.WEB.srt")
        assertEquals("The.Last.of.Us.S02E07.WEB.srt", BgSubLogic.pickEntry(names, 2, 7, true))
        assertNull(BgSubLogic.pickEntry(names, 2, 9, true))
        assertNull(BgSubLogic.pickEntry(listOf("ep one.srt", "ep two.srt"), 2, 1, true))
    }

    @Test fun archiveKind() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        assertEquals(BgSubLogic.Kind.ZIP, BgSubLogic.kind(b(0x50, 0x4B, 3, 4, 20)))
        assertEquals(BgSubLogic.Kind.RAR5, BgSubLogic.kind(b(0x52, 0x61, 0x72, 0x21, 0x1A, 7, 1, 0)))
        assertEquals(BgSubLogic.Kind.RAR4, BgSubLogic.kind(b(0x52, 0x61, 0x72, 0x21, 0x1A, 7, 0, 0xCF)))
        assertEquals(BgSubLogic.Kind.PLAIN, BgSubLogic.kind("1\n00:00:01,000 --> 00:00:02,000\n".toByteArray()))
    }

    @Test fun entryChoice() {
        assertEquals("Movie.srt", BgSubLogic.pickEntry(listOf("subsunacs.net.txt", "Movie.srt", "Movie.sub")))
        assertEquals("s/Show.S01E02.srt", BgSubLogic.pickEntry(listOf("s/Show.S01E01.srt", "s/Show.S01E02.srt", "s/Show.S01E12.srt"), 1, 2))
        assertEquals("a.txt", BgSubLogic.pickEntry(listOf("a.txt", "Procheti.txt")))
        assertNull(BgSubLogic.pickEntry(listOf("movie.idx", "movie.sub", "readme.txt")))
    }

    @Test fun cyrillicText() {
        val bg = "1\n00:00:01,000 --> 00:00:02,000\nЗдравей, свят!\n"
        assertEquals(bg, BgSubLogic.decode(bg.toByteArray(charset("windows-1251"))))
        assertEquals(bg, BgSubLogic.decode(bg.toByteArray(Charsets.UTF_8)))
        assertEquals(bg, BgSubLogic.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bg.toByteArray(Charsets.UTF_8)))
        val de = "1\n00:00:01,000 --> 00:00:02,000\nGrüße aus Köln, schön dass du da bist\n"
        assertEquals(de, BgSubLogic.decode(de.toByteArray(charset("windows-1252"))))
        assertFalse(BgSubLogic.looksCyrillic(de.toByteArray(charset("windows-1252"))))
    }

    @Test fun microDvd() {
        val sub = "{1}{1}25.000\n{25}{75}Първи ред|втори ред\n{100}{150}{y:i}Курсив\n{200}{}Без край\n"
        assertTrue(BgSubLogic.isMicroDvd(sub))
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\nПърви ред\nвтори ред\n\n2\n00:00:04,000 --> 00:00:06,000\n<i>Курсив</i>\n\n" +
                "3\n00:00:08,000 --> 00:00:11,000\nБез край\n\n",
            BgSubLogic.microDvdToSrt(sub)
        )
        // without a rate line the site's rate counts
        assertTrue(BgSubLogic.microDvdToSrt("{24}{48}x\n", 24.0)!!.contains("00:00:01,000 --> 00:00:02,000"))
        assertFalse(BgSubLogic.isMicroDvd("1\n00:00:01,000 --> 00:00:02,000\nx\n"))
    }

    @Test fun normalizeFile() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nЗдравей\n"
        val (ext, data) = BgSubLogic.normalize("Movie.SRT", srt.toByteArray(charset("windows-1251")))!!
        assertEquals("srt", ext)
        assertEquals(srt, String(data, Charsets.UTF_8))
        assertEquals("srt", BgSubLogic.normalize("Movie.sub", "{25}{50}Ред\n".toByteArray(charset("windows-1251")), 25.0)!!.first)
        assertNull(BgSubLogic.normalize("Movie.sub", byteArrayOf(0, 0, 1, 0xBA.toByte(), 0x44)))
        assertNull(BgSubLogic.normalize("notes.txt", "Преводът е мой.".toByteArray(charset("windows-1251"))))
    }
}
