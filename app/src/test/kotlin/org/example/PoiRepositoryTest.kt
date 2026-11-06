package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoiRepositoryTest {

    @Test
    fun testUtm35NToLatLonConversion() {
        // Amphitheatre coordinates in UTM 35N
        val eastingAmphi = 700422.7070511838
        val northingAmphi = 4529627.432749903

        val (latAmphi, lonAmphi) = CoordinateConverter.utm35NToLatLon(eastingAmphi, northingAmphi)
        assertEquals(40.8932, latAmphi, 0.001)
        assertEquals(29.3791, lonAmphi, 0.001)

        // Lake memorial coordinates in UTM 35N
        val eastingLake = 700115.1027633464
        val northingLake = 4529582.072674975

        val (latLake, lonLake) = CoordinateConverter.utm35NToLatLon(eastingLake, northingLake)
        assertEquals(40.8928, latLake, 0.001)
        assertEquals(29.3755, lonLake, 0.001)

        // Tosun Terzioglu Garden in UTM 35N
        val eastingGarden = 700263.5836827627
        val northingGarden = 4529331.133092464

        val (latGarden, lonGarden) = CoordinateConverter.utm35NToLatLon(eastingGarden, northingGarden)
        assertEquals(40.8905, latGarden, 0.001)
        assertEquals(29.3771, lonGarden, 0.001)
    }

    @Test
    fun testPoiDataConsistency() {
        val amphi = CoordinateConverter.utm35NToLatLon(700422.7070511838, 4529627.432749903)
        val lake = CoordinateConverter.utm35NToLatLon(700115.1027633464, 4529582.072674975)
        val garden = CoordinateConverter.utm35NToLatLon(700263.5836827627, 4529331.133092464)

        assertTrue(amphi.first > garden.first, "Amphitheatre should be north of Garden")
        assertTrue(amphi.second > lake.second, "Amphitheatre should be east of Lake")
    }
}
