package org.oregonlandexplorer

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CountyRenderDiagnosticTest {
    @Test fun inspectActualCountyFrameAndLocalSources() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext;val device=UiDevice.getInstance(i)
        val store=PackageStore(context);store.installBundled()
        for(name in listOf("marion-outdoors","marion-salem-west11")) store.importPackage(i.context.assets.open("county-packs/$name.olepack"))
        for(pack in store.installed().filter { it.kind=="parcels" }) store.setEnabled(pack.id,pack.id=="marion-salem-west11")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ready=false;val deadline=SystemClock.elapsedRealtime()+90000
            while(!ready && SystemClock.elapsedRealtime()<deadline) {scenario.onActivity { ready=it.ready };Thread.sleep(100)}
            assertTrue(ready);Thread.sleep(3000)
            val frame=CountDownLatch(1);var partial=0
            scenario.onActivity { a ->
                a.mapView.addOnDidFinishRenderingMapListener { if(it) frame.countDown() else partial++ }
                a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(45.108,-122.897),16.0))
            }
            val complete=frame.await(45,TimeUnit.SECONDS)
            val report=JSONObject().put("complete",complete).put("partial_frames",partial).put("tile_error",LocalTiles.lastError)
                .put("pending_requests",LocalTiles.pendingRequests()).put("directory_bytes",LocalTiles.cacheBytes())
            scenario.onActivity { a ->
                val rect=android.graphics.RectF(0f,0f,a.mapView.width.toFloat(),a.mapView.height.toFloat())
                report.put("rendered_roads",a.map!!.queryRenderedFeatures(rect,"roads").size)
                report.put("rendered_labels",a.map!!.queryRenderedFeatures(rect,"road-labels","place-labels","park-labels").size)
                report.put("style",a.region!!.style())
            }
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"county-render-diagnostic.json")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/ole-evidence")
            }
            val uri=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
            context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(report.toString(2)) }
            device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/county-render-diagnostic.png")
            assertTrue("No complete Gervais frame; diagnostics and logcat preserved",complete)
        }
    }
}
