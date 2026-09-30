package dev.lynx.android.plugins.maps

import java.io.File
import java.security.MessageDigest

/** Pure archive checks shared by downloads and JVM tests. */
internal object MapArchive {
    fun validHeader(file: File): Boolean {
        if (!file.isFile || file.length() < 127) return false
        return file.inputStream().use { input ->
            val magic = ByteArray(8)
            java.io.DataInputStream(input).readFully(magic)
            String(magic, 0, 7, Charsets.US_ASCII) == "PMTiles" && magic[7].toInt() == 3
        }
    }
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun verified(file: File, expected: String): Boolean = validHeader(file) && digest(file) == expected
    fun resumeOffset(responseCode: Int, contentRange: String?, requestedOffset: Long): Long {
        require(responseCode == 200 || responseCode == 206) { "DOWNLOAD_FAILED: HTTP $responseCode" }
        if (responseCode == 200) return 0
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(contentRange ?: "")
        require(match != null) { "DOWNLOAD_FAILED: Invalid Content-Range." }
        val start = match.groupValues[1].toLongOrNull()
        val end = match.groupValues[2].toLongOrNull()
        val total = match.groupValues[3].toLongOrNull()
        require(start == requestedOffset && end != null && end >= requestedOffset && (match.groupValues[3] == "*" || total != null && total > end)) { "DOWNLOAD_FAILED: Invalid Content-Range." }
        return requestedOffset
    }
    fun rangeTotal(contentRange: String?): Long? = contentRange?.substringAfterLast('/')?.toLongOrNull()
}
