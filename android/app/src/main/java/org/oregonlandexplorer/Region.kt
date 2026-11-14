package org.oregonlandexplorer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/** Continuous base coverage and independent parcel coverage are intentionally different. */
class Region(private val context: Context) {
    val store=PackageStore(context)
    val packages: List<MapPackage>
    val manifest: JSONObject
    val parcels: FeatureCollection
    private val base: MapPackage
    private val parcelPackages: List<MapPackage>
    init {
        store.installBundled()
        packages=store.installed()
        val active=packages.filter { it.enabled }
        base=active.firstOrNull { it.kind=="vector" } ?: error("Enable an installed vector basemap")
        parcelPackages=active.filter { it.kind in listOf("parcels","legacy") }
        val unique=LinkedHashMap<Long,Feature>()
        for(pack in parcelPackages.sortedBy { it.manifest.optString("extracted_at") }) {
            for(feature in pack.parcels().features() ?: emptyList()) unique[feature.getNumberProperty("OBJECTID").toLong()]=feature
        }
        parcels=FeatureCollection.fromFeatures(unique.values.toList())
        manifest=JSONObject(base.manifest.toString()).put("parcel_count",unique.size)
        parcelPackages.firstOrNull()?.manifest?.optJSONObject("selection_test")?.let { manifest.put("selection_test",it) }
    }
    fun contains(latitude: Double,longitude: Double)=base.contains(latitude,longitude)
    fun containsParcel(latitude: Double,longitude: Double)=parcelPackages.any { it.contains(latitude,longitude) }
    fun containsAerial(latitude: Double,longitude: Double)=packages.any { it.enabled && it.kind in listOf("aerial","legacy") && it.contains(latitude,longitude) }
    fun style(aerial: Boolean=false): String {
        val style=JSONObject(context.assets.open("outdoor-style.json").bufferedReader().use { it.readText() })
        style.getJSONObject("sources").getJSONObject("outdoors").put("url",base.url("basemap.pmtiles"))
        if(aerial) {
            val layers=style.getJSONArray("layers")
            for(pack in packages.filter { it.enabled && it.kind in listOf("aerial","legacy") }) {
                val source="aerial-${pack.id}"
                style.getJSONObject("sources").put(source,JSONObject().put("type","raster").put("tileSize",256).put("url",pack.url("aerial.pmtiles")))
                layers.put(JSONObject().put("id",source).put("type","raster").put("source",source).put("paint",JSONObject().put("raster-fade-duration",0)))
            }
        }
        return style.toString()
    }
}
