package dev.nicotv.core
import org.junit.Assert.*
import org.junit.Test
class StationCatalogTest {
 @Test fun officialIdsAreUnique() { assertEquals(10, StationCatalog.stations.size); assertEquals(10, StationCatalog.stations.map { it.id }.toSet().size); assertEquals(10, StationCatalog.stations.map { it.nicoChannelId }.toSet().size) }
 @Test fun unknownDoesNotGuess() { assertNull(StationCatalog.find("4")); assertNull(StationCatalog.find(null)) }
 @Test fun commercialMapping() { assertEquals("ch2646438", StationCatalog.find("jk4")?.nicoChannelId); assertEquals("ch2646440", StationCatalog.find("jk6")?.nicoChannelId); assertEquals("ch2646442", StationCatalog.find("jk8")?.nicoChannelId) }
}
