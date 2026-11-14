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
    val parcelPackages: List<MapPackage>
    val previousPackageIds: Set<String>
    private val styleTemplate=context.assets.open("outdoor-style.json").bufferedReader().use { it.readText() }
    val legacyPackages: List<MapPackage> get()=parcelPackages.filter { !it.tiled }
    val parcelLayerIds: List<String> get()=listOf("parcel-fill","parcel-line","selected-line")+parcelPackages.filter { it.tiled }.flatMap { listOf("parcel-fill-${it.id}","parcel-line-${it.id}") }
    private val base: MapPackage
    private val bases: List<MapPackage>
    init {
        store.installBundled()
        packages=store.installed()
        previousPackageIds=packages.filter { store.hasPrevious(it.id) }.map { it.id }.toSet()
        val active=packages.filter { it.enabled }
        bases=active.filter { it.kind=="vector" }
        base=active.firstOrNull { it.kind=="vector" } ?: error("Enable an installed vector basemap")
        parcelPackages=active.filter { it.kind in listOf("parcels","legacy") }
        for(pack in legacyPackages) pack.legacyParcelFile=ParcelDatabase.prepareLegacy(pack,java.io.File(context.filesDir,"parcel-indexes"))
        manifest=JSONObject(base.manifest.toString()).put("parcel_count",parcelPackages.sumOf { it.manifest.getInt("parcel_count") })
        parcelPackages.firstOrNull()?.manifest?.optJSONObject("selection_test")?.let { manifest.put("selection_test",it) }
    }
    /** Resolve identity freshness BEFORE the exact hit test, so moved newer parcels
     * cannot resurrect a stale boundary from an overlapping older region. */
    fun select(latitude: Double, longitude: Double): List<Feature> {
        if(!containsParcel(latitude,longitude)) return emptyList()
        val ordered=parcelPackages.sortedWith(compareByDescending<MapPackage> { java.time.Instant.parse(it.manifest.getString("extracted_at")) }.thenBy { it.id })
        val keys=ordered.filter { it.contains(latitude,longitude) }.flatMap { ParcelDatabase.candidates(it,latitude,longitude) }.toSet()
        require(keys.size<=256) { "Too many overlapping parcel candidates" }
        return keys.mapNotNull { key ->
            val pair=ordered.firstNotNullOfOrNull { pack -> ParcelDatabase.lookup(pack,key)?.let { pack to it } }
            pair?.takeIf { ParcelDatabase.contains(it.second,latitude,longitude) }?.let { (pack,feature) ->
                feature.addStringProperty("source_extracted_at",pack.manifest.getString("extracted_at"))
                feature.addStringProperty("source_package",pack.manifest.getString("name"))
                feature
            }
        }
    }
    fun legacyViewport(bounds: DoubleArray): FeatureCollection {
        val unique=LinkedHashMap<String,Feature>();var bytes=0
        for(pack in legacyPackages.sortedBy { java.time.Instant.parse(it.manifest.getString("extracted_at")) }) {
            for(f in ParcelDatabase.viewport(pack,bounds)) {
                bytes+=f.toJson().length
                require(unique.size<1000 && bytes<=2*1024*1024) { "Legacy parcel view is dense; zoom in or install schema 3 package" }
                unique[f.getStringProperty("parcel_key")]=f
            }
        }
        return FeatureCollection.fromFeatures(unique.values.toList())
    }
    fun contains(latitude: Double,longitude: Double)=bases.any { it.contains(latitude,longitude) }
    fun containsParcel(latitude: Double,longitude: Double)=parcelPackages.any { it.contains(latitude,longitude) }
    fun containsAerial(latitude: Double,longitude: Double)=packages.any { it.enabled && it.kind in listOf("aerial","legacy") && it.contains(latitude,longitude) }
    fun style(aerial: Boolean=false): String {
        val style=JSONObject(styleTemplate)
        style.getJSONObject("sources").getJSONObject("outdoors").put("url",base.url("basemap.pmtiles"))
        // Each additional compatible outdoor archive gets its own local source and layers.
        // Imported packages cannot inject remote styles, URLs, glyphs or executable code.
        val original=JSONArray(style.getJSONArray("layers").toString())
        for((index,pack) in bases.filter { it.id!=base.id }.withIndex()) {
            val source="outdoors-$index"
            style.getJSONObject("sources").put(source,JSONObject().put("type","vector").put("url",pack.url("basemap.pmtiles")))
            for(i in 0 until original.length()) {
                val layer=JSONObject(original.getJSONObject(i).toString())
                if(layer.optString("source")=="outdoors") {
                    layer.put("source",source).put("id",layer.getString("id")+"-$index")
                    style.getJSONArray("layers").put(layer)
                }
            }
        }
        if(aerial) {
            val layers=style.getJSONArray("layers")
            for(pack in packages.filter { it.enabled && it.kind in listOf("aerial","legacy") }) {
                val source="aerial-${pack.id}"
                style.getJSONObject("sources").put(source,JSONObject().put("type","raster").put("tileSize",256).put("url",pack.url("aerial.pmtiles")))
                layers.put(JSONObject().put("id",source).put("type","raster").put("source",source).put("paint",JSONObject().put("raster-fade-duration",0)))
            }
        }
        for(pack in parcelPackages.filter { it.tiled }) {
            val source="parcels-${pack.id}"
            style.getJSONObject("sources").put(source,JSONObject().put("type","vector").put("url",pack.url("parcels.pmtiles")))
            style.getJSONArray("layers").put(JSONObject().put("id","parcel-fill-${pack.id}").put("type","fill").put("source",source).put("source-layer","parcels").put("minzoom",12)
                .put("paint",JSONObject().put("fill-color","#ffb547").put("fill-opacity",0.025)))
            style.getJSONArray("layers").put(JSONObject().put("id","parcel-line-${pack.id}").put("type","line").put("source",source).put("source-layer","boundaries").put("minzoom",12)
                .put("paint",JSONObject().put("line-color","#b27a25").put("line-width",1.1)))
        }
        return style.toString()
    }
}
