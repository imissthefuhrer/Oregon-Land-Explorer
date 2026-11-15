package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Debug
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Real county open-data polygons, no synthetic ownership and no network permission. */
class CountyPerformanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val assets=instrumentation.context.assets
    private val device=UiDevice.getInstance(instrumentation)
    private fun ready(s: ActivityScenario<MainActivity>) {
        val end=SystemClock.elapsedRealtime()+120000;var loaded=false
        while(!loaded && SystemClock.elapsedRealtime()<end) { s.onActivity { loaded=it.ready };Thread.sleep(100) }
        assertTrue("County map not ready",loaded)
    }
    private fun memory(): JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        val runtime=Runtime.getRuntime()
        return JSONObject().put("pss_kib",info.totalPss).put("native_pss_kib",info.nativePss).put("dalvik_pss_kib",info.dalvikPss)
            .put("java_alloc_bytes",runtime.totalMemory()-runtime.freeMemory()).put("native_alloc_bytes",Debug.getNativeHeapAllocatedSize())
            .put("open_file_descriptors",java.io.File("/proc/self/fd").list()?.size ?: -1).put("directory_cache_bytes",LocalTiles.cacheBytes())
    }
    private fun save(name: String,text: String) {
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ole-evidence")
        }
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) }
    }
    @Test fun countyInstalledAirplaneNavigationSelectionAndRecovery() {
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
        assertEquals("1",device.executeShellCommand("settings get global airplane_mode_on").trim())
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        val store=PackageStore(context);store.installBundled()
        val files=assets.list("county-packs")!!.filter { it.endsWith(".olepack") }.sorted()
        assertTrue("Full county fixtures missing",files.size>2)
        val importStarted=SystemClock.elapsedRealtime()
        for(name in files) store.importPackage(assets.open("county-packs/$name"))
        val report=JSONObject().put("import_all_ms",SystemClock.elapsedRealtime()-importStarted)
            .put("limits","API 35 Pixel 2 hosted emulator, Mesa software rendering; PSS includes instrumentation. Owner names withheld by official public download. Not physical phone performance.")
        val countyPacks=store.installed().filter { it.id.startsWith("marion-") && it.kind=="parcels" }
        val catalog=JSONObject(context.assets.open("county-catalog.json").bufferedReader().use { it.readText() })
        assertEquals(catalog.getJSONArray("packages").length(),countyPacks.size)
        assertEquals(catalog.getJSONArray("packages").let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).getInt("parcel_count") } },countyPacks.sumOf { it.manifest.getInt("parcel_count") })
        report.put("county_package_count",countyPacks.size).put("county_parcel_count",countyPacks.sumOf { it.manifest.getInt("parcel_count") })
        report.put("installed_payload_bytes",store.installed().sumOf { it.payloadBytes })
        val samples=JSONArray();report.put("scenarios",samples)
        save("county-import-performance.json",report.toString(2))
        val anchors=listOf("Salem" to LatLng(44.942,-123.035),"Gervais" to LatLng(45.108,-122.897),
            "Silverton" to LatLng(45.005,-122.783),"Silver Falls" to LatLng(44.878,-122.657),
            "Gates" to LatLng(44.755,-122.416),"Detroit" to LatLng(44.735,-122.151),"Eastern forest" to LatLng(44.85,-121.93))
        for(mode in listOf("one-region","all-regions","overlapping-regions")) {
            val one=countyPacks.first().id
            for(pack in store.installed().filter { it.kind in listOf("parcels","legacy") }) {
                store.setEnabled(pack.id,when(mode) { "one-region" -> pack.id==one;"all-regions" -> pack.id.startsWith("marion-");else -> true })
            }
            val sample=JSONObject().put("scenario",mode);samples.put(sample)
            val started=SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario);sample.put("activity_ready_ms",SystemClock.elapsedRealtime()-started)
                val viewReports=JSONArray();sample.put("views",viewReports)
                for((name,point) in anchors) {
                    val frame=CountDownLatch(1);val moved=SystemClock.elapsedRealtime()
                    scenario.onActivity { a ->
                        a.mapView.addOnDidFinishRenderingFrameListener { fully, _, _ -> if(fully) frame.countDown() }
                        assertTrue("Missing county basemap $name",a.region!!.contains(point.latitude,point.longitude))
                        a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,16.0))
                    }
                    assertTrue("No complete tile frame $name",frame.await(45,TimeUnit.SECONDS))
                    val completeMs=SystemClock.elapsedRealtime()-moved
                    Thread.sleep(1000)
                    viewReports.put(JSONObject().put("place",name).put("complete_frame_ms",completeMs).put("memory",memory()))
                    if(mode=="all-regions") device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/county-${name.replace(' ','-')}.png")
                }
                // Each partition supplies a real exact interior point and identity.
                val selections=JSONArray();sample.put("selection",selections)
                scenario.onActivity { a -> assertEquals(null,LocalTiles.lastError) }
                val regionCount=if(mode=="one-region") 1 else countyPacks.size
                for(pack in countyPacks.take(regionCount)) {
                    val p=pack.manifest.getJSONObject("selection_test")
                    val t=SystemClock.elapsedRealtime()
                    var found=false
                    scenario.onActivity { a ->
                        val hits=a.region!!.select(p.getDouble("latitude"),p.getDouble("longitude"))
                        found=hits.any { it.getNumberProperty("OBJECTID").toLong()==p.getLong("id") && it.getStringProperty("parcel_key").startsWith("41047:marion-open:") }
                        for(f in hits.filter { it.getStringProperty("parcel_key").startsWith("41047:marion-open:") }) {
                            assertTrue(!f.hasProperty("OWNERNAME") || f.getProperty("OWNERNAME").isJsonNull)
                        }
                    }
                    assertTrue("Exact selection failed for ${pack.id}",found)
                    selections.put(JSONObject().put("package",pack.id).put("exact_lookup_ms",SystemClock.elapsedRealtime()-t))
                }
                if(mode!="one-region") {
                    val targets=catalog.getJSONArray("selection_targets")
                    for(i in 0 until targets.length()) {
                        val target=targets.getJSONObject(i);val t=SystemClock.elapsedRealtime()
                        scenario.onActivity { a ->
                            val hits=a.region!!.select(target.getDouble("latitude"),target.getDouble("longitude"))
                            assertTrue("Actual parcel missing near ${target.getString("name")}",hits.any { it.getStringProperty("parcel_key")==target.getString("parcel_key") && it.getStringProperty("TAXLOT")==target.getString("taxlot") })
                        }
                        selections.put(JSONObject().put("target",target.getString("name")).put("package",target.getString("package")).put("exact_lookup_ms",SystemClock.elapsedRealtime()-t))
                    }
                }
                val navigation=JSONArray();sample.put("navigation_memory",navigation)
                val count=if(mode=="overlapping-regions") 600 else 24
                val panStarted=SystemClock.elapsedRealtime()
                for(i in 0 until count) {
                    val point=anchors[(i/4)%anchors.size].second
                    scenario.onActivity { a -> a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(point.latitude+(i%3-1)*.001,point.longitude+(i%5-2)*.002),doubleArrayOf(12.0,15.0,17.0,18.0)[i%4])) }
                    Thread.sleep(300)
                    if(i%12==11) {Thread.sleep(1000);navigation.put(memory())}
                }
                sample.put("pan_zoom_operations",count).put("navigation_ms",SystemClock.elapsedRealtime()-panStarted)
                Thread.sleep(2000);sample.put("after_navigation",memory())
                scenario.moveToState(Lifecycle.State.CREATED);Thread.sleep(1000);scenario.moveToState(Lifecycle.State.RESUMED);ready(scenario)
                scenario.recreate();ready(scenario);Thread.sleep(1000);sample.put("after_recreation",memory())
                save("county-$mode-performance.json",report.toString(2))
                if(mode=="all-regions") {
                    val p=countyPacks.first().manifest.getJSONObject("selection_test")
                    val point=LatLng(p.getDouble("latitude"),p.getDouble("longitude"))
                    val idle=CountDownLatch(1)
                    scenario.onActivity { a ->
                        a.map!!.addOnCameraIdleListener { idle.countDown() }
                        a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,18.0))
                    }
                    assertTrue("Selection camera did not become idle",idle.await(10,TimeUnit.SECONDS))
                    // Moving the camera intentionally cancels stale tap work. Tap
                    // after the idle event, just as a person would, not in that move.
                    scenario.onActivity { assertTrue("Parcel tap was not accepted",it.selectParcel(point)) }
                    val title=By.text(java.util.regex.Pattern.compile("Property details|Overlapping parcels"))
                    val shown=device.wait(Until.hasObject(title),10000)
                    if(!shown) device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/county-popup-failure.png")
                    assertTrue("Neither parcel details nor legitimate overlap chooser appeared",shown)
                    if(device.hasObject(By.text("Overlapping parcels"))) {
                        androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.anything()).atPosition(0).perform(click())
                    }
                    assertTrue("County owner popup missing",device.wait(Until.hasObject(By.text("Property details")),10000))
                    assertTrue("Withheld-owner explanation missing",device.wait(Until.hasObject(By.textContains("Withheld from county public download")),10000))
                    device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/county-owner-withheld.png")
                    device.pressBack()
                }
            }
        }
        // Repeated real GPS provider callbacks through the existing recenter button.
        device.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
        device.executeShellCommand("appops set ${context.packageName} android:mock_location allow")
        val locations=context.getSystemService(LocationManager::class.java)
        locations.addTestProvider(LocationManager.GPS_PROVIDER,false,true,false,false,true,true,true,1,1)
        locations.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ready(scenario)
                for(i in 0 until 14) {
                    val point=anchors[i%anchors.size].second
                    locations.setTestProviderLocation(LocationManager.GPS_PROVIDER,Location(LocationManager.GPS_PROVIDER).apply {
                        latitude=point.latitude;longitude=point.longitude;accuracy=5f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                    })
                    Thread.sleep(2200)
                    onView(withText("GPS")).perform(click())
                    Thread.sleep(1000)
                    scenario.onActivity { assertEquals(point.latitude,it.map!!.cameraPosition.target!!.latitude,.002) }
                }
            }
        } finally {locations.removeTestProvider(LocationManager.GPS_PROVIDER)}
        report.put("gps_recenter_checks",14)
        // Update and rollback a real namespace package without touching any neighbors.
        val original=countyPacks.first();val name=original.id+".olepack"
        val updated=ByteArrayOutputStream()
        ZipInputStream(assets.open("county-packs/$name")).use { input ->
            ZipOutputStream(updated).use { output ->
                while(true) {
                    val entry=input.nextEntry ?: break
                    val data=input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if(entry.name=="manifest.json") JSONObject(String(data)).put("revision",original.manifest.getInt("revision")+1).toString().toByteArray() else data)
                    output.closeEntry()
                }
            }
        }
        store.importPackage(updated.toByteArray().inputStream());store.rollback(original.id)
        assertEquals(original.directory,store.installed().first { it.id==original.id }.directory)
        val others=store.installed().filter { it.id!=original.id }.associate { it.id to it.directory }
        store.remove(original.id)
        assertEquals(others,store.installed().associate { it.id to it.directory })
        store.importPackage(assets.open("county-packs/$name"))
        report.put("update_rollback_remove_reimport","PASS").put("result","PASS")
        save("county-performance.json",report.toString(2))
    }
}
