package org.example

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleFilterCatalogTest {

    private fun loadShippedCatalog(): BleFilterCatalog {
        val candidate = listOf(
            File("src/main/assets/ble_filter_catalog.json"),
            File("app/src/main/assets/ble_filter_catalog.json")
        ).firstOrNull { it.exists() }
            ?: error("Could not locate ble_filter_catalog.json asset")
        return BleFilterCatalog.fromJson(candidate.readText())
    }

    @Test
    fun testShippedCatalogIntegrityAndCoverage() {
        val catalog = loadShippedCatalog()

        assertTrue(catalog.companyEntries.size >= 65, "Expected >= 65 SIG manufacturers, got ${catalog.companyEntries.size}")
        assertTrue(catalog.serviceEntries.size >= 15, "Expected >= 15 service UUIDs, got ${catalog.serviceEntries.size}")

        val uniqueCompanyIds = catalog.companyEntries.map { it.companyId }.toSet()
        assertEquals(catalog.companyEntries.size, uniqueCompanyIds.size, "Duplicate company IDs found")

        val uniqueServiceUuids = catalog.serviceEntries.map { it.fullUuidString.uppercase() }.toSet()
        assertEquals(catalog.serviceEntries.size, uniqueServiceUuids.size, "Duplicate service UUIDs found")

        assertEquals(16, catalog.coreSpecs.size, "Core fallback should target 16 filters")
        assertTrue(catalog.allSpecs.any { it is BleFilterSpec.ServiceData }, "Fast Pair/SmartTag need AD 0x16 ServiceData filters")

        val batches = catalog.batchedSpecs(6)
        assertTrue(batches.all { it.size <= 6 })
        assertEquals(catalog.allSpecs.size, batches.sumOf { it.size })
    }

    @Test
    fun testClassificationPriority() {
        val catalog = loadShippedCatalog()

        assertEquals(
            BleDeviceCategory.PHONE_ECOSYSTEM,
            catalog.classify(listOf(0x038F), emptyList()),
            "Xiaomi 0x038F should classify as PHONE_ECOSYSTEM"
        )
        assertEquals(
            BleDeviceCategory.AUDIO_WEARABLE,
            catalog.classify(listOf(0x0059), listOf(BleFilterCatalog.to128BitBluetoothUuid("FE2C"))),
            "Generic Nordic SoC advertising Google Fast Pair (FE2C) should classify as AUDIO_WEARABLE"
        )
        assertEquals(
            BleDeviceCategory.TRACKER_BEACON,
            catalog.classify(emptyList(), listOf(BleFilterCatalog.to128BitBluetoothUuid("FD5A"))),
            "Samsung SmartTag FD5A should classify as TRACKER_BEACON"
        )
        assertEquals(
            BleDeviceCategory.CHIPSET_IOT,
            catalog.classify(listOf(0x0059), emptyList()),
            "Standalone Nordic 0x0059 should classify as CHIPSET_IOT"
        )
        assertNull(catalog.classify(listOf(0xFFFF), emptyList()))
    }
}
