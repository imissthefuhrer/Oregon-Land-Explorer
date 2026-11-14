package org.oregonlandexplorer

import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Local-only tile transport for the unchanged MapLibre renderer. No sockets/server.
 * Avoids MapLibre 12's PMTilesFileSource retaining completed range requests (#4832).
 * The public HttpRequest adapter receives virtual URLs and answers from disk.
 */
object LocalTiles {
    private const val HOST="tiles.oregon-land-explorer.invalid"
    private const val MAX_BYTES=4*1024*1024
    private val archives=java.util.concurrent.ConcurrentHashMap<String,Archive>()
    private val workers=ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(128))
    private val directories=object: LruCache<String,Directory>(8*1024*1024) {
        override fun sizeOf(key: String,value: Directory)=value.ids.size*32+key.length*2
    }
    private var installed=false
    @Volatile var lastError: String?=null
    @Volatile var onError: ((String)->Unit)?=null

    @Synchronized fun install() {
        if(installed) return
        val original=MapLibre.getModuleProvider()
        MapLibre.setModuleProvider(object: ModuleProvider {
            override fun createHttpRequest(): HttpRequest=Request()
            override fun createLibraryLoaderProvider()=original.createLibraryLoaderProvider()
        })
        installed=true
    }

    /** Called during Region preparation on its worker, never during map movement. */
    fun register(file: File): String {
        val key=file.parentFile!!.name+"-"+file.name.removeSuffix(".pmtiles")
        archives.computeIfAbsent(key) { Archive(file) }
        return key
    }
    fun source(key: String,type: String): JSONObject {
        val a=archives[key] ?: error("Local tile archive not registered")
        return JSONObject().put("type",type)
            .put("tiles",JSONArray().put("https://$HOST/$key/{z}/{x}/{y}"))
            .put("scheme","xyz").put("minzoom",a.low).put("maxzoom",a.high).put("bounds",JSONArray(a.bounds.toList()))
            .also { if(type=="raster") it.put("tileSize",256) }
    }
    fun retain(keys: Set<String>) {
        for(key in archives.keys) if(key !in keys) archives.remove(key)
        synchronized(directories) {
            for(key in directories.snapshot().keys) if(key.substringBefore('|') !in keys) directories.remove(key)
        }
    }
    fun trim()=directories.evictAll()
    fun tile(key: String,z: Int,x: Int,y: Int): ByteArray?=archives[key]?.tile(key,z,x,y)
    fun cacheBytes()=directories.size()
    fun pendingRequests()=workers.queue.size

    private class Request: HttpRequest {
        @Volatile private var cancelled=false
        private var task: FutureTask<Unit>?=null
        override fun executeRequest(responder: HttpResponder,nativePtr: Long,resourceUrl: String,
                                    dataRange: String?,etag: String?,modified: String?,offlineUsage: Boolean) {
            val work=FutureTask<Unit> {
                if(!cancelled) try {
                    val uri=Uri.parse(resourceUrl);val p=uri.pathSegments
                    require(uri.scheme=="https" && uri.host==HOST && p.size==4) { "Remote tile requests are disabled" }
                    require(dataRange.isNullOrEmpty()) { "Unexpected virtual tile range" }
                    val body=tile(p[0],p[1].toInt(),p[2].toInt(),p[3].toInt())
                    if(!cancelled) responder.onResponse(if(body==null) 404 else 200,null,null,
                        "max-age=31536000, immutable",null,null,null,body ?: ByteArray(0))
                } catch(error: Exception) {
                    if(!cancelled) {
                        val message=error.message ?: "Cannot read local tile"
                        if(lastError!=message) { lastError=message;onError?.invoke(message) }
                        responder.handleFailure(HttpRequest.PERMANENT_ERROR,message)
                    }
                }
            }
            synchronized(this) {
                task=work
                if(cancelled) return
                try { workers.execute(work) }
                catch(full: java.util.concurrent.RejectedExecutionException) {
                    responder.handleFailure(HttpRequest.TEMPORARY_ERROR,"Local tile request queue is full")
                }
            }
        }
        override fun cancelRequest()=synchronized(this) {
            cancelled=true;task?.let { it.cancel(false);workers.remove(it) };task=null
        }
    }

    /** PMTiles v3 directory layout/Hilbert ordering: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md */
    private class Directory(val ids: LongArray,val runs: LongArray,val lengths: LongArray,val offsets: LongArray)
    private class Archive(val file: File) {
        val low: Int;val high: Int;val bounds: DoubleArray
        private val rootOffset: Long;private val rootLength: Long
        private val leafOffset: Long;private val tileOffset: Long
        private val internalCompression: Int;private val tileCompression: Int
        init {
            val bytes=read(0,127);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes,0,7,Charsets.US_ASCII)=="PMTiles" && bytes[7].toInt()==3)
            rootOffset=b.getLong(8);rootLength=b.getLong(16);leafOffset=b.getLong(40);tileOffset=b.getLong(56)
            internalCompression=bytes[97].toInt();tileCompression=bytes[98].toInt()
            require(internalCompression in 1..2 && tileCompression in 1..2)
            low=bytes[100].toInt();high=bytes[101].toInt()
            bounds=doubleArrayOf(b.getInt(102)/1e7,b.getInt(106)/1e7,b.getInt(110)/1e7,b.getInt(114)/1e7)
        }
        private fun read(offset: Long,length: Long): ByteArray {
            require(length in 0..MAX_BYTES.toLong()) { "Local tile range exceeds decode budget" }
            return RandomAccessFile(file,"r").use { input ->
                require(offset>=0 && offset<=input.length() && length<=input.length()-offset) { "Invalid local tile range" }
                ByteArray(length.toInt()).also { input.seek(offset);input.readFully(it) }
            }
        }
        private fun inflate(bytes: ByteArray,compression: Int): ByteArray {
            if(compression==1) return bytes
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out=ByteArrayOutputStream();val buffer=ByteArray(16384)
                while(true) {
                    val n=input.read(buffer);if(n<0) break
                    require(out.size()+n<=MAX_BYTES) { "Local tile exceeds decode budget" }
                    out.write(buffer,0,n)
                };out.toByteArray()
            }
        }
        private fun directory(key: String,offset: Long,length: Long): Directory {
            val cacheKey="$key|$offset|$length"
            synchronized(directories) { directories.get(cacheKey)?.let { return it } }
            val bytes=inflate(read(offset,length),internalCompression);var cursor=0
            fun number(): Long {
                var value=0L;var shift=0
                while(true) {
                    require(cursor<bytes.size && shift<63) { "Invalid PMTiles directory varint" }
                    val v=bytes[cursor++].toInt() and 255
                    value=value or ((v and 127).toLong() shl shift)
                    if(v and 128==0) return value
                    shift+=7
                }
            }
            val count=number();require(count in 0..100000)
            val n=count.toInt();val ids=LongArray(n);val runs=LongArray(n);val lengths=LongArray(n);val offsets=LongArray(n)
            var id=0L
            for(i in 0 until n) { val delta=number();id=Math.addExact(id,delta);ids[i]=id;if(i>0) require(ids[i]>ids[i-1]) }
            for(i in 0 until n) runs[i]=number()
            for(i in 0 until n) lengths[i]=number()
            for(i in 0 until n) { val v=number();offsets[i]=if(v==0L && i>0) Math.addExact(offsets[i-1],lengths[i-1]) else v-1;require(offsets[i]>=0) }
            require(cursor==bytes.size)
            return Directory(ids,runs,lengths,offsets).also { synchronized(directories) { directories.put(cacheKey,it) } }
        }
        fun tile(key: String,z: Int,x: Int,y: Int): ByteArray? {
            if(z !in low..high) return null
            val id=tileId(z,x,y);var offset=rootOffset;var length=rootLength
            repeat(4) {
                val d=directory(key,offset,length)
                var left=0;var right=d.ids.size-1;var found= -1
                while(left<=right) { val middle=(left+right) ushr 1;if(d.ids[middle]<=id) { found=middle;left=middle+1 } else right=middle-1 }
                if(found<0) return null
                if(d.runs[found]>0) {
                    if(id-d.ids[found]>=d.runs[found]) return null
                    return inflate(read(Math.addExact(tileOffset,d.offsets[found]),d.lengths[found]),tileCompression)
                }
                offset=Math.addExact(leafOffset,d.offsets[found]);length=d.lengths[found]
            }
            error("PMTiles directory depth exceeds supported limit")
        }
    }

    fun tileId(z: Int,x: Int,y: Int): Long {
        require(z in 0..26 && x>=0 && y>=0 && x<(1 shl z) && y<(1 shl z))
        var xx=x.toLong();var yy=y.toLong();var s=1L shl z
        var id=((1L shl (2*z))-1)/3
        s=s shr 1
        while(s>0) {
            val rx=if(xx and s!=0L) 1L else 0L;val ry=if(yy and s!=0L) 1L else 0L
            id+=s*s*((3*rx) xor ry)
            if(ry==0L) { if(rx==1L) { xx=s-1-xx;yy=s-1-yy };val swap=xx;xx=yy;yy=swap }
            s=s shr 1
        }
        return id
    }
}
