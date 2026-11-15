package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/** Measures actual local database work separately from scripted popup polling. */
class OwnerLookupBenchmarkTest {
    @Test fun measureExactSelectionAndOwnerLookupAcrossCounty() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val assets=inst.context.assets;val device=UiDevice.getInstance(inst)
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        val store=PackageStore(context);store.installBundled()
        for(name in assets.list("county-packs")!!.filter { it.endsWith(".olepack") }.sorted()) store.importPackage(assets.open("county-packs/$name"))
        for(pack in store.installed()) if(pack.kind in listOf("parcels","legacy")) store.setEnabled(pack.id,pack.id.startsWith("marion-"))
        store.importPackage(assets.open("owner-packs/owners-v1.olepack"))
        val catalog=JSONObject(assets.open("owner-packs/catalog.json").bufferedReader().use { it.readText() })
        val targets=catalog.getJSONArray("selection_targets")
        val views=JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline=SystemClock.elapsedRealtime()+120000;var ready=false
            while(!ready && SystemClock.elapsedRealtime()<deadline) { scenario.onActivity { ready=it.ready };Thread.sleep(100) }
            assertTrue(ready)
            lateinit var region: Region
            scenario.onActivity { region=it.region!! }
            assertEquals(115870,region.parcelPackages.sumOf { it.manifest.getInt("parcel_count") })
            for(i in 0 until targets.length()) {
                val target=targets.getJSONObject(i);val lat=target.getDouble("latitude");val lon=target.getDouble("longitude")
                scenario.onActivity { it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat,lon),17.0)) }
                Thread.sleep(1500)
                val selections=JSONArray();val owners=JSONArray()
                repeat(30) {
                    val start=SystemClock.elapsedRealtimeNanos();val hits=region.select(lat,lon)
                    selections.put((SystemClock.elapsedRealtimeNanos()-start)/1000000.0)
                    val selected=hits.single { it.getStringProperty("parcel_key")==target.getString("parcel_key") }
                    val ownerStart=SystemClock.elapsedRealtimeNanos();OwnerDatabase.enrich(selected,region.ownerPackages)
                    owners.put((SystemClock.elapsedRealtimeNanos()-ownerStart)/1000000.0)
                    assertEquals("SYNTHETIC TEST ONLY "+selected.getStringProperty("TAXLOT")+" / "+selected.getStringProperty("TAXACCT"),selected.getStringProperty("OWNERNAME"))
                }
                val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                views.put(JSONObject().put("place",target.getString("name")).put("exact_selection_with_owner_ms",selections)
                    .put("owner_sqlite_lookup_ms",owners).put("active_pss_kib",info.totalPss))
            }
        }
        val report=JSONObject().put("result","PASS").put("test_data",true).put("airplane_mode",true).put("county_parcels",115870)
            .put("samples_per_place",30).put("views",views)
            .put("method","Hosted API35 Pixel2 Mesa emulator. Timers measure local work on instrumentation thread while map is active; assertions, camera waits and popup polling are excluded. Region.select includes spatial candidates, polygon decoding, exact hit testing, geometry hash and ownership. Owner timer includes readonly connection and indexed owner lookup. Includes first lookup; no forced disk-cache flush. Not physical phone performance.")
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"owner-exact-lookup-performance.json")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ole-evidence")
        }
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(report.toString(2)) }
    }
}
