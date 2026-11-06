package org.example

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class POI(
    val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double
)

object CoordinateConverter {
    /**
     * Converts UTM Zone 35N coordinates (WGS84 ellipsoid) to latitude and longitude in degrees.
     */
    fun utm35NToLatLon(easting: Double, northing: Double): Pair<Double, Double> {
        val a = 6378137.0
        val f = 1.0 / 298.257223563
        val b = a * (1.0 - f)
        val e = Math.sqrt(1.0 - (b / a) * (b / a))
        val e1 = (1.0 - Math.sqrt(1.0 - e * e)) / (1.0 + Math.sqrt(1.0 - e * e))

        val k0 = 0.9996
        val x = easting - 500000.0
        val y = northing

        val m = y / k0
        val mu = m / (a * (1.0 - e * e / 4.0 - 3.0 * Math.pow(e, 4.0) / 64.0 - 5.0 * Math.pow(e, 6.0) / 256.0))

        val phi1 = mu + (3.0 * e1 / 2.0 - 27.0 * Math.pow(e1, 3.0) / 32.0) * Math.sin(2.0 * mu) +
                (21.0 * e1 * e1 / 16.0 - 55.0 * Math.pow(e1, 4.0) / 32.0) * Math.sin(4.0 * mu) +
                (151.0 * Math.pow(e1, 3.0) / 96.0) * Math.sin(6.0 * mu)

        val sinPhi1 = Math.sin(phi1)
        val cosPhi1 = Math.cos(phi1)
        val tanPhi1 = Math.tan(phi1)

        val n1 = a / Math.sqrt(1.0 - e * e * sinPhi1 * sinPhi1)
        val t1 = tanPhi1 * tanPhi1
        val c1 = (e * e / (1.0 - e * e)) * cosPhi1 * cosPhi1
        val r1 = a * (1.0 - e * e) / Math.pow(1.0 - e * e * sinPhi1 * sinPhi1, 1.5)
        val d = x / (n1 * k0)

        val lat = phi1 - (n1 * tanPhi1 / r1) * (
                d * d / 2.0 -
                (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * (e * e / (1.0 - e * e))) * Math.pow(d, 4.0) / 24.0 +
                (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * (e * e / (1.0 - e * e)) - 3.0 * c1 * c1) * Math.pow(d, 6.0) / 720.0
        )

        val lon0 = (35 - 1) * 6 - 180 + 3 // Zone 35 central meridian = 27 degrees
        val lon = Math.toRadians(lon0.toDouble()) + (
                d -
                (1.0 + 2.0 * t1 + c1) * Math.pow(d, 3.0) / 6.0 +
                (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * (e * e / (1.0 - e * e)) + 24.0 * t1 * t1) * Math.pow(d, 5.0) / 120.0
        ) / cosPhi1

        return Pair(Math.toDegrees(lat), Math.toDegrees(lon))
    }
}

object PoiRepository {
    private var cachedPois: List<POI>? = null

    /**
     * Loads POI features directly from `poi-vertices.geojson` located in assets.
     * Converts EPSG:32635 UTM coordinates to WGS84 lat/lon.
     */
    fun loadPois(context: Context): List<POI> {
        cachedPois?.let { return it }

        val pois = mutableListOf<POI>()
        try {
            val jsonString = context.assets.open("poi-vertices.geojson").use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
            val root = JSONObject(jsonString)
            val features = root.getJSONArray("features")

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val properties = feature.getJSONObject("properties")
                val geometry = feature.getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")

                val id = properties.getInt("id")
                val distance = properties.optDouble("distance", 80.0)
                val name = properties.optString("name", when (id) {
                    1 -> "amphitheatre"
                    2 -> "lake_memorial"
                    3 -> "tosun_terzioglu_garden"
                    else -> "poi_$id"
                })

                val easting = coordinates.getDouble(0)
                val northing = coordinates.getDouble(1)

                val (lat, lon) = CoordinateConverter.utm35NToLatLon(easting, northing)
                pois.add(POI(id = id, name = name, lat = lat, lon = lon, radiusMeters = distance))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback in case asset reading fails
            pois.clear()
            pois.addAll(listOf(
                POI(1, "amphitheatre", 40.89320979780269, 29.379169091952324, 80.0),
                POI(2, "lake_memorial", 40.892876775980646, 29.375505980922984, 60.0),
                POI(3, "tosun_terzioglu_garden", 40.890581851850961, 29.377186219901855, 80.0)
            ))
        }

        cachedPois = pois
        return pois
    }
}
