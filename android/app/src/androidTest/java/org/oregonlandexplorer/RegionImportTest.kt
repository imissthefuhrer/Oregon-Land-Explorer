package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RegionImportTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun waitReady(s: ActivityScenario<MainActivity>) {
        val deadline=SystemClock.elapsedRealtime()+60000;var ready=false
        while(!ready && SystemClock.elapsedRealtime()<deadline) { s.onActivity { ready=it.ready };Thread.sleep(250) }
        assertTrue("Map not ready",ready)
    }
    @Test fun importUsingAndroidDocumentPickerAndRenderDestinationsOffline() {
        device.executeShellCommand("mkdir -p /sdcard/Download/ole-evidence")
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
        assertEquals("1",device.executeShellCommand("settings get global airplane_mode_on").trim())
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitReady(scenario)
            onView(withText("Region")).perform(click())
            onView(withText("Import .olepack from Downloads")).perform(click())
            assertTrue("Android document picker did not open",device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),10000) || device.wait(Until.hasObject(By.pkg("com.android.documentsui")),10000))
            // Downloads may be the initial location or selected from the navigation drawer.
            if(!device.hasObject(By.text("gervais-countryside.olepack"))) {
                device.findObject(By.desc("Show roots"))?.click()
                device.wait(Until.findObject(By.text("Downloads")),5000)?.click()
            }
            val document=device.wait(Until.findObject(By.text("gervais-countryside.olepack")),10000)
            if(document==null) device.executeShellCommand("uiautomator dump /sdcard/Download/ole-evidence/picker.xml")
            assertNotNull("Downloaded package not shown in picker",document)
            document!!.click()
            Thread.sleep(7000);waitReady(scenario)
            scenario.onActivity { a ->
                assertTrue(a.region!!.packages.any { it.id=="gervais-countryside" && it.enabled })
                assertTrue(a.region!!.containsParcel(45.13,-122.947))
            }
            // Independent second region import uses the exact same validated importer.
            PackageStore(context).importPackage(context.assets.open("test-packs/silver-falls.olepack"))
            scenario.recreate();waitReady(scenario)
            val targets=listOf(Triple("gervais",LatLng(45.108,-122.897),"roads"),Triple("st-louis",LatLng(45.130,-122.947),"roads"),Triple("willamette",LatLng(45.085,-123.045),"trails"),Triple("silver-falls",LatLng(44.877,-122.655),"trails"))
            for((name,point,layer) in targets) {
                scenario.onActivity { a ->
                    assertTrue("Base missing $name",a.region!!.contains(point.latitude,point.longitude))
                    assertTrue("Parcel coverage missing $name",a.region!!.containsParcel(point.latitude,point.longitude))
                    a.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(point,15.0))
                }
                Thread.sleep(4500)
                scenario.onActivity { a ->
                    val rect=android.graphics.RectF(0f,0f,a.mapView.width.toFloat(),a.mapView.height.toFloat())
                    assertTrue("No rendered $layer at $name",a.map!!.queryRenderedFeatures(rect,layer).isNotEmpty())
                    assertTrue("No labels at $name",a.map!!.queryRenderedFeatures(rect,"road-labels","place-labels","park-labels").isNotEmpty())
                }
                device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/$name.png")
            }
            // Feed the real LocationManager test provider so GPS listener/recenter runs offline.
            device.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_FINE_LOCATION")
            device.executeShellCommand("pm grant ${context.packageName} android.permission.ACCESS_COARSE_LOCATION")
            device.executeShellCommand("appops set ${context.packageName} android:mock_location allow")
            val locations=context.getSystemService(LocationManager::class.java)
            locations.addTestProvider(LocationManager.GPS_PROVIDER,false,true,false,false,true,true,true,1,1)
            locations.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true)
            onView(withText("GPS")).perform(click())
            val location=Location(LocationManager.GPS_PROVIDER).apply { latitude=45.13;longitude= -122.947;accuracy=5f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos() }
            locations.setTestProviderLocation(LocationManager.GPS_PROVIDER,location)
            Thread.sleep(2500)
            scenario.onActivity { a ->
                assertEquals(45.13,a.map!!.cameraPosition.target!!.latitude,0.002)
                assertTrue(a.map!!.queryRenderedFeatures(a.map!!.projection.toScreenLocation(LatLng(45.13,-122.947)),"gps-dot").isNotEmpty())
            }
            locations.removeTestProvider(LocationManager.GPS_PROVIDER)
            device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/gps-offline.png")
        }
    }

    private fun archive(revision: Int=1,corrupt: Boolean=false,path: String="parcels.geojson"): ByteArray {
        val data="""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"OBJECTID":1,"OWNERNAME":"Synthetic test","TAXLOT":"1"},"geometry":{"type":"Polygon","coordinates":[[[-123,45],[-122.99,45],[-122.99,45.01],[-123,45.01],[-123,45]]]}}]}""".toByteArray()
        val digest=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val m=JSONObject().put("schema_version",2).put("id","synthetic-import-test").put("name","Synthetic import test").put("revision",revision).put("kind","parcels").put("attribution","Synthetic test only").put("bbox",org.json.JSONArray("[-123,45,-122.99,45.01]")).put("parcel_count",1)
        val payload=mapOf(path to data,"source-record-hashes.json" to "{}".toByteArray(),"source-metadata.json" to "{}".toByteArray())
        val hashes=JSONObject()
        for((name,bytes) in payload) hashes.put(name,MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        if(corrupt) hashes.put(path,"0".repeat(64))
        m.put("sha256",hashes)
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for((name,bytes) in payload+mapOf("manifest.json" to m.toString().toByteArray())) { z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry() }
        }
        return out.toByteArray()
    }
    @Test fun rejectUnsafePackagesAndKeepPreviousVersion() {
        val store=PackageStore(context)
        store.importPackage(ByteArrayInputStream(archive()))
        val first=store.installed().first { it.id=="synthetic-import-test" }.directory
        for(bytes in listOf(archive(2,true),archive(2,path="../parcels.geojson"),"not a zip".toByteArray())) {
            try { store.importPackage(ByteArrayInputStream(bytes));fail("Unsafe package accepted") } catch(expected: IllegalArgumentException) { }
            assertEquals(first,store.installed().first { it.id=="synthetic-import-test" }.directory)
        }
        store.importPackage(ByteArrayInputStream(archive(2)))
        try { store.importPackage(ByteArrayInputStream(archive(1)));fail("Downgrade accepted") } catch(expected: IllegalArgumentException) { }
        store.rollback("synthetic-import-test")
        assertEquals(first,store.installed().first { it.id=="synthetic-import-test" }.directory)
        store.setEnabled("synthetic-import-test",false)
    }
}
