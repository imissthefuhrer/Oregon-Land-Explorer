package org.oregonlandexplorer

import android.os.Debug
import android.os.SystemClock
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Compiled unchanged against PR #4 AND the new app, on the same emulator.
 * Does not access Region.parcels or any new implementation API.
 */
class ParcelPerformanceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private val variant=InstrumentationRegistry.getArguments().getString("variant") ?: "optimized"
    private fun ready(s: ActivityScenario<MainActivity>) {
        val end=SystemClock.elapsedRealtime()+90000;var loaded=false
        while(!loaded && SystemClock.elapsedRealtime()<end) { s.onActivity { loaded=it.ready };Thread.sleep(100) }
        assertTrue("Map not ready",loaded)
    }
    private fun save(name: String, text: String) {
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ole-evidence")
        }
        val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) }
    }
    private fun memory(): JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        val runtime=Runtime.getRuntime()
        return JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()).put("pss_kib",info.totalPss)
            .put("dalvik_pss_kib",info.dalvikPss).put("native_pss_kib",info.nativePss)
            .put("java_alloc_bytes",runtime.totalMemory()-runtime.freeMemory())
            .put("native_alloc_bytes",Debug.getNativeHeapAllocatedSize())
    }
    @Test fun compareRegionScenariosAndExtendedNavigation() {
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
        val store=PackageStore(context);store.installBundled()
        for(name in listOf("gervais-original-parcels","gervais-countryside","silver-falls")) {
            store.importPackage(instrumentation.context.assets.open("test-packs/$name.olepack"))
        }
        val report=JSONObject().put("variant",variant).put("api",android.os.Build.VERSION.SDK_INT)
            .put("limits","Instrumentation PSS includes the test runner. Same process per variant; startup is ActivityScenario creation, not a force-stopped process. Separate shell cold-launch timings accompany this report. Mesa software rendering is not phone GPU evidence.")
        val scenarios=JSONArray()
        for(name in listOf("original","gervais","silver","both")) {
            store.setEnabled("gervais-countryside",name in listOf("gervais","both"))
            store.setEnabled("silver-falls",name in listOf("silver","both"))
            val result=JSONObject().put("scenario",name)
            val started=SystemClock.elapsedRealtime()
            ActivityScenario.launch(MainActivity::class.java).use { s ->
                ready(s);result.put("activity_ready_ms",SystemClock.elapsedRealtime()-started)
                val rendered=CountDownLatch(1)
                val completedFrames=JSONArray()
                var frameStarted=0L;var fpsCount=0;var fpsSum=0.0;var fpsMin=Double.POSITIVE_INFINITY
                val point=if(name=="silver") LatLng(44.877,-122.655) else LatLng(45.108,-122.897)
                s.onActivity { a ->
                    a.mapView.addOnDidFinishRenderingMapListener { fully ->
                        if(fully) {
                            rendered.countDown()
                            if(frameStarted>0) { completedFrames.put(SystemClock.elapsedRealtime()-frameStarted);frameStarted=0 }
                        }
                    }
                    a.map!!.setOnFpsChangedListener { fps -> fpsCount++;fpsSum+=fps;fpsMin=minOf(fpsMin,fps) }
                    frameStarted=SystemClock.elapsedRealtime()
                    a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,15.0))
                }
                assertTrue("Map frame not completed",rendered.await(45,TimeUnit.SECONDS))
                result.put("first_complete_frame_ms",SystemClock.elapsedRealtime()-started)
                Thread.sleep(3000);result.put("settled",memory())
                val selection=JSONArray()
                val lookupToUi=JSONArray()
                // Identical original source polygon point in each APK, irrespective of tile boundaries.
                val pack=store.installed().first { it.id==if(name=="silver") "silver-falls" else "gervais-original-parcels" }
                val p=pack.manifest.getJSONObject("selection_test")
                val hit=LatLng(p.getDouble("latitude"),p.getDouble("longitude"))
                s.onActivity { it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(hit,18.0)) }
                Thread.sleep(2000)
                for(i in 0 until 15) {
                    val tap=SystemClock.elapsedRealtime()
                    s.onActivity { assertTrue(it.selectParcel(hit)) }
                    assertTrue("Parcel popup missing",device.wait(Until.hasObject(By.text("Property details")),10000))
                    selection.put(SystemClock.elapsedRealtime()-tap)
                    s.onActivity { a ->
                        // Optional metric from the new app; the benchmark still compiles
                        // unchanged against PR #4 and measures both popup latencies.
                        a.javaClass.methods.firstOrNull { it.name=="getLastSelectionMs" }?.let { lookupToUi.put(it.invoke(a)) }
                    }
                    device.pressBack()
                }
                result.put("tap_to_popup_ms",selection)
                if(lookupToUi.length()>0) result.put("lookup_to_ui_ms",lookupToUi)
                val samples=JSONArray();val navigationStarted=SystemClock.elapsedRealtime()
                device.executeShellCommand("dumpsys gfxinfo ${context.packageName} reset")
                val count=if(name=="both") 600 else 12
                for(i in 0 until count) {
                    // Repeatable regional switches, pans and zooms, including tile seams.
                    val anchor=if(i%4<2) point else LatLng(44.877,-122.655)
                    val position=LatLng(anchor.latitude+(i%3-1)*0.002,anchor.longitude+(i%5-2)*0.003)
                    val zoom=doubleArrayOf(13.0,15.0,17.0,18.0)[i%4]
                    s.onActivity { frameStarted=SystemClock.elapsedRealtime();it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(position,zoom)) }
                    Thread.sleep(if(name=="both") 300 else 100)
                    if(i%12==11) { Thread.sleep(1500);samples.put(memory()) }
                }
                result.put("navigation_ms",SystemClock.elapsedRealtime()-navigationStarted).put("navigation_samples",samples)
                // Extended run includes repeated return to a fixed viewport for comparable end memory.
                s.onActivity { it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,15.0)) }
                Thread.sleep(3000);result.put("after_navigation",memory())
                s.onActivity { it.map!!.setOnFpsChangedListener(null) }
                result.put("completed_viewport_frame_ms",completedFrames)
                result.put("native_fps_samples",fpsCount)
                if(fpsCount>0) result.put("native_fps_mean",fpsSum/fpsCount).put("native_fps_min",fpsMin)
                save("$variant-$name-gfx.txt",device.executeShellCommand("dumpsys gfxinfo ${context.packageName} framestats"))
                s.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                Thread.sleep(1000);s.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);ready(s)
                s.recreate();ready(s);Thread.sleep(2000);result.put("after_restore",memory())
                save("$variant-$name-memory.txt",device.executeShellCommand("dumpsys meminfo ${context.packageName}"))
                result.put("installed_payload_bytes",store.installed().sumOf { pck -> pck.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } })
                result.put("internal_data_bytes_all_versions_and_indexes",context.dataDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                result.put("installed_apk_bytes",java.io.File(context.applicationInfo.sourceDir).length())
            }
            scenarios.put(result);report.put("scenarios",scenarios)
            save("$variant-performance-$name.json",report.toString(2))
            Thread.sleep(1000)
        }
        save("$variant-performance.json",report.toString(2))
    }
}
