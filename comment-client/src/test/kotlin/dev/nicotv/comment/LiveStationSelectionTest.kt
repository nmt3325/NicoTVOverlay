package dev.nicotv.comment
import org.junit.Assert.*
import org.junit.Test
class LiveStationSelectionTest {
    @Test fun defaultRemainsJk4AndExplicitCatalogStationWorks() {
        assertEquals("jk4", liveSmokeStation(null).id)
        assertEquals("jk211", liveSmokeStation("jk211").id)
    }
    @Test fun arbitraryUrlsUnknownAndBlankValuesAreRejected() {
        for (value in listOf("https://live.nicovideo.jp/watch/ch2646438", "wss://evil.example/", "jk999", "", " ")) {
            assertThrows(IllegalArgumentException::class.java) { liveSmokeStation(value) }
        }
    }
}
