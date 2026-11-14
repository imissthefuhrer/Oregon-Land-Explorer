package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.layers.PropertyFactory.visibility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OfflineMapTest {
    @Test fun coldStartRendersVectorAndSelectsOriginalParcelWithoutInternetPermission() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        instrumentation.runOnMainSync {
            org.maplibre.android.MapLibre.getInstance(context)
            org.maplibre.android.storage.FileSource.getInstance(context).setResourceTransform { kind,url ->
                android.util.Log.i("OLE_RESOURCE","$kind $url");url
            }
        }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val device=UiDevice.getInstance(instrumentation)
        device.executeShellCommand("mkdir -p /sdcard/Download/ole-evidence")
        // UTP can restore networking while preparing/installing APKs. Disable it HERE,
        // immediately before the cold launch, rather than trusting pre-Gradle shell state.
        device.executeShellCommand("cmd connectivity airplane-mode enable")
        device.executeShellCommand("svc wifi disable")
        device.executeShellCommand("svc data disable")
        assertEquals("1", device.executeShellCommand("settings get global airplane_mode_on").trim())
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        fun connected()=connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
        val offlineDeadline=System.currentTimeMillis()+15000
        while(connected() && System.currentTimeMillis()<offlineDeadline) Thread.sleep(250)
        assertFalse("Emulator retained validated connectivity",connected())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ready=false
            val deadline=System.currentTimeMillis()+60000
            while(!ready && System.currentTimeMillis()<deadline) {
                scenario.onActivity { ready=it.ready }
                Thread.sleep(250)
            }
            assertTrue("Local style did not load",ready)
            onView(withText(containsString("Offline ·"))).check(matches(isDisplayed()))
            // Vector snapshot must contain styled land, roads and labels with parcels hidden.
            scenario.onActivity { activity ->
                activity.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(45.108,-122.897),15.0))
                for(name in listOf("parcel-fill","parcel-line")) activity.map!!.style!!.getLayer(name)!!.setProperties(visibility("none"))
            }
            Thread.sleep(7000)
            val latch=CountDownLatch(1)
            var colors=0
            var darkLabelPixels=0
            scenario.onActivity { activity -> activity.map!!.snapshot { bitmap ->
                val unique=HashSet<Int>()
                for(x in bitmap.width/4 until bitmap.width*3/4 step 3) for(y in bitmap.height/4 until bitmap.height*3/4 step 3) unique.add(bitmap.getPixel(x,y))
                colors=unique.size
                for(x in 0 until bitmap.width step 2) for(y in 0 until bitmap.height step 2) {
                    val c=bitmap.getPixel(x,y)
                    if(android.graphics.Color.red(c)<125 && android.graphics.Color.green(c)<125 && android.graphics.Color.blue(c)<125) darkLabelPixels++
                }
                latch.countDown()
            } }
            assertTrue("Snapshot timed out",latch.await(20,TimeUnit.SECONDS))
            device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/offline-vector-corridor.png")
            scenario.onActivity { a ->
                val rect=android.graphics.RectF(0f,0f,a.mapView.width.toFloat(),a.mapView.height.toFloat())
                val features=a.map!!.queryRenderedFeatures(rect,"roads")
                android.util.Log.i("OLE_DIAGNOSTIC","glyphs="+org.json.JSONObject(a.map!!.style!!.json).optString("glyphs"))
                android.util.Log.i("OLE_DIAGNOSTIC","roads="+features.take(4).joinToString { it.properties().toString() })
            }
            assertTrue("Expected styled vector detail, found only $colors colors", colors>30)
            if(darkLabelPixels<=100) {
                // Failure-only probes distinguish expression evaluation, asset loading and glyph rendering.
                scenario.onActivity { a -> for(name in listOf("road-labels","park-labels","place-labels")) a.map!!.style!!.getLayer(name)!!.setProperties(org.maplibre.android.style.layers.PropertyFactory.textField("{name}")) }
                Thread.sleep(3500)
                device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/token-label-probe.png")
                val directory=java.io.File(context.cacheDir,"glyph-probe/Noto Sans Regular").apply { mkdirs() }
                for(name in context.assets.list("glyphs/Noto Sans Regular")!!) context.assets.open("glyphs/Noto Sans Regular/$name").use { input -> java.io.File(directory,name).outputStream().use { input.copyTo(it) } }
                scenario.onActivity { a ->
                    val json=org.json.JSONObject(a.region!!.style())
                    json.put("glyphs","file://${directory.parentFile!!.absolutePath}/{fontstack}/{range}.pbf")
                    for(i in 0 until json.getJSONArray("layers").length()) {
                        val layer=json.getJSONArray("layers").getJSONObject(i)
                        if(layer.getString("type")=="symbol") layer.getJSONObject("layout").put("text-field","{name}")
                    }
                    a.map!!.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(json.toString())) { style ->
                        style.addSource(org.maplibre.android.style.sources.GeoJsonSource("font-probe",org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(-122.897,45.108))))
                        style.addLayer(org.maplibre.android.style.layers.SymbolLayer("font-probe","font-probe").withProperties(org.maplibre.android.style.layers.PropertyFactory.textField("Font diagnostic"),org.maplibre.android.style.layers.PropertyFactory.textFont(arrayOf("Noto Sans Regular")),org.maplibre.android.style.layers.PropertyFactory.textSize(24f),org.maplibre.android.style.layers.PropertyFactory.textColor("#000000"),org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap(true)))
                    }
                }
                Thread.sleep(5000)
                device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/file-glyph-probe.png")
            }
            assertTrue("Map labels must be visibly rendered ($darkLabelPixels dark pixels)",darkLabelPixels>100)
            var expectedTaxlot=""
            var expectedOwner=""
            var clickX=0
            var clickY=0
            scenario.onActivity { activity ->
                for(name in listOf("parcel-fill","parcel-line")) activity.map!!.style!!.getLayer(name)!!.setProperties(visibility("visible"))
            }
            Thread.sleep(1500)
            device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/offline-boundaries.png")
            scenario.onActivity { activity ->
                val test=activity.region!!.manifest.getJSONObject("selection_test")
                val feature=activity.region!!.parcels.features()!!.first { it.getNumberProperty("OBJECTID").toLong()==test.getLong("id") }
                expectedTaxlot=feature.getStringProperty("TAXLOT")
                expectedOwner=feature.getStringProperty("OWNERNAME") ?: "Not available"
                activity.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(test.getDouble("latitude"),test.getDouble("longitude")),18.0))
            }
            Thread.sleep(3000)
            scenario.onActivity { activity ->
                val test=activity.region!!.manifest.getJSONObject("selection_test")
                val p=activity.map!!.projection.toScreenLocation(LatLng(test.getDouble("latitude"),test.getDouble("longitude")))
                val offset=IntArray(2);activity.mapView.getLocationOnScreen(offset)
                clickX=p.x.toInt()+offset[0]; clickY=p.y.toInt()+offset[1]
            }
            assertTrue(device.click(clickX,clickY))
            Thread.sleep(1000)
            onView(withText("Property details")).check(matches(isDisplayed()))
            onView(withText(containsString("Tax lot: $expectedTaxlot"))).check(matches(isDisplayed()))
            onView(withText(containsString("Owner: $expectedOwner"))).check(matches(isDisplayed()))
            onView(withText(containsString("Legal public access: Unknown"))).check(matches(isDisplayed()))
            device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/offline-parcel.png")
            device.pressBack()
            scenario.onActivity { it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(44.7,-123.2),15.0)) }
            Thread.sleep(1500)
            onView(withText(containsString("Area not downloaded"))).check(matches(isDisplayed()))
            scenario.recreate()
            Thread.sleep(5000)
            scenario.onActivity { assertTrue("Restart did not reload local region",it.ready) }
        }
    }
}
