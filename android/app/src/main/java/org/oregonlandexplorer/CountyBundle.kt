package org.oregonlandexplorer

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

/** Complete county ZIP, including APK/instructions. APK bytes are checked, never executed. */
class CountyBundle private constructor(private val stage: File,val manifest: JSONObject,val packages: List<File>): Closeable {
    override fun close() { stage.deleteRecursively() }
    fun validateInstalled(registry: JSONObject,root: File) {
        val descriptions=manifest.getJSONArray("packages")
        var parcels=0;var owners=0
        for(i in 0 until descriptions.length()) {
            val d=descriptions.getJSONObject(i);val m=MapPackage(File(root,registry.getJSONObject(d.getString("id")).getString("current")),true).manifest
            require(m.getString("kind")==d.getString("kind")) { "Bundle package kind mismatch" }
            if(m.getString("kind")=="parcels") {require(m.optString("source_namespace")=="marion-open");parcels+=m.getInt("parcel_count")}
            if(m.getString("kind")=="owners") {require(!m.optBoolean("test_data") && OwnerDatabase.observed(m));owners+=m.getInt("owner_count")}
        }
        require(parcels==115870 && owners==115870) { "Complete county bundle must contain all 115,870 parcels and ownership records" }
    }
    companion object {
        private const val LIMIT=512L*1024*1024
        fun open(context: Context,file: File,progress: (String)->Unit): CountyBundle {
            val stage=File(context.cacheDir,"county-bundle-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                ZipFile(file).use { zip ->
                    val entries=zip.entries().asSequence().toList()
                    require(entries.size in 20..24 && entries.none { it.isDirectory } && entries.map { it.name }.toSet().size==entries.size) { "Unexpected, duplicate or incomplete county ZIP entries" }
                    val header=zip.getEntry("bundle.json") ?: error("Missing bundle.json")
                    require(header.size in 1..131072)
                    val m=JSONObject(zip.getInputStream(header).bufferedReader().use { it.readText() })
                    require(m.getInt("schema_version")==1 && m.getString("id")=="marion-county-complete" && m.getString("county")=="41047") { "Unsupported county bundle" }
                    require(m.getInt("parcel_count")==115870 && m.getInt("owner_count")==115870)
                    val descriptions=m.getJSONArray("packages");require(descriptions.length()==18) { "County ZIP needs 18 map packages: basemap, 16 parcel regions and owners" }
                    val catalog=JSONObject(context.assets.open("county-catalog.json").bufferedReader().use { it.readText() }).getJSONArray("packages")
                    val expectedIds=(0 until catalog.length()).map { catalog.getJSONObject(it).getString("id") }.toSet()+setOf("marion-outdoors","marion-private-owners")
                    require((0 until descriptions.length()).map { descriptions.getJSONObject(it).getString("id") }.toSet()==expectedIds)
                    val files=linkedMapOf<String,Pair<JSONObject,Boolean>>()
                    var installed=0L
                    for(i in 0 until descriptions.length()) {
                        val d=descriptions.getJSONObject(i);val path=d.getString("path")
                        require(path=="maps/${d.getString("id")}.olepack")
                        require(files.put(path,d to true)==null)
                        installed+=d.getLong("installed_bytes");require(installed in 1..LIMIT)
                    }
                    val extras=m.getJSONArray("extras");require(extras.length()==2)
                    for(i in 0 until extras.length()) {
                        val d=extras.getJSONObject(i);val path=d.getString("path")
                        require(path=="INSTALL.txt" || path.matches(Regex("Oregon-Land-Explorer-v[0-9.]+\\.apk")))
                        require(files.put(path,d to false)==null)
                    }
                    require(entries.map { it.name }.toSet()==files.keys+setOf("bundle.json","SHA256SUMS.txt")) { "Unlisted or missing files in county ZIP" }
                    require(zip.getEntry("SHA256SUMS.txt").size in 1..16384)
                    val required=file.length()+installed+64L*1024*1024
                    require(StatFs(stage.path).availableBytes>=required) { "Not enough free space. Keep at least ${required/1048576+1} MiB free for safe staging; existing maps were preserved." }
                    val packs=mutableListOf<File>();var total=header.size
                    for((path,description) in files) {
                        val (d,isPack)=description;val entry=zip.getEntry(path) ?: error("Missing $path")
                        val size=d.getLong("bytes");require(size in 1..384L*1024*1024 && entry.size==size)
                        val expected=d.getString("sha256");require(expected.matches(Regex("[a-f0-9]{64}")))
                        progress(if(isPack) "Checking ${packs.size+1}/18: ${File(path).name}" else "Checking ${File(path).name}")
                        val digest=MessageDigest.getInstance("SHA-256");var copied=0L
                        val target=if(isPack) File(stage,File(path).name) else null
                        val output=target?.outputStream()
                        try { zip.getInputStream(entry).use { input ->
                            val buffer=ByteArray(65536)
                            while(true) {val n=input.read(buffer);if(n<0) break;copied+=n;total+=n;require(copied<=size && total<=LIMIT);digest.update(buffer,0,n);output?.write(buffer,0,n)}
                        } } finally { output?.close() }
                        require(copied==size && digest.digest().joinToString("") { "%02x".format(it) }==expected) { "County ZIP checksum mismatch: ${File(path).name}" }
                        if(target!=null) packs.add(target)
                    }
                    return CountyBundle(stage,m,packs)
                }
            } catch(e: Exception) {stage.deleteRecursively();throw e}
        }
    }
}
