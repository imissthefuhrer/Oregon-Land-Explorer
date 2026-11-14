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
}
