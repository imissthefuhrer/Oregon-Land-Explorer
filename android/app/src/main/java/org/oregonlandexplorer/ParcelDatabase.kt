package org.oregonlandexplorer

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.Feature
import java.io.File
import java.util.UUID

/** Short-lived read-only connections; neither geometry nor ownership is cached per region. */
object ParcelDatabase {
    const val APPLICATION_ID = 0x4f4c4550
    const val COUNTY = "41047"
    const val CELL_SCALE=100
    private fun cell(value: Double)=kotlin.math.floor(value*CELL_SCALE).toInt()
    private const val MAX_GEOMETRY = 8 * 1024 * 1024
    private const val COLUMNS = "p.parcel_key,p.properties,p.geometry"
    private const val SPATIAL = " FROM parcel_cells c JOIN parcel_bounds b ON b.id=c.parcel_id JOIN parcels p ON p.id=b.id WHERE c.cell_x=? AND c.cell_y=? AND b.west<=? AND b.east>=? AND b.south<=? AND b.north>=?"

    fun open(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).apply {
        rawQuery("PRAGMA cache_size=-1024",null).use { it.moveToFirst() }
        rawQuery("PRAGMA mmap_size=0",null).use { it.moveToFirst() }
        rawQuery("PRAGMA query_only=ON",null).use { it.moveToFirst() }
    }

