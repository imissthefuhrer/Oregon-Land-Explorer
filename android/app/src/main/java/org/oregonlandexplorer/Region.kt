package org.oregonlandexplorer

import android.content.Context
import org.json.JSONObject
import org.maplibre.geojson.FeatureCollection
import java.io.File
import java.security.MessageDigest

/** Versioned, independently replaceable data. No network or MapLibre offline-cache dependency. */
class Region(context: Context) {
    val directory = File(context.filesDir, "bundled-region-v1")
    val manifest: JSONObject
    val parcels: FeatureCollection
    init {
        directory.mkdirs()
        // Replace changed bundled assets after an APK update, checking every digest before use.
        val raw = context.assets.open("region/manifest.json").bufferedReader().use { it.readText() }
        manifest = JSONObject(raw)
        require(manifest.getInt("schema_version") == 1)
        val hashes = manifest.getJSONObject("sha256")
        for (name in hashes.keys()) {
            require(File(name).name == name)
            val destination = File(directory, name)
            val expected = hashes.getString(name)
            if (!destination.exists() || sha(destination) != expected) {
                val temporary = File(directory, "$name.tmp")
                context.assets.open("region/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
                require(sha(temporary) == expected) { "Damaged map package: $name" }
                check(temporary.renameTo(destination)) { "Cannot install map package" }
            }
        }
        parcels = FeatureCollection.fromJson(File(directory, "parcels.geojson").readText())
        require(parcels.features()?.size == manifest.getInt("parcel_count"))
    }
    fun contains(latitude: Double, longitude: Double): Boolean {
        val b = manifest.getJSONArray("bbox")
        return longitude in b.getDouble(0)..b.getDouble(2) && latitude in b.getDouble(1)..b.getDouble(3)
    }
    fun style(): String = JSONObject().apply {
        put("version", 8)
        put("sources", JSONObject().put("aerial", JSONObject()
            .put("type", "raster").put("tileSize", 256)
            .put("url", "pmtiles://file://${File(directory, "aerial.pmtiles").absolutePath}")))
        put("layers", org.json.JSONArray().put(JSONObject().put("id", "background").put("type", "background")
            .put("paint", JSONObject().put("background-color", "#26382e")))
            .put(JSONObject().put("id", "aerial").put("type", "raster").put("source", "aerial")))
    }.toString()
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
