package org.example

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

enum class BleDeviceCategory(val key: String) {
    PHONE_ECOSYSTEM("phone_ecosystem"),
    AUDIO_WEARABLE("audio_wearable"),
    TRACKER_BEACON("tracker_beacon"),
    CHIPSET_IOT("chipset_iot"),
    OTHER("other");

    companion object {
        fun fromKey(raw: String): BleDeviceCategory =
            entries.firstOrNull { it.key == raw } ?: OTHER
    }
}

data class CompanyFilterEntry(
    val companyId: Int,
    val name: String,
    val category: BleDeviceCategory,
    val core: Boolean
)

enum class ServiceMatchMode(val key: String) {
    SERVICE_UUID("service_uuid"),
    SERVICE_DATA("service_data"),
    BOTH("both");

    companion object {
        fun fromKey(raw: String): ServiceMatchMode =
            entries.firstOrNull { it.key == raw } ?: SERVICE_UUID
    }
}

data class ServiceUuidFilterEntry(
    val shortHex: String,
    val fullUuidString: String,
    val name: String,
    val category: BleDeviceCategory,
    val core: Boolean,
    val matchMode: ServiceMatchMode = ServiceMatchMode.SERVICE_UUID
)

sealed interface BleFilterSpec {
    val category: BleDeviceCategory
    val core: Boolean

    data class Manufacturer(val entry: CompanyFilterEntry) : BleFilterSpec {
        override val category: BleDeviceCategory get() = entry.category
        override val core: Boolean get() = entry.core
    }

    data class ServiceUuid(val entry: ServiceUuidFilterEntry) : BleFilterSpec {
        override val category: BleDeviceCategory get() = entry.category
        override val core: Boolean get() = entry.core
    }

    data class ServiceData(val entry: ServiceUuidFilterEntry) : BleFilterSpec {
        override val category: BleDeviceCategory get() = entry.category
        override val core: Boolean get() = entry.core
    }
}

/**
 * Pure-Kotlin loader and classifier for [assets/ble_filter_catalog.json].
 * Kept independent of Android's ScanFilter so it is fully unit-testable on the JVM.
 */
class BleFilterCatalog(
    val companyEntries: List<CompanyFilterEntry>,
    val serviceEntries: List<ServiceUuidFilterEntry>
) {
    private val companyById: Map<Int, CompanyFilterEntry> = companyEntries.associateBy { it.companyId }
    private val serviceByFullUuid: Map<String, ServiceUuidFilterEntry> =
        serviceEntries.associateBy { it.fullUuidString.uppercase() }

    val allSpecs: List<BleFilterSpec> = buildList {
        for (entry in serviceEntries) {
            if (entry.matchMode == ServiceMatchMode.SERVICE_UUID || entry.matchMode == ServiceMatchMode.BOTH) {
                add(BleFilterSpec.ServiceUuid(entry))
            }
            if (entry.matchMode == ServiceMatchMode.SERVICE_DATA || entry.matchMode == ServiceMatchMode.BOTH) {
                add(BleFilterSpec.ServiceData(entry))
            }
        }
        for (entry in companyEntries) {
            add(BleFilterSpec.Manufacturer(entry))
        }
    }

    val coreSpecs: List<BleFilterSpec> = buildList {
        for (entry in serviceEntries.filter { it.core }) {
            // In core-16 fallback mode, emit one primary filter per core service UUID to fit the 16-slot budget
            if (entry.matchMode == ServiceMatchMode.SERVICE_DATA) {
                add(BleFilterSpec.ServiceData(entry))
            } else {
                add(BleFilterSpec.ServiceUuid(entry))
            }
        }
        for (entry in companyEntries.filter { it.core }) {
            add(BleFilterSpec.Manufacturer(entry))
        }
    }

    fun batchedSpecs(batchSize: Int = 6): List<List<BleFilterSpec>> {
        require(batchSize > 0) { "batchSize must be positive" }
        return allSpecs.chunked(batchSize)
    }

    /**
     * Classifies an advertisement into a coarse functional category without reading
     * device names or raw payload bytes. Service UUIDs take priority over generic chipset
     * company IDs because e.g. Fast Pair / LE Audio identifies consumer wearables directly.
     */
    fun classify(companyIds: List<Int>, serviceUuidStrings: List<String>): BleDeviceCategory? {
        val matchedServices = serviceUuidStrings.mapNotNull { serviceByFullUuid[it.uppercase()] }
        val matchedCompanies = companyIds.mapNotNull { companyById[it] }

        if (matchedServices.isEmpty() && matchedCompanies.isEmpty()) return null

        // Prefer specific non-chipset company categories first when known, else service UUID category
        val specificCompany = matchedCompanies.firstOrNull { it.category != BleDeviceCategory.CHIPSET_IOT }
        if (specificCompany != null) return specificCompany.category

        val specificService = matchedServices.firstOrNull { it.category != BleDeviceCategory.CHIPSET_IOT }
        if (specificService != null) return specificService.category

        return matchedCompanies.firstOrNull()?.category ?: matchedServices.firstOrNull()?.category
    }

    companion object {
        private const val ASSET_NAME = "ble_filter_catalog.json"

        fun to128BitBluetoothUuid(shortHex: String): String {
            val cleaned = shortHex.removePrefix("0x").removePrefix("0X").uppercase().padStart(4, '0')
            return "0000$cleaned-0000-1000-8000-00805F9B34FB"
        }

        fun fromJson(jsonText: String): BleFilterCatalog {
            val root = JSONObject(jsonText)
            val companiesJson = root.getJSONArray("company_ids")
            val companies = ArrayList<CompanyFilterEntry>(companiesJson.length())
            for (i in 0 until companiesJson.length()) {
                val obj = companiesJson.getJSONObject(i)
                val idHex = obj.getString("id").removePrefix("0x").removePrefix("0X")
                companies.add(
                    CompanyFilterEntry(
                        companyId = idHex.toInt(16),
                        name = obj.getString("name"),
                        category = BleDeviceCategory.fromKey(obj.getString("category")),
                        core = obj.optBoolean("core", false)
                    )
                )
            }

            val servicesJson = root.getJSONArray("service_uuids")
            val services = ArrayList<ServiceUuidFilterEntry>(servicesJson.length())
            for (i in 0 until servicesJson.length()) {
                val obj = servicesJson.getJSONObject(i)
                val shortHex = obj.getString("uuid").uppercase()
                services.add(
                    ServiceUuidFilterEntry(
                        shortHex = shortHex,
                        fullUuidString = to128BitBluetoothUuid(shortHex),
                        name = obj.getString("name"),
                        category = BleDeviceCategory.fromKey(obj.getString("category")),
                        core = obj.optBoolean("core", false),
                        matchMode = ServiceMatchMode.fromKey(obj.optString("match_mode", "service_uuid"))
                    )
                )
            }

            return BleFilterCatalog(companies, services)
        }

        fun fromAssets(context: Context): BleFilterCatalog {
            val text = context.assets.open(ASSET_NAME).use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
            return fromJson(text)
        }
    }
}
