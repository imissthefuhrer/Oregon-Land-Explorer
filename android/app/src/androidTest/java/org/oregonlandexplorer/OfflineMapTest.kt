package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
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
    @Test fun coldStartRendersRealImageryAndSelectsParcelWithoutInternetPermission() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val device=UiDevice.getInstance(instrumentation)
        device.executeShellCommand("mkdir -p /sdcard/Download/ole-evidence")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ready=false
            val deadline=System.currentTimeMillis()+60000
            while(!ready && System.currentTimeMillis()<deadline) {
                scenario.onActivity { ready=it.ready }
                Thread.sleep(250)
            }
            assertTrue("Local style did not load",ready)
            // Native snapshot must contain actual photographic detail, not just orange polygons.
            scenario.onActivity { activity ->
                for(name in listOf("parcel-fill","parcel-line")) activity.map!!.style!!.getLayer(name)!!.setProperties(visibility("none"))
            }
            Thread.sleep(7000)
            val latch=CountDownLatch(1)
            var colors=0
            scenario.onActivity { activity -> activity.map!!.snapshot { bitmap ->
                val unique=HashSet<Int>()
                for(x in bitmap.width/4 until bitmap.width*3/4 step 3) for(y in bitmap.height/4 until bitmap.height*3/4 step 3) unique.add(bitmap.getPixel(x,y))
                colors=unique.size
                latch.countDown()
            } }
            assertTrue("Snapshot timed out",latch.await(20,TimeUnit.SECONDS))
            assertTrue("Expected photographic detail, found only $colors colors", colors>200)
            device.executeShellCommand("screencap -p /sdcard/Download/ole-evidence/offline-aerial.png")
            var expectedTaxlot=""
            var expectedOwner=""
            var clickX=0
            var clickY=0
            scenario.onActivity { activity ->
                for(name in listOf("parcel-fill","parcel-line")) activity.map!!.style!!.getLayer(name)!!.setProperties(visibility("visible"))
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
            scenario.onActivity { it.map!!.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(44.9,-123.2),15.0)) }
            Thread.sleep(1500)
            onView(withText(containsString("Area not downloaded"))).check(matches(isDisplayed()))
            scenario.recreate()
            Thread.sleep(5000)
            scenario.onActivity { assertTrue("Restart did not reload local region",it.ready) }
        }
    }
}
