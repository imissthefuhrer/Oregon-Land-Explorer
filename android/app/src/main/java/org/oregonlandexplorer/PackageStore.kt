package org.oregonlandexplorer

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.FeatureCollection
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/** Immutable validated versions plus one atomically written registry. Readers never see staging. */
class PackageStore(private val context: Context) {
    private val root = File(context.filesDir,"map-packages").apply { mkdirs() }
    private val index = AtomicFile(File(root,"registry.json"))
    private fun registry(): JSONObject = if(index.baseFile.exists() || File(root,"registry.json.bak").exists()) index.openRead().bufferedReader().use { JSONObject(it.readText()) } else JSONObject()
    private fun commit(value: JSONObject) {
        val stream=index.startWrite()
        try { stream.write(value.toString().toByteArray()); index.finishWrite(stream) }
        catch(e: Exception) { index.failWrite(stream); throw e }
    }
    fun installed(): List<MapPackage> = synchronized(LOCK) {
        val r=registry()
        return r.keys().asSequence().map { id ->
            val record=r.getJSONObject(id)
            MapPackage(File(root,record.getString("current")),record.optBoolean("enabled",true)).also { pack ->
                validate(pack.directory,pack.manifest,pack.directory.list()!!.toSet(),false)
            }
        }.sortedBy { it.manifest.getString("name") }.toList()
    }
    fun installBundled(): Unit = synchronized(LOCK) {
        val installed=registry()
        for(name in context.assets.list("packs") ?: emptyArray()) {
            if(installed.has(name.removeSuffix(".olepack"))) continue
            context.assets.open("packs/$name").use { importPackage(it,true) }
        }
    }
    fun importPackage(input: InputStream, bundled: Boolean = false): MapPackage = synchronized(LOCK) {
        val stage=File(root,".staging-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val seen=HashSet<String>(); var total=0L
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    val name=entry.name
                    require(seen.size<8 && name in ALLOWED && seen.add(name) && !entry.isDirectory) { "Unexpected or duplicate package entry: $name" }
                    var size=0L
                    File(stage,name).outputStream().use { out ->
                        val bytes=ByteArray(65536)
                        while(true) {
                            val n=zip.read(bytes); if(n<0) break
                            size+=n;total+=n
                            require(total<=MAX_TOTAL && size<=limit(name)) { "Package exceeds safe size limit" }
                            out.write(bytes,0,n)
                        }
                        out.fd.sync()
                    }
                    zip.closeEntry()
                }
            }
            require("manifest.json" in seen) { "Missing manifest.json; choose an .olepack archive" }
            val m=JSONObject(File(stage,"manifest.json").readText())
            validate(stage,m,seen)
            val id=m.getString("id")
            val revision=m.optInt("revision",1)
            val digest=sha(File(stage,"manifest.json"))
            val r=registry();val previous=r.optJSONObject(id)
            if(previous!=null) {
                val old=MapPackage(File(root,previous.getString("current")),true)
                if(sha(File(old.directory,"manifest.json"))==digest) return old
                if(bundled) return old // APK upgrades must never replace an installed region.
                require(revision>old.manifest.optInt("revision",1)) { "Update revision must be newer; installed data was preserved" }
            }
            val destination=File(root,"$id-$revision-${digest.take(16)}")
            // An unreferenced complete version may remain after a process interruption.
            if(destination.exists()) {
                require(sha(File(destination,"manifest.json"))==digest)
                validate(destination,m,destination.list()!!.toSet())
            } else check(stage.renameTo(destination)) { "Cannot activate package" }
            val record=JSONObject().put("current",destination.name).put("enabled",previous?.optBoolean("enabled",true) ?: true)
            previous?.let { record.put("previous",it.getString("current")) }
            r.put(id,record);commit(r)
            return MapPackage(destination,record.getBoolean("enabled"))
        } finally { stage.deleteRecursively() }
    }
    fun setEnabled(id: String, enabled: Boolean): Unit = synchronized(LOCK) {
        val r=registry();val record=r.getJSONObject(id)
        val pack=MapPackage(File(root,record.getString("current")),record.optBoolean("enabled",true))
        record.put("enabled",enabled)
        if(pack.kind=="vector" && !enabled) require(hasActiveVector(r)) { "Keep at least one vector basemap enabled" }
        commit(r)
    }
    fun rollback(id: String): Unit = synchronized(LOCK) {
        val r=registry(); val record=r.getJSONObject(id)
        val old=record.getString("previous")
        val candidate=MapPackage(File(root,old),true)
        validate(candidate.directory,candidate.manifest,candidate.directory.list()!!.toSet())
        record.put("previous",record.getString("current")).put("current",old);commit(r)
    }
    fun remove(id: String): Unit = synchronized(LOCK) {
        require(id !in setOf("willamette-silver-outdoors","gervais-original-parcels")) { "Core maps are retained" }
        val r=registry()
        val record=r.getJSONObject(id)
        val pack=MapPackage(File(root,record.getString("current")),record.optBoolean("enabled",true))
        r.remove(id)
        if(pack.kind=="vector") require(hasActiveVector(r)) { "Enable another vector basemap before deleting this one" }
        // Match manifest IDs, not prefixes: deleting "gervais" must never delete "gervais-original-parcels".
        val versions=root.listFiles()?.filter { dir ->
            dir.isDirectory && runCatching { JSONObject(File(dir,"manifest.json").readText()).getString("id")==id }.getOrDefault(false)
        } ?: emptyList()
        commit(r)
        for(version in versions) check(version.deleteRecursively()) { "Package disabled but some files could not be removed" }
    }
    private fun hasActiveVector(r: JSONObject)=r.keys().asSequence().any { id ->
        val record=r.getJSONObject(id)
        record.optBoolean("enabled",true) && MapPackage(File(root,record.getString("current")),true).kind=="vector"
    }
    fun hasPrevious(id: String)=synchronized(LOCK) { registry().getJSONObject(id).has("previous") }

    private fun validate(dir: File,m: JSONObject,seen: Set<String>, deep: Boolean=true) {
        require(m.getInt("schema_version") in 1..2) { "Unsupported package schema" }
        require(m.getString("id").matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid package ID" }
        require(m.getString("name").length in 1..160 && m.optInt("revision",1)>0)
        require(m.getString("attribution").length in 1..4000)
        java.time.Instant.parse(m.getString("extracted_at")) // Reject ambiguous freshness metadata.
        val b=m.getJSONArray("bbox")
        require(b.length()==4 && (-125.0<=b.getDouble(0) && b.getDouble(0)<b.getDouble(2) && b.getDouble(2)<= -116.0) && (41.0<=b.getDouble(1) && b.getDouble(1)<b.getDouble(3) && b.getDouble(3)<=47.0)) { "Invalid Oregon coverage bounds" }
        val hashes=m.getJSONObject("sha256")
        val files=hashes.keys().asSequence().toSet()
        require(files==seen-"manifest.json" && files.all { it in ALLOWED && it!="manifest.json" }) { "Unlisted or missing payload" }
        for(name in files) {
            val expected=hashes.getString(name)
            require(expected.matches(Regex("[a-f0-9]{64}")) && sha(File(dir,name))==expected) { "Checksum mismatch: $name" }
        }
        val kind=if(m.getInt("schema_version")==1) "legacy" else m.getString("kind")
        require(kind in listOf("legacy","vector","parcels","aerial")) { "Unsupported package kind" }
        if(kind in listOf("legacy","parcels")) {
            require(files.containsAll(listOf("parcels.geojson","source-record-hashes.json","source-metadata.json")))
            if(deep) {
            val data=JSONObject(File(dir,"parcels.geojson").readText()); require(data.getString("type")=="FeatureCollection")
            val features=data.getJSONArray("features")
            require(features.length() in 1..35000 && features.length()==m.getInt("parcel_count"))
            val ids=HashSet<Long>(); var vertices=0
            fun ring(r: JSONArray) {
                require(r.length()>=4)
                val first=r.getJSONArray(0);val last=r.getJSONArray(r.length()-1)
                require(first.getDouble(0)==last.getDouble(0) && first.getDouble(1)==last.getDouble(1))
                for(j in 0 until r.length()) {
                    val point=r.getJSONArray(j);require(point.length()==2 && point.getDouble(0) in -180.0..180.0 && point.getDouble(1) in -90.0..90.0)
                    vertices++; require(vertices<=3000000) { "Too many parcel vertices" }
                }
            }
            fun polygon(p: JSONArray) { require(p.length()>0); for(j in 0 until p.length()) ring(p.getJSONArray(j)) }
            for(i in 0 until features.length()) {
                val f=features.getJSONObject(i);require(f.getString("type")=="Feature")
                require(ids.add(f.getJSONObject("properties").getLong("OBJECTID"))) { "Duplicate parcel ID" }
                val g=f.getJSONObject("geometry");val c=g.getJSONArray("coordinates")
                when(g.getString("type")) { "Polygon" -> polygon(c); "MultiPolygon" -> { require(c.length()>0);for(j in 0 until c.length()) polygon(c.getJSONArray(j)) }; else -> error("Invalid parcel geometry") }
            }
            }
        }
        if(kind=="vector") require(m.getString("tile_schema")=="oregon-outdoor-v1") { "Unsupported outdoor tile schema" }
        if(kind in listOf("legacy","vector","aerial")) {
            val low=m.getInt("minzoom");val high=m.getInt("maxzoom");require(low in 0..high && high<=18)
            val name=if(kind=="vector") "basemap.pmtiles" else "aerial.pmtiles"
            require(name in files)
            RandomAccessFile(File(dir,name),"r").use { f ->
                require(f.length()>=127);val header=ByteArray(127);f.readFully(header)
                require(String(header.copyOfRange(0,7),Charsets.US_ASCII)=="PMTiles" && header[7].toInt()==3) { "Invalid PMTiles v3 header" }
                val h=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for(offset in listOf(8,24,40,56)) { val start=h.getLong(offset);val length=h.getLong(offset+8);require(length>=0 && ((length==0L && start==0L) || (start>=127 && start<=f.length() && length<=f.length()-start))) { "Invalid PMTiles offsets" } }
                require(header[99].toInt()==(if(kind=="vector") 1 else 2)) { "Wrong PMTiles tile type" }
                require(header[100].toInt()==low && header[101].toInt()==high) { "PMTiles zooms disagree with manifest" }
            }
        }
    }
    companion object {
        private val LOCK=Any()
        private const val MAX_TOTAL=512L*1024*1024
        private val ALLOWED=setOf("manifest.json","basemap.pmtiles","aerial.pmtiles","parcels.geojson","source-record-hashes.json","source-metadata.json")
        private fun limit(name: String)=when(name) { "manifest.json" -> 128L*1024; "parcels.geojson" -> 64L*1024*1024; "source-record-hashes.json","source-metadata.json" -> 16L*1024*1024; else -> 384L*1024*1024 }
        fun sha(file: File): String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class MapPackage(val directory: File,val enabled: Boolean) {
    val manifest=JSONObject(File(directory,"manifest.json").readText())
    val id: String get()=manifest.getString("id")
    val kind: String get()=if(manifest.getInt("schema_version")==1) "legacy" else manifest.getString("kind")
    fun contains(lat: Double,lon: Double): Boolean { val b=manifest.getJSONArray("bbox");return lon in b.getDouble(0)..b.getDouble(2) && lat in b.getDouble(1)..b.getDouble(3) }
    fun url(name: String)="pmtiles://file://${File(directory,name).absolutePath}"
    fun parcels()=FeatureCollection.fromJson(File(directory,"parcels.geojson").readText())
}
