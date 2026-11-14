package org.oregonlandexplorer

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.geojson.Feature
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ParcelDatabaseTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext

    @Test fun exactSelectionHandlesHolesMultipolygonsAndSharedEdges() {
        val feature=Feature.fromJson("""{"type":"Feature","properties":{},"geometry":{"type":"MultiPolygon","coordinates":[[[[0,0],[4,0],[4,4],[0,4],[0,0]],[[1,1],[1,2],[2,2],[2,1],[1,1]]],[[[6,0],[7,0],[7,1],[6,1],[6,0]]]]}}""")
        assertTrue(ParcelDatabase.contains(feature,0.5,0.5))
        assertFalse("Hole must never select its surrounding parcel",ParcelDatabase.contains(feature,1.5,1.5))
        assertTrue("Disjoint part must retain the same owner",ParcelDatabase.contains(feature,0.5,6.5))
        assertFalse(ParcelDatabase.contains(feature,0.5,5.0))
        assertTrue("Exact shared edge may offer both real parcels",ParcelDatabase.contains(feature,0.5,0.0))
    }

    @Test fun realSchema3IndexAndOwnerLookupSurviveRepeatedImports() {
        val store=PackageStore(context);store.installBundled()
        repeat(3) { store.importPackage(instrumentation.context.assets.open("test-packs/silver-falls.olepack")) }
        val installed=Region(context)
        val pack=installed.parcelPackages.first { it.id=="silver-falls" }
        assertTrue(pack.tiled)
        ParcelDatabase.validate(pack.parcelFile,pack.manifest,true)
        val test=pack.manifest.getJSONObject("selection_test")
        val expected=ParcelDatabase.lookup(pack,"41047:${test.getLong("id")}")!!
        repeat(50) {
            val matches=installed.select(test.getDouble("latitude"),test.getDouble("longitude"))
            assertEquals("A tile seam or overlapping pack must not duplicate a popup",matches.size,matches.map { it.getStringProperty("parcel_key") }.toSet().size)
            val actual=matches.first { it.getStringProperty("parcel_key")==expected.getStringProperty("parcel_key") }
            assertEquals(expected.getStringProperty("OWNERNAME"),actual.getStringProperty("OWNERNAME"))
            assertEquals(expected.getStringProperty("TAXLOT"),actual.getStringProperty("TAXLOT"))
        }
    }

    @Test fun rejectCorruptOptimizedDatabaseWithoutReplacingInstalledVersion() {
        val store=PackageStore(context)
        store.importPackage(instrumentation.context.assets.open("test-packs/silver-falls.olepack"))
        val before=store.installed().first { it.id=="silver-falls" }.directory
        val payload=linkedMapOf<String,ByteArray>()
        ZipInputStream(instrumentation.context.assets.open("test-packs/silver-falls.olepack")).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break;payload[entry.name]=zip.readBytes() }
        }
        val m=JSONObject(String(payload.getValue("manifest.json")))
        m.put("revision",m.getInt("revision")+1)
        payload["parcels.sqlite"]="not a SQLite database".toByteArray()
        m.getJSONObject("sha256").put("parcels.sqlite",java.security.MessageDigest.getInstance("SHA-256").digest(payload.getValue("parcels.sqlite")).joinToString("") { "%02x".format(it) })
        payload["manifest.json"]=m.toString().toByteArray()
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for((name,data) in payload) { z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry() } }
        try { store.importPackage(ByteArrayInputStream(out.toByteArray()));fail("Corrupt database accepted with recomputed checksum") } catch(expected: Exception) { }
        assertEquals(before,store.installed().first { it.id=="silver-falls" }.directory)
    }

    private fun legacy(id: String, revision: Int, date: String, west: Double, owner: String): ByteArray {
        val geom="""{"type":"Polygon","coordinates":[[[$west,45.01],[${west+0.01},45.01],[${west+0.01},45.02],[$west,45.02],[$west,45.01]]]}"""
        val data="""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"OBJECTID":987654321,"OWNERNAME":"$owner","TAXLOT":"Synthetic"},"geometry":$geom}]}""".toByteArray()
        val payload=mapOf("parcels.geojson" to data,"source-record-hashes.json" to "{}".toByteArray(),"source-metadata.json" to "{}".toByteArray())
        val hashes=JSONObject()
        for((name,bytes) in payload) hashes.put(name,java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val m=JSONObject().put("schema_version",2).put("kind","parcels").put("id",id).put("name","Synthetic selection test")
            .put("revision",revision).put("extracted_at",date).put("bbox",org.json.JSONArray("[-123.05,45.0,-123.0,45.05]"))
            .put("attribution","Synthetic test only").put("parcel_count",1).put("sha256",hashes)
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for((name,bytes) in payload+mapOf("manifest.json" to m.toString().toByteArray())) { z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun overlapUsesNewestExactGeometryAndRollbackRetainsUpdateWatermark() {
        val store=PackageStore(context);store.installBundled()
        val old="synthetic-parcel-old";val newer="synthetic-parcel-new"
        try {
            store.importPackage(ByteArrayInputStream(legacy(old,1,"2020-01-01T00:00:00Z",-123.04,"Synthetic old owner")))
            store.importPackage(ByteArrayInputStream(legacy(newer,1,"2030-01-01T00:00:00Z",-123.02,"Synthetic new owner")))
            val region=Region(context)
            assertFalse("Moved newer record must suppress stale geometry",region.select(45.015,-123.035).any { it.getStringProperty("parcel_key")=="41047:987654321" })
            val hits=region.select(45.015,-123.015).filter { it.getStringProperty("parcel_key")=="41047:987654321" }
            assertEquals(1,hits.size);assertEquals("Synthetic new owner",hits.single().getStringProperty("OWNERNAME"))
            store.importPackage(ByteArrayInputStream(legacy(newer,2,"2031-01-01T00:00:00Z",-123.02,"Synthetic newer owner")))
            store.rollback(newer)
            for(bytes in listOf(legacy(newer,2,"2031-01-01T00:00:00Z",-123.02,"Synthetic changed"),legacy(newer,3,"2029-01-01T00:00:00Z",-123.02,"Synthetic stale"))) {
                try { store.importPackage(ByteArrayInputStream(bytes));fail("Stale update accepted after rollback") } catch(expected: IllegalArgumentException) { }
            }
            assertEquals(1,store.installed().first { it.id==newer }.manifest.getInt("revision"))
        } finally {
            for(id in listOf(old,newer)) if(store.installed().any { it.id==id }) store.remove(id)
        }
    }
}
