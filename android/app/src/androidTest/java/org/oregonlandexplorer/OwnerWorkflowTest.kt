package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** All synthetic names are visibly labelled; no county owner harvest is involved. */
class OwnerWorkflowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val assets=instrumentation.context.assets
    private val device=UiDevice.getInstance(instrumentation)
    private fun ready(s: ActivityScenario<MainActivity>) {
        val end=SystemClock.elapsedRealtime()+120000;var loaded=false
        while(!loaded && SystemClock.elapsedRealtime()<end) { s.onActivity { loaded=it.ready };Thread.sleep(100) }
        assertTrue("Offline map not ready",loaded)
    }
    private fun memory(): JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        return JSONObject().put("pss_kib",info.totalPss).put("native_alloc_bytes",Debug.getNativeHeapAllocatedSize())
            .put("java_alloc_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("file_descriptors",java.io.File("/proc/self/fd").list()?.size ?: -1)
    }
    private fun save(name: String,value: String) {
        val v=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ole-evidence")
        }
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)!!
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(value) }
    }
    @Test fun prepareV04InstalledData() {
        val store=PackageStore(context);store.installBundled()
        for(name in assets.list("county-packs")!!.filter { it.endsWith(".olepack") }.sorted()) store.importPackage(assets.open("county-packs/$name"))
        val packs=store.installed().filter { it.kind=="parcels" && it.manifest.optString("source_namespace")=="marion-open" }
        assertEquals(115870,packs.sumOf { it.manifest.getInt("parcel_count") })
        // Immutable file hashes and enabled state must survive the APK update.
        context.getSharedPreferences("owner-upgrade-test",0).edit()
            .putString("base_hashes",JSONObject().also { o -> for(p in packs) o.put(p.id,p.manifest.getJSONObject("sha256")) }.toString())
            .putBoolean("sentinel",true).commit()
        val report=JSONObject().put("scenario","v0.4.0 same installed county without ownership overlay")
        val samples=JSONArray();report.put("navigation_memory",samples)
        val catalog=JSONObject(assets.open("owner-packs/catalog.json").bufferedReader().use { it.readText() })
        val targets=catalog.getJSONArray("selection_targets")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario)
            repeat(600) { i ->
                val target=targets.getJSONObject((i/4)%targets.length())
                scenario.onActivity { a ->
                    val lat=target.getDouble("latitude");val lon=target.getDouble("longitude")
                    a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat+(i%3-1)*.001,lon+(i%5-2)*.002),doubleArrayOf(12.0,15.0,17.0,18.0)[i%4]))
                    if(i%12==0) assertTrue(a.region!!.select(lat,lon).any { it.getStringProperty("parcel_key").startsWith("41047:marion-open:") })
                }
                Thread.sleep(300)
                if(i%12==11) samples.put(memory())
            }
            report.put("after_navigation",memory()).put("pan_zoom_operations",600)
        }
        save("owner-v04-before-update-memory.json",report.toString(2))
    }
    @Test fun countyOwnershipOfflineUpdateRollbackAndNavigation() {
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
        assertEquals("1",device.executeShellCommand("settings get global airplane_mode_on").trim())
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        val store=PackageStore(context)
        val prefs=context.getSharedPreferences("owner-upgrade-test",0)
        assertTrue("APK update lost settings",prefs.getBoolean("sentinel",false))
        val hashes=JSONObject(prefs.getString("base_hashes",null)!!)
        val county=store.installed().filter { it.kind=="parcels" && it.manifest.optString("source_namespace")=="marion-open" }
        assertEquals(115870,county.sumOf { it.manifest.getInt("parcel_count") })
        for(p in county) assertEquals(hashes.getJSONObject(p.id).toString(),p.manifest.getJSONObject("sha256").toString())
        for(p in store.installed()) if(p.kind in listOf("parcels","legacy")) store.setEnabled(p.id,p.id.startsWith("marion-"))
        val importStart=SystemClock.elapsedRealtime()
        store.importPackage(assets.open("owner-packs/owners-v1.olepack"))
        val report=JSONObject().put("import_ms",SystemClock.elapsedRealtime()-importStart).put("data","115870 visibly synthetic names on unchanged real county polygons")
            .put("device","Hosted API35 Pixel2 x86_64 Mesa emulator; not physical phone performance")
        val ownerPack=store.installed().single { it.kind=="owners" }
        assertEquals(115870,ownerPack.manifest.getInt("owner_count"))
        report.put("owner_payload_bytes",ownerPack.payloadBytes)
        val catalog=JSONObject(assets.open("owner-packs/catalog.json").bufferedReader().use { it.readText() })
        val targets=catalog.getJSONArray("selection_targets")
        val selections=JSONArray();report.put("selections",selections)
        val navigation=JSONArray();report.put("navigation_memory",navigation)
        val start=SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(scenario);report.put("ready_ms",SystemClock.elapsedRealtime()-start)
            // Every geographic parcel package supplies a real interior test point.
            val installedRegion=Region(context)
            for(pack in county) {
                val point=pack.manifest.getJSONObject("selection_test")
                val hits=installedRegion.select(point.getDouble("latitude"),point.getDouble("longitude"))
                assertTrue("No validated owner in ${pack.id}",hits.any { it.getStringProperty("owner_match_status")=="matched" })
            }
            for(i in 0 until targets.length()) {
                val target=targets.getJSONObject(i)
                val point=LatLng(target.getDouble("latitude"),target.getDouble("longitude"))
                val frame=CountDownLatch(1);val moved=SystemClock.elapsedRealtime()
                scenario.onActivity { a ->
                    a.mapView.addOnDidFinishRenderingFrameListener { fully,_,_-> if(fully) frame.countDown() }
                    a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,17.0))
                }
                assertTrue("No rendered frame",frame.await(45,TimeUnit.SECONDS));Thread.sleep(1000)
                val lookup=SystemClock.elapsedRealtime()
                scenario.onActivity { a ->
                    val hits=a.region!!.select(point.latitude,point.longitude)
                    val f=hits.single { it.getStringProperty("parcel_key")==target.getString("parcel_key") }
                    val expected="SYNTHETIC TEST ONLY "+f.getStringProperty("TAXLOT")+" / "+f.getStringProperty("TAXACCT")
                    assertEquals(expected,f.getStringProperty("OWNERNAME"));assertEquals("matched",f.getStringProperty("owner_match_status"))
                    // Even deliberately unrelated OIDs cannot influence the owner join.
                    f.addNumberProperty("OBJECTID",999999999);f.removeProperty("OWNERNAME")
                    OwnerDatabase.enrich(f,a.region!!.ownerPackages);assertEquals(expected,f.getStringProperty("OWNERNAME"))
                    f.addStringProperty("owner_geometry_binding","0".repeat(64));OwnerDatabase.enrich(f,a.region!!.ownerPackages)
                    assertFalse(f.hasProperty("OWNERNAME"));assertEquals("geometry_changed",f.getStringProperty("owner_match_status"))
                    a.selectParcel(point)
                }
                if(device.wait(Until.hasObject(By.text("Overlapping parcels")),2000)) {
                    androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).atPosition(0).perform(androidx.test.espresso.action.ViewActions.click())
                }
                assertTrue("Owner popup missing",device.wait(Until.hasObject(By.text("Property details")),10000))
                assertTrue(device.hasObject(By.textContains("SYNTHETIC TEST ONLY")))
                selections.put(JSONObject().put("place",target.getString("name")).put("lookup_and_popup_ms",SystemClock.elapsedRealtime()-lookup).put("frame_ms",lookup-moved))
                device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/owner-${target.getString("name").replace(' ','-')}.png")
                device.pressBack()
            }
            val navigationStart=SystemClock.elapsedRealtime()
            repeat(600) { i ->
                val target=targets.getJSONObject((i/4)%targets.length())
                scenario.onActivity { a ->
                    val lat=target.getDouble("latitude");val lon=target.getDouble("longitude")
                    a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat+(i%3-1)*.001,lon+(i%5-2)*.002),doubleArrayOf(12.0,15.0,17.0,18.0)[i%4]))
                    if(i%12==0) assertTrue(a.region!!.select(lat,lon).any { it.getStringProperty("owner_match_status")=="matched" })
                }
                Thread.sleep(300)
                if(i%12==11) navigation.put(memory())
            }
            report.put("pan_zoom_operations",600).put("navigation_ms",SystemClock.elapsedRealtime()-navigationStart)
            scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);ready(scenario)
            scenario.recreate();ready(scenario)
        }
        val point=targets.getJSONObject(0)
        fun selected()=Region(context).select(point.getDouble("latitude"),point.getDouble("longitude")).single { it.getStringProperty("parcel_key")==point.getString("parcel_key") }
        store.setEnabled(ownerPack.id,false);assertFalse(selected().hasProperty("OWNERNAME"))
        store.setEnabled(ownerPack.id,true);assertTrue(selected().hasProperty("OWNERNAME"))
        store.importPackage(assets.open("owner-packs/owners-v2.olepack"))
        assertEquals("null",selected().getStringProperty("owner_match_status"));assertFalse(selected().hasProperty("OWNERNAME"))
        store.rollback(ownerPack.id);assertTrue(selected().hasProperty("OWNERNAME"))
        try {store.importPackage(assets.open("owner-packs/owners-v2.olepack"));fail("Update watermark lost after rollback") } catch(expected: IllegalArgumentException) { }
        // Corruption with a recomputed checksum must still fail deep SQLite validation.
        val payload=linkedMapOf<String,ByteArray>()
        ZipInputStream(assets.open("owner-packs/owners-v2.olepack")).use { zip ->
            while(true) {val entry=zip.nextEntry ?: break
                if(entry.name!="owners.sqlite") payload[entry.name]=zip.readBytes()
            }
        }
        payload["owners.sqlite"]="invalid database".toByteArray()
        val m=JSONObject(String(payload.getValue("manifest.json"))).put("revision",3)
        m.getJSONObject("sha256").put("owners.sqlite",java.security.MessageDigest.getInstance("SHA-256").digest(payload.getValue("owners.sqlite")).joinToString("") { "%02x".format(it) })
        payload["manifest.json"]=m.toString().toByteArray()
        val out=ByteArrayOutputStream();ZipOutputStream(out).use { zip -> for((name,bytes) in payload) {zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()} }
        try {store.importPackage(ByteArrayInputStream(out.toByteArray()));fail("Corrupt owners accepted") } catch(expected: Exception) { }
        assertTrue(selected().hasProperty("OWNERNAME"))
        store.remove(ownerPack.id);assertFalse(selected().hasProperty("OWNERNAME"))
        assertEquals(115870,store.installed().filter { it.manifest.optString("source_namespace")=="marion-open" }.sumOf { it.manifest.getInt("parcel_count") })
        report.put("after_navigation",memory()).put("update_rollback_removal","PASS").put("airplane_mode",true)
        save("owner-workflow-performance.json",report.toString(2))
    }
}
