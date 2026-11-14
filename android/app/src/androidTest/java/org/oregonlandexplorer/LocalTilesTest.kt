package org.oregonlandexplorer

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.http.HttpResponder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalTilesTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun fixtures()=JSONObject(instrumentation.context.assets.open("tile-fixtures/oracles.json").bufferedReader().use { it.readText() })
    private fun hash(body: ByteArray)=java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    @Test fun hilbertAddressesMatchIndependentPythonOracle() {
        val rows=fixtures().getJSONArray("addresses")
        for(i in 0 until rows.length()) {
            val r=rows.getJSONArray(i)
            assertEquals(r.getLong(3),LocalTiles.tileId(r.getInt(0),r.getInt(1),r.getInt(2)))
        }
    }

    @Test fun boundedLocalTransportMatchesRealTilesAndNestedDirectoryOracleOffline() {
        val store=PackageStore(context);store.installBundled()
        val packs=store.installed().associateBy { it.id }
        val f=fixtures();val bodies=f.getJSONArray("bodies")
        var url="";var expected=""
        for(i in 0 until bodies.length()) {
            val row=bodies.getJSONObject(i)
            val key=LocalTiles.register(File(packs.getValue(row.getString("pack")).directory,row.getString("payload")))
            val body=LocalTiles.tile(key,row.getInt("z"),row.getInt("x"),row.getInt("y"))!!
            assertEquals(row.getString("sha256"),hash(body))
            if(i==0) {
                url=LocalTiles.source(key,"vector").getJSONArray("tiles").getString(0)
                    .replace("{z}",row.getInt("z").toString()).replace("{x}",row.getInt("x").toString()).replace("{y}",row.getInt("y").toString())
                expected=row.getString("sha256")
            }
        }
        val file=File(context.cacheDir,"synthetic-reader.pmtiles")
        instrumentation.context.assets.open("tile-fixtures/synthetic-reader.pmtiles").use { input -> file.outputStream().use { input.copyTo(it) } }
        val key=LocalTiles.register(file);val nested=f.getJSONArray("nested")
        for(i in 0 until nested.length()) {
            val row=nested.getJSONObject(i)
            assertEquals(row.getString("sha256"),hash(LocalTiles.tile(key,row.getInt("z"),row.getInt("x"),row.getInt("y"))!!))
        }
        LocalTiles.install()
        val done=CountDownLatch(1);val result=java.util.concurrent.atomic.AtomicReference<ByteArray>();val failure=java.util.concurrent.atomic.AtomicReference<String>()
        val callback=object: HttpResponder {
            override fun onResponse(code: Int,tag: String?,modified: String?,cache: String?,expires: String?,retry: String?,reset: String?,body: ByteArray) { if(code==200) result.set(body) else failure.set("HTTP $code");done.countDown() }
            override fun handleFailure(type: Int,message: String) { failure.set(message);done.countDown() }
        }
        MapLibre.getModuleProvider().createHttpRequest().executeRequest(callback,0L,url,null,null,null,false)
        assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(failure.get());assertEquals(expected,hash(result.get()))
        repeat(500) {
            val request=MapLibre.getModuleProvider().createHttpRequest()
            request.executeRequest(callback,0L,url,null,null,null,false);request.cancelRequest()
        }
        val until=android.os.SystemClock.elapsedRealtime()+10000
        while(LocalTiles.pendingRequests()>0 && android.os.SystemClock.elapsedRealtime()<until) Thread.sleep(50)
        assertEquals(0,LocalTiles.pendingRequests())
        assertTrue(LocalTiles.cacheBytes()<=8*1024*1024)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.INTERNET))
    }
}