    fun validate(file: File, manifest: JSONObject, deep: Boolean) {
        val stable=manifest.optString("identity_strategy")=="taxlot-taxacct-sha256-v1"
        require(manifest.optString("identity_strategy").isEmpty() || stable) { "Unsupported parcel identity strategy" }
        if(stable) require(manifest.getString("source_namespace") in listOf("marion-open","marion-county-owner"))
        open(file).use { db ->
            fun scalar(sql: String)=db.rawQuery(sql,null).use { require(it.moveToFirst());it.getString(0) }
            require(scalar("PRAGMA application_id").toInt()==APPLICATION_ID && scalar("PRAGMA user_version")=="2") { "Unsupported parcel database" }
            require(scalar("SELECT count(*) FROM sqlite_master WHERE type IN ('view','trigger')")=="0") { "Executable database schema is not supported" }
            require(scalar("SELECT count(*) FROM parcels").toInt()==manifest.getInt("parcel_count")) { "Parcel count mismatch" }
            require(scalar("SELECT value FROM metadata WHERE key='cell_scale'")==CELL_SCALE.toString()) { "Unsupported spatial grid" }
            require(scalar("SELECT count(*) FROM parcel_bounds").toInt()==manifest.getInt("parcel_count")) { "Spatial count mismatch" }
            require(scalar("SELECT value FROM metadata WHERE key='extracted_at'")==manifest.getString("extracted_at")) { "Source date mismatch" }
            require(scalar("SELECT value FROM metadata WHERE key='county'")==manifest.getString("county")) { "County mismatch" }
            val legacyIdentity=if(stable) "" else " OR CAST(p.id AS TEXT)!=p.source_id"
            require(scalar("SELECT count(*) FROM parcels p LEFT JOIN parcel_bounds b ON p.id=b.id WHERE b.id IS NULL OR p.parcel_key!=p.county||':'||p.source_id$legacyIdentity OR p.county!='${manifest.getString("county")}' OR length(p.geometry)>$MAX_GEOMETRY")=="0") { "Invalid parcel identities or bounds" }
            require(scalar("SELECT count(*) FROM (SELECT parcel_key FROM parcels GROUP BY parcel_key HAVING count(*)>1)")=="0")
            require(scalar("SELECT count(*) FROM pragma_index_list('parcels') WHERE \"unique\"=1").toInt()>=2) { "Missing identity indexes" }
            if(deep) {
                require(scalar("PRAGMA integrity_check")=="ok") { "Corrupt parcel database" }
                // Stock Android SQLite has no R-Tree module. Validate indexed grid
                // coverage against every exact polygon, without loading the dataset.
                var totalCells=0L
                db.rawQuery("SELECT p.parcel_key,p.properties,p.geometry,b.west,b.south,b.east,b.north,p.id FROM parcels p JOIN parcel_bounds b ON p.id=b.id",null).use { c ->
                    while(c.moveToNext()) {
                        val f=feature(c.getString(0),c.getString(1),c.getString(2))
                        val b=bounds(JSONObject(c.getString(2)))
                        require(c.getDouble(3)<=b[0] && c.getDouble(4)<=b[1] && c.getDouble(5)>=b[2] && c.getDouble(6)>=b[3]) { "Spatial index does not cover exact geometry" }
                        val cells=(cell(b[2])-cell(b[0])+1L)*(cell(b[3])-cell(b[1])+1L)
                        require(cells in 1..50000)
                        totalCells+=cells
                        db.rawQuery("SELECT count(*) FROM parcel_cells WHERE parcel_id=? AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?",
                            arrayOf(c.getString(7),cell(b[0]).toString(),cell(b[2]).toString(),cell(b[1]).toString(),cell(b[3]).toString())).use { index ->
                            require(index.moveToFirst() && index.getLong(0)==cells) { "Spatial grid coverage mismatch" }
                        }
                        require(f.getNumberProperty("OBJECTID").toLong()==c.getLong(7))
                        val expected=if(stable) {
                            val p=JSONObject(c.getString(1))
                            require(p.optString("TAXLOT").isNotEmpty() && p.optString("TAXACCT").isNotEmpty())
                            // Canonical compact JSON matches the ASCII tax identifiers in the hosted builder.
                            val pair=JSONArray().put(p.getString("TAXLOT")).put(p.getString("TAXACCT")).toString()
                            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(pair.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                            COUNTY+":"+manifest.getString("source_namespace")+":"+digest
                        } else COUNTY+":"+c.getString(7)
                        require(c.getString(0)==expected) { "Stable parcel identity does not match source attributes" }
                    }
                }
                require(scalar("SELECT count(*) FROM parcel_cells").toLong()==totalCells) { "Unexpected spatial grid entries" }
            }
        }
    }

    fun feature(key: String, properties: String, geometry: String): Feature {
        require(geometry.length<=MAX_GEOMETRY)
        return Feature.fromJson("""{"type":"Feature","properties":$properties,"geometry":$geometry}""").also { it.addStringProperty("parcel_key",key) }
    }

    fun candidates(pack: MapPackage, lat: Double, lon: Double): List<String> = open(pack.parcelFile).use { db ->
        db.rawQuery("SELECT p.parcel_key"+SPATIAL+" LIMIT 257",arrayOf(cell(lon).toString(),cell(lat).toString(),lon.toString(),lon.toString(),lat.toString(),lat.toString())).use { c ->
            val keys=mutableListOf<String>();while(c.moveToNext()) keys.add(c.getString(0))
            require(keys.size<=256) { "Too many overlapping parcel candidates; zoom in or inspect source data" };keys
        }
    }

    fun lookup(pack: MapPackage, key: String): Feature? = open(pack.parcelFile).use { db ->
        db.rawQuery("SELECT $COLUMNS FROM parcels p WHERE p.parcel_key=?",arrayOf(key)).use { c ->
            if(c.moveToFirst()) feature(c.getString(0),c.getString(1),c.getString(2)).also { f ->
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(c.getString(2).toByteArray(Charsets.UTF_8))
                f.addStringProperty("owner_geometry_binding",digest.joinToString("") { "%02x".format(it) })
            } else null
        }
    }

    /** Legacy display only. Explicit budget failure asks for zoom, never drops parcels silently. */
    fun viewport(pack: MapPackage, b: DoubleArray): List<Feature> = open(pack.parcelFile).use { db ->
        db.rawQuery("SELECT $COLUMNS FROM parcels p JOIN parcel_bounds b ON b.id=p.id WHERE p.id IN (SELECT parcel_id FROM parcel_cells WHERE cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?) AND b.west<=? AND b.east>=? AND b.south<=? AND b.north>=? LIMIT 1001",
            arrayOf(cell(b[0]).toString(),cell(b[2]).toString(),cell(b[1]).toString(),cell(b[3]).toString(),b[2].toString(),b[0].toString(),b[3].toString(),b[1].toString())).use { c ->
            var bytes=0;val rows=mutableListOf<Feature>()
            while(c.moveToNext()) {
                val g=c.getString(2);bytes+=g.length
                require(rows.size<1000 && bytes<=2*1024*1024) { "Parcel view is dense; zoom in or import the updated parcel package" }
                rows.add(feature(c.getString(0),c.getString(1),g))
            };rows
        }
    }

    /** Exact point-in-polygon on the audited display geometry. Holes and MultiPolygons retained. */
    fun contains(feature: Feature, lat: Double, lon: Double): Boolean {
        return containsGeometry(JSONObject(feature.geometry()!!.toJson()),lat,lon)
    }
    fun containsGeometry(g: JSONObject, lat: Double, lon: Double): Boolean {
        val c=g.getJSONArray("coordinates")
        fun ring(r: JSONArray): Int {
            var inside=false
            for(i in 0 until r.length()-1) {
                val a=r.getJSONArray(i);val b=r.getJSONArray(i+1)
                val ax=a.getDouble(0);val ay=a.getDouble(1);val bx=b.getDouble(0);val by=b.getDouble(1)
                val cross=(lon-ax)*(by-ay)-(lat-ay)*(bx-ax)
                if(kotlin.math.abs(cross)<=1e-12*maxOf(kotlin.math.abs(bx-ax),kotlin.math.abs(by-ay)) && lon>=minOf(ax,bx) && lon<=maxOf(ax,bx) && lat>=minOf(ay,by) && lat<=maxOf(ay,by)) return 2
                if((ay>lat)!=(by>lat) && lon<(bx-ax)*(lat-ay)/(by-ay)+ax) inside=!inside
            }
            return if(inside) 1 else 0
        }
        fun polygon(p: JSONArray): Boolean {
            val outer=ring(p.getJSONArray(0));if(outer==0) return false
            for(i in 1 until p.length()) { val hole=ring(p.getJSONArray(i));if(hole==1) return false;if(hole==2) return true }
            return true
        }
        return if(g.getString("type")=="Polygon") polygon(c) else (0 until c.length()).any { polygon(c.getJSONArray(it)) }
    }

    fun bounds(g: JSONObject): DoubleArray {
        val result=doubleArrayOf(180.0,90.0,-180.0,-90.0);var vertices=0
        fun ring(r: JSONArray) {
            require(r.length()>=4)
            require(r.getJSONArray(0).toString()==r.getJSONArray(r.length()-1).toString()) { "Unclosed parcel ring" }
            for(i in 0 until r.length()) {
                val p=r.getJSONArray(i);require(p.length()==2)
                val x=p.getDouble(0);val y=p.getDouble(1)
                require(x.isFinite() && y.isFinite() && x in -180.0..180.0 && y in -85.0..85.0 && ++vertices<=500000)
                result[0]=minOf(result[0],x);result[1]=minOf(result[1],y);result[2]=maxOf(result[2],x);result[3]=maxOf(result[3],y)
            }
        }
        fun polygon(p: JSONArray) { require(p.length()>0);for(i in 0 until p.length()) ring(p.getJSONArray(i)) }
        val coordinates=g.getJSONArray("coordinates")
        when(g.getString("type")) {
            "Polygon" -> polygon(coordinates)
            "MultiPolygon" -> { require(coordinates.length()>0);for(i in 0 until coordinates.length()) polygon(coordinates.getJSONArray(i)) }
            else -> error("Invalid parcel geometry")
        };return result
    }

    /** Stream one feature at a time into a version-specific sidecar; keep old package bytes. */
    fun prepareLegacy(pack: MapPackage, cache: File): File {
        cache.mkdirs()
        val target=File(cache,PackageStore.sha(File(pack.directory,"manifest.json"))+".sqlite")
        if(target.exists()) return target
        val stage=File(cache,".staging-${UUID.randomUUID()}.sqlite")
        try {
            SQLiteDatabase.openOrCreateDatabase(stage,null).use { db ->
                db.rawQuery("PRAGMA journal_mode=DELETE",null).use { it.moveToFirst() }
                db.execSQL("CREATE TABLE parcels(id INTEGER PRIMARY KEY,parcel_key TEXT NOT NULL UNIQUE,properties TEXT NOT NULL,geometry TEXT NOT NULL)")
                db.execSQL("CREATE TABLE parcel_bounds(id INTEGER PRIMARY KEY,west REAL,east REAL,south REAL,north REAL)")
                db.execSQL("CREATE TABLE parcel_cells(cell_x INTEGER,cell_y INTEGER,parcel_id INTEGER,PRIMARY KEY(cell_x,cell_y,parcel_id)) WITHOUT ROWID")
                db.execSQL("CREATE INDEX parcel_cell_id ON parcel_cells(parcel_id,cell_x,cell_y)")
                db.beginTransaction()
                try {
                    var count=0;var collection=false
                    JsonReader(File(pack.directory,"parcels.geojson").reader()).use { reader ->
                        reader.beginObject()
                        while(reader.hasNext()) when(reader.nextName()) {
                            "type" -> require(reader.nextString()=="FeatureCollection")
                            "features" -> {
                                require(!collection);collection=true;reader.beginArray()
                                while(reader.hasNext()) {
                                    val f=readValue(reader,0,intArrayOf(0)) as JSONObject
                                    require(f.getString("type")=="Feature")
                                    val props=f.getJSONObject("properties");val g=f.getJSONObject("geometry")
                                    val b=bounds(g);val id=props.getLong("OBJECTID")
                                    val geometry=g.toString();require(geometry.length<=MAX_GEOMETRY)
                                    db.execSQL("INSERT INTO parcels VALUES (?,?,?,?)",arrayOf(id,"$COUNTY:$id",props.toString(),geometry))
                                    db.execSQL("INSERT INTO parcel_bounds VALUES (?,?,?,?,?)",arrayOf(id,b[0],b[2],b[1],b[3]))
                                    require((cell(b[2])-cell(b[0])+1L)*(cell(b[3])-cell(b[1])+1L)<=50000) { "Parcel exceeds spatial grid budget" }
                                    for(x in cell(b[0])..cell(b[2])) for(y in cell(b[1])..cell(b[3]))
                                        db.execSQL("INSERT INTO parcel_cells VALUES (?,?,?)",arrayOf(x,y,id))
                                    require(++count<=35000)
                                };reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                        reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT)
                    }
                    require(collection && count==pack.manifest.getInt("parcel_count")) { "Legacy parcel count mismatch" }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            java.io.RandomAccessFile(stage,"rw").use { it.fd.sync() }
            check(stage.renameTo(target)) { "Cannot activate legacy parcel index" }
            return target
        } finally { stage.delete();File(stage.path+"-journal").delete() }
    }

    private fun readValue(reader: JsonReader, depth: Int, budget: IntArray): Any {
        require(++budget[0]<=600000) { "Parcel decode budget exceeded" }
        require(depth<16) { "Excessively nested parcel data" }
        return when(reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().also { obj ->
                reader.beginObject();while(reader.hasNext()) { val name=reader.nextName();require(!obj.has(name));obj.put(name,readValue(reader,depth+1,budget)) };reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().also { array -> reader.beginArray();while(reader.hasNext()) { require(array.length()<500000);array.put(readValue(reader,depth+1,budget)) };reader.endArray() }
            JsonToken.NULL -> { reader.nextNull();JSONObject.NULL }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> { val s=reader.nextString();s.toLongOrNull() ?: s.toDouble().also { require(it.isFinite()) } }
            JsonToken.STRING -> reader.nextString().also { require(it.length<=65536) }
            else -> error("Malformed parcel JSON")
        }
    }
}
