package dev.lynx.android.plugins.maps

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MapArchiveTest {
    private fun archive(version: Int = 3): File = File.createTempFile("map-archive-test-", ".pmtiles").apply {
        deleteOnExit()
        writeBytes(ByteArray(160).apply { "PMTiles".toByteArray(Charsets.US_ASCII).copyInto(this); this[7] = version.toByte() })
    }
    @Test fun verifiesCorrectHeaderAndChecksum() { val file = archive(); assertTrue(MapArchive.verified(file, MapArchive.digest(file))) }
    @Test fun rejectsCorruptedArchiveAndPreservesOriginal() {
        val original = archive(); val expected = MapArchive.digest(original)
        val corrupt = archive().apply { appendText("changed") }
        assertFalse(MapArchive.verified(corrupt, expected)); assertTrue(MapArchive.verified(original, expected))
    }
    @Test fun rejectsUnsupportedVersion() { assertFalse(MapArchive.validHeader(archive(2))) }
    @Test fun rejectsTruncatedHeader() { val file = archive().apply { writeText("PMTiles") }; assertFalse(MapArchive.validHeader(file)) }
    @Test fun restartsWhenServerIgnoresRange() { assertEquals(0L, MapArchive.resumeOffset(200, null, 100)) }
    @Test fun resumesOnlyMatchingRange() {
        assertEquals(100L, MapArchive.resumeOffset(206, "bytes 100-199/200", 100))
        assertEquals(200L, MapArchive.rangeTotal("bytes 100-199/200"))
        assertThrows(IllegalArgumentException::class.java) { MapArchive.resumeOffset(206, "bytes 0-99/200", 100) }
    }
    @Test fun rejectsInvalidRangeAndServerFailure() {
        assertThrows(IllegalArgumentException::class.java) { MapArchive.resumeOffset(206, "bytes 100-200/200", 100) }
        assertThrows(IllegalArgumentException::class.java) { MapArchive.resumeOffset(206, null, 100) }
        assertThrows(IllegalArgumentException::class.java) { MapArchive.resumeOffset(500, null, 0) }
    }
}
