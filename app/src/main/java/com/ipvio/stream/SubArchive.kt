package com.ipvio.stream

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Reads subtitle files out of a downloaded archive: ZIP with java.util.zip, RAR 4/5 and 7z with libarchive. The Bulgarian
 * subtitle sites deliver archives, and java.util.zip alone cannot open RAR. Which entry is shown is decided in [BgSubLogic].
 */
object SubArchive {
    private val EXT = setOf("srt", "sub", "txt", "ass", "ssa", "vtt", "idx")
    private const val MAX_ENTRIES = 400
    private const val MAX_FILES = 60
    private const val MAX_BYTES = 4_000_000

    /**
     * Entries with a subtitle extension as (path, content); ".idx" entries come with empty content (only their name
     * matters). Larger files, encrypted entries and broken archives are skipped; an unreadable archive = empty list.
     */
    fun subtitles(data: ByteArray): List<Pair<String, ByteArray>> {
        // ZIP is read without the native library; libarchive is for RAR and 7z (and any ZIP the JVM cannot read)
        if (BgSubLogic.kind(data) == BgSubLogic.Kind.ZIP) BgSubLogic.zipSubtitles(data).let { if (it.isNotEmpty()) return it }
        return libarchive(data)
    }

    private fun libarchive(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var a = 0L
        try {
            a = Archive.readNew()
            Archive.readSupportFilterAll(a)
            Archive.readSupportFormatAll(a)
            // The buffer must stay reachable until the archive is closed
            val src = ByteBuffer.allocateDirect(data.size)
            src.put(data); src.flip()
            Archive.readOpenMemory(a, src)
            val buf = ByteBuffer.allocateDirect(64 * 1024)
            var seen = 0
            while (seen++ < MAX_ENTRIES && out.size < MAX_FILES) {
                val e = Archive.readNextHeader(a)
                if (e == 0L) break
                val name = ArchiveEntry.pathnameUtf8(e)
                    ?: ArchiveEntry.pathname(e)?.let { BgSubLogic.decode(it, true) } ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in EXT || ArchiveEntry.isEncrypted(e)) continue
                if (ext == "idx") { out.add(name to ByteArray(0)); continue }
                val bytes = ByteArrayOutputStream()
                var tooBig = false
                while (true) {
                    buf.clear()
                    Archive.readData(a, buf)
                    buf.flip()
                    if (!buf.hasRemaining()) break
                    val chunk = ByteArray(buf.remaining())
                    buf.get(chunk)
                    bytes.write(chunk)
                    if (bytes.size() > MAX_BYTES) { tooBig = true; break }
                }
                if (!tooBig && bytes.size() > 0) out.add(name to bytes.toByteArray())
            }
        } catch (e: Exception) {
            // Broken or unsupported archive: what was read so far counts
        } catch (e: LinkageError) {
            // Native library missing for this CPU type
        } finally {
            if (a != 0L) runCatching { Archive.readFree(a) }
        }
        return out
    }
}
