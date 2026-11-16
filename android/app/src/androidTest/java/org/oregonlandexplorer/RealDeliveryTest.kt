package org.oregonlandexplorer

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.io.File

/** Executed ONLY in private CI. Real data arrives at runtime, never in this test APK.
 * Assertion messages deliberately exclude owner names and private record values. */
class RealDeliveryTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private val device=UiDevice.getInstance(inst)
    private val inputs=File(context.filesDir,"private-input")
    private val mode=InstrumentationRegistry.getArguments().getString("mode") ?: "missing"
    private val targets by lazy { JSONObject(File(inputs,"fixtures.json").readText()).getJSONArray("selection_targets") }
    @After fun captureSystemUi() {
        device.executeShellCommand("screencap -p /sdcard/Download/private-evidence/$mode-final-ui.png")
        val out=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(out)
        save("$mode-final-ui.xml",out.toString("UTF-8"))
    }
    private fun appClick(text: String) { androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(text)).perform(androidx.test.espresso.action.ViewActions.click()) }
    private fun menuClick(text: String) { androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo(text)).perform(androidx.test.espresso.action.ViewActions.click()) }
    private fun waitReady(s: ActivityScenario<MainActivity>,condition: (MainActivity)->Boolean = { true }) {
        val end=SystemClock.elapsedRealtime()+180000;var ready=false
        while(!ready && SystemClock.elapsedRealtime()<end) {s.onActivity { ready=it.ready && condition(it) };Thread.sleep(200)}
        assertTrue("Map/import did not become ready ($mode)",ready)
    }
    private fun save(name: String,text: String) {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/private-evidence")
        }
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) }
    }
    private fun publish(file: File,name: String): Uri {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,if(name.endsWith(".zip")) "application/zip" else "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download");put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)
        return uri
    }
    private fun clickText(text: String) {
        val config=androidx.test.uiautomator.Configurator.getInstance();val previous=config.waitForIdleTimeout
        config.setWaitForIdleTimeout(0)
        try {
        val end=SystemClock.elapsedRealtime()+20000
        while(SystemClock.elapsedRealtime()<end) {
            try {val node=device.findObject(UiSelector().text(text));if(node.waitForExists(500) && node.click()) return} catch(_: androidx.test.uiautomator.UiObjectNotFoundException) { }
            Thread.sleep(200)
        }
        fail("Required system/app control unavailable")
        } finally {config.setWaitForIdleTimeout(previous)}
    }
    private fun chooseDownload(s: ActivityScenario<MainActivity>,name: String,county: Boolean) {
        appClick("Region");menuClick(if(county) "Import complete county ZIP" else "Import .olepack from Downloads")
        assertTrue("Android Files picker unavailable",device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000) || device.wait(Until.hasObject(By.pkg("com.android.documentsui")),10000))
        if(!device.wait(Until.hasObject(By.text(name)),3000)) {
            device.findObject(By.desc("Show roots"))?.click();clickText("Downloads")
        }
        assertTrue("Completed browser-style download missing from Files",device.wait(Until.hasObject(By.text(name)),10000))
        if(name.endsWith(".zip")) {
            // Select the ZIP as a document, not its browsable archive contents.
            device.findObject(UiSelector().text(name)).longClick()
            val open=device.wait(Until.findObject(By.text("Open")),2000) ?: device.findObject(By.text("OPEN")) ?: device.findObject(By.desc("Open"))
            if(open!=null) open.click() else clickText(name)
        } else clickText(name)
        // Some DocumentsUI releases open ZIP navigation on tap. Select the archive
        // itself if still in the picker rather than substituting a non-ZIP MIME type.
        Thread.sleep(1000)
        if(device.hasObject(By.pkg("com.google.android.documentsui")) || device.hasObject(By.pkg("com.android.documentsui"))) {
            device.pressBack()
            val row=device.findObject(UiSelector().text(name))
            if(row.waitForExists(3000)) {row.longClick();device.findObject(By.text("Open"))?.click();device.findObject(By.text("OPEN"))?.click()}
        }
    }
    private fun expected(lot: String,account: String): String=ParcelDatabase.open(File(inputs,"reference.sqlite")).use { db ->
        db.rawQuery("SELECT owner FROM owners_reference WHERE county=? AND taxlot=? AND taxacct=?",arrayOf("41047",lot,account)).use { c ->
            assertTrue("Independent private input reference missing",c.moveToFirst());c.getString(0)
        }
    }
    private fun popupChecks(s: ActivityScenario<MainActivity>,label: String,unknownDate: Boolean): JSONArray {
        val checks=JSONArray()
        for(i in 0 until targets.length()) {
            val target=targets.getJSONObject(i);val name=target.getString("name")
            val point=LatLng(target.getDouble("latitude"),target.getDouble("longitude"))
            s.onActivity { it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,17.0)) };Thread.sleep(2200)
            var index=0;var expectedOwner="";var x=0;var y=0
            val begin=SystemClock.elapsedRealtime()
            s.onActivity { a ->
                val hits=a.region!!.select(point.latitude,point.longitude)
                index=hits.indexOfFirst { it.getStringProperty("parcel_key")==target.getString("parcel_key") }
                assertTrue("Exact source parcel not selectable at $name",index>=0)
                val feature=hits[index]
                expectedOwner=expected(feature.getStringProperty("TAXLOT"),feature.getStringProperty("TAXACCT"))
                assertTrue("Real owner does not equal independent source record at $name",feature.getStringProperty("OWNERNAME")==expectedOwner)
                assertFalse("Synthetic owner data substituted at $name",expectedOwner.startsWith("SYNTHETIC TEST ONLY"))
                val p=a.map!!.projection.toScreenLocation(point);val offset=IntArray(2);a.mapView.getLocationOnScreen(offset)
                x=p.x.toInt()+offset[0];y=p.y.toInt()+offset[1]
            }
            device.click(x,y)
            if(device.wait(Until.hasObject(By.text("Overlapping parcels")),1500)) {
                androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).atPosition(index).perform(androidx.test.espresso.action.ViewActions.click())
            }
            assertTrue("Property popup absent at $name",device.wait(Until.hasObject(By.text("Property details")),10000))
            assertTrue("Displayed real owner mismatch at $name",device.hasObject(By.textContains("Owner: $expectedOwner")))
            if(unknownDate) assertTrue("Unknown ownership snapshot misrepresented at $name",device.hasObject(By.textContains("Ownership snapshot date unknown")))
            checks.put(JSONObject().put("place",name).put("exact_owner_equal_to_private_input",true).put("tap_to_check_ms",SystemClock.elapsedRealtime()-begin))
            device.executeShellCommand("screencap -p /sdcard/Download/private-evidence/$label-${name.replace(' ','-')}.png")
            device.pressBack()
        }
        return checks
    }
    private fun memory(): JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        return JSONObject().put("pss_kib",info.totalPss).put("native_alloc_bytes",Debug.getNativeHeapAllocatedSize())
            .put("java_alloc_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            .put("file_descriptors",File("/proc/self/fd").list()?.size ?: -1).put("directory_cache_bytes",LocalTiles.cacheBytes())
    }
    @Test fun privateRealDataAcceptance() {
        assertTrue("Private runtime fixtures not supplied",File(inputs,"reference.sqlite").exists())
        device.executeShellCommand("mkdir -p /sdcard/Download/private-evidence")
        device.executeShellCommand("cmd connectivity airplane-mode enable");device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
        assertEquals("1",device.executeShellCommand("settings get global airplane_mode_on").trim())
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        if(mode=="old-probe") oldProbe() else currentAcceptance()
    }
    private fun oldProbe() {
        val store=PackageStore(context);store.installBundled()
        val uri=publish(File(inputs,"original-owners.olepack"),"Original-Marion-Owners.olepack")
        val report=JSONObject().put("mode",mode).put("real_private_data",true)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitReady(s);chooseDownload(s,"Original-Marion-Owners.olepack",false)
            waitReady(s) { it.region!!.packages.any { p -> p.kind=="owners" } }
            s.onActivity { a ->
                val packs=a.region!!.packages
                assertEquals(115870,packs.single { it.kind=="owners" }.manifest.getInt("owner_count"))
                val county=packs.count { it.manifest.optString("source_namespace")=="marion-open" }
                assertEquals(0,county)
                report.put("real_owner_archive_imported_v05",true).put("county_parcel_regions_after_owner_only_import",county)
                    .put("owner_overlay_alone_cannot_supply_county_boundaries",true)
            }
        }
        var rejected=false
        try {File(inputs,"original-download.zip").inputStream().use { store.importPackage(it) }} catch(_: Exception) {rejected=true}
        assertTrue("v0.5 unexpectedly accepts outer artifact ZIP",rejected);report.put("v05_outer_download_zip_rejected",true)
        for(file in File(inputs,"maps").listFiles()!!.filter { it.name.endsWith(".olepack") && it.name!="marion-private-owners.olepack" }.sortedBy { it.name }) file.inputStream().use { store.importPackage(it) }
        ActivityScenario.launch(MainActivity::class.java).use { s ->waitReady(s);report.put("with_required_county_maps",popupChecks(s,"v05-real",false))}
        context.contentResolver.delete(uri,null,null)
        val county=store.installed().filter { it.manifest.optString("source_namespace")=="marion-open" }
        val snapshot=JSONObject();for(p in county) snapshot.put(p.id,p.manifest.getJSONObject("sha256"))
        store.setEnabled("gervais-original-parcels",false)
        val disabled=county.first().id;store.setEnabled(disabled,false)
        context.getSharedPreferences("private-upgrade",0).edit().putBoolean("sentinel",true).putString("geometry_hashes",snapshot.toString()).putString("disabled_county",disabled).commit()
        save("v05-real-diagnosis.json",report.put("result","PASS").toString(2))
    }
    private fun currentAcceptance() {
        assertTrue(mode=="upgrade" || mode=="fresh")
        val store=PackageStore(context);store.installBundled()
        val prefs=context.getSharedPreferences("private-upgrade",0)
        if(mode=="upgrade") {
            assertTrue("APK upgrade lost settings",prefs.getBoolean("sentinel",false))
            assertFalse("Existing core enable state lost",store.installed().first { it.id=="gervais-original-parcels" }.enabled)
        }
        val report=JSONObject().put("mode",mode).put("real_private_data",true).put("airplane_mode",true)
        val filename="Marion-County-$mode.zip";val uri=publish(File(inputs,"Marion-County-Offline-v0.6.0.zip"),filename)
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            waitReady(s);val started=SystemClock.elapsedRealtime();chooseDownload(s,filename,true)
            waitReady(s) { a -> a.region!!.packages.count { it.manifest.optString("source_namespace")=="marion-open" }==16 && a.region!!.packages.any { it.kind=="owners" && it.manifest.optString("owner_schema")=="oregon-owners-v2" } }
            report.put("complete_zip_import_ms",SystemClock.elapsedRealtime()-started)
            if(device.hasObject(By.text("Import results"))) device.pressBack()
            val installed=store.installed();assertEquals(115870,installed.filter { it.manifest.optString("source_namespace")=="marion-open" }.sumOf { it.manifest.getInt("parcel_count") })
            assertEquals(115870,installed.single { it.kind=="owners" }.manifest.getInt("owner_count"))
            if(mode=="upgrade") {
                val old=JSONObject(prefs.getString("geometry_hashes",null)!!)
                for(p in installed.filter { it.manifest.optString("source_namespace")=="marion-open" }) assertTrue("County geometry changed during owner delivery",old.getJSONObject(p.id).toString()==p.manifest.getJSONObject("sha256").toString())
                assertFalse("Disabled county setting changed without consent",installed.first { it.id==prefs.getString("disabled_county",null) }.enabled)
                assertFalse(installed.first { it.id=="gervais-original-parcels" }.enabled)
                appClick("Region");menuClick("Enable all county maps")
                waitReady(s) { it.region!!.parcelPackages.count { p -> p.manifest.optString("source_namespace")=="marion-open" }==16 }
            }
            assertEquals(1,context.contentResolver.delete(uri,null,null))
            var unavailable=false;try {context.contentResolver.openInputStream(uri)?.close()} catch(_: Exception) {unavailable=true}
            assertTrue("Deleted source URI unexpectedly remained readable",unavailable)
            report.put("installed_copy_survives_source_uri_removal",true)
            report.put("actual_real_owner_popups",popupChecks(s,mode,true))
            if(mode=="upgrade") {
                val samples=JSONArray();val began=SystemClock.elapsedRealtime()
                repeat(600) { i ->
                    val t=targets.getJSONObject((i/4)%targets.length());val lat=t.getDouble("latitude");val lon=t.getDouble("longitude")
                    s.onActivity { a ->
                        a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat+(i%3-1)*.001,lon+(i%5-2)*.002),doubleArrayOf(12.0,15.0,17.0,18.0)[i%4]))
                        if(i%12==0) {
                            val f=a.region!!.select(lat,lon).single { it.getStringProperty("parcel_key")==t.getString("parcel_key") }
                            assertTrue("Real owner changed during offline navigation",f.getStringProperty("OWNERNAME")==expected(f.getStringProperty("TAXLOT"),f.getStringProperty("TAXACCT")))
                        }
                    }
                    Thread.sleep(300);if(i%12==11) samples.put(memory())
                }
                report.put("navigation_operations",600).put("navigation_ms",SystemClock.elapsedRealtime()-began).put("navigation_memory",samples)
                s.moveToState(Lifecycle.State.CREATED);s.moveToState(Lifecycle.State.RESUMED);waitReady(s);s.recreate();waitReady(s)
                val before=File(context.filesDir,"map-packages/registry.json").readBytes()
                var rejected=false;try {File(inputs,"broken-county.zip").inputStream().use { store.importDownload(it) }} catch(_: Exception) {rejected=true}
                assertTrue("Bad final bundle package accepted",rejected)
                assertTrue("Failed bundle changed installed registry",before.contentEquals(File(context.filesDir,"map-packages/registry.json").readBytes()))
                store.rollback("marion-private-owners");assertEquals(1,store.installed().single { it.kind=="owners" }.manifest.getInt("revision"))
                store.rollback("marion-private-owners");assertEquals(2,store.installed().single { it.kind=="owners" }.manifest.getInt("revision"))
                rejected=false;try {File(inputs,"original-owners.olepack").inputStream().use { store.importPackage(it) }} catch(_: Exception) {rejected=true}
                assertTrue("Older owner package accepted after update/rollback",rejected)
                report.put("failed_bundle_atomic_rollback",true).put("owner_rollback_and_revision_watermark",true)
            }
        }
        save("$mode-real-acceptance.json",report.put("result","PASS").toString(2))
    }
}
