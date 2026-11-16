package org.oregonlandexplorer

import org.json.JSONObject
import org.maplibre.geojson.Feature
import java.io.File

/** Attribute-only, indexed private ownership. Never caches a county of names or geometries. */
object OwnerDatabase {
    private val statuses=setOf("matched","missing","null","conflict","stale","parcel_changed")
    fun observed(m: JSONObject)=m.optString("owner_schema")=="oregon-owners-v2"
    fun orderingDate(m: JSONObject): java.time.Instant=java.time.Instant.parse(if(observed(m)) m.getString("retrieved_utc") else m.getString("source_data_as_of"))
    fun dateDescription(m: JSONObject)=if(observed(m)) "Downloaded ${m.getString("retrieved_utc")} · ownership snapshot date unknown" else "Package-declared date ${m.getString("source_data_as_of")} · freshness not independently verified"
    fun validate(file: File,m: JSONObject,deep: Boolean) {
        val observed=observed(m)
        require(m.getString("owner_schema") in listOf("oregon-owners-v1","oregon-owners-v2") && m.getString("county")=="41047") { "This ownership format needs a newer app. Install the APK supplied with the county ZIP." }
        require(m.getString("base_namespace")=="marion-open" && m.getString("identity_strategy")=="county-taxlot-taxacct-v1")
        require(m.getString("source_namespace")=="marion-owner-overlay")
        if(observed) {
            require(m.getString("source_snapshot_status")=="unknown" && m.isNull("source_data_as_of") && m.isNull("source_updated_at")) { "Download time must not be described as an ownership snapshot" }
            java.time.Instant.parse(m.getString("retrieved_utc"))
            require(m.getString("use_basis").isNotBlank())
        } else {
            require(m.getString("authorization_reference").isNotBlank())
            java.time.Instant.parse(m.getString("source_data_as_of"))
            require(m.getString("source_updated_at")==m.getString("source_data_as_of"))
        }
        require(m.getInt("parcel_count") in 1..250000 && m.getInt("owner_count") in 0..m.getInt("parcel_count"))
        ParcelDatabase.open(file).use { db ->
            fun scalar(sql: String)=db.rawQuery(sql,null).use { require(it.moveToFirst());it.getString(0) }
            require(scalar("PRAGMA application_id").toInt()==0x4f4c454f && scalar("PRAGMA user_version")==if(observed) "2" else "1") { "Unsupported ownership database" }
            require(scalar("SELECT count(*) FROM sqlite_master WHERE type IN ('trigger','view')")=="0")
            if(observed) require(scalar("SELECT value FROM metadata WHERE key='retrieved_utc'")==m.getString("retrieved_utc"))
            else require(scalar("SELECT value FROM metadata WHERE key='source_data_as_of'")==m.getString("source_data_as_of"))
            require(scalar("SELECT count(*) FROM owners").toInt()==m.getInt("parcel_count"))
            require(scalar("SELECT count(*) FROM owners WHERE status='matched'").toInt()==m.getInt("owner_count"))
            require(scalar("SELECT count(*) FROM owners WHERE county IS NULL OR taxlot IS NULL OR taxacct IS NULL OR county!='41047' OR length(trim(taxlot)) NOT BETWEEN 1 AND 128 OR length(trim(taxacct)) NOT BETWEEN 1 AND 128 OR length(note)>4096 OR length(owner)>1024 OR length(reported_owner)>1024")=="0")
            require(scalar("SELECT count(*) FROM pragma_index_list('owners') WHERE \"unique\"=1").toInt()>=1)
            val primary=mutableListOf<Pair<Int,String>>()
            db.rawQuery("PRAGMA table_info('owners')",null).use { c ->
                while(c.moveToNext()) if(c.getInt(5)>0) primary.add(c.getInt(5) to c.getString(1))
            }
            require(primary.sortedBy { it.first }.map { it.second }==listOf("county","taxlot","taxacct")) { "Ownership lookup requires the exact composite primary key" }
            if(deep) {
                require(scalar("PRAGMA integrity_check")=="ok")
                db.rawQuery("SELECT status,owner,reported_owner,geometry_sha256,record_sha256,owner_as_of,"+(if(observed) "source_lastupdate" else "NULL")+" FROM owners",null).use { c ->
                    while(c.moveToNext()) {
                        val status=c.getString(0);require(status in statuses)
                        if(m.optBoolean("test_data") && !c.isNull(2)) require(c.getString(2).startsWith("SYNTHETIC TEST ONLY "))
                        require(c.getString(3).matches(Regex("[a-f0-9]{64}")) && c.getString(4).matches(Regex("[a-f0-9]{64}")))
                        if(observed) {
                            require(c.isNull(5)) { "Unknown ownership snapshot must stay unknown" }
                            if(!c.isNull(6)) java.time.Instant.parse(c.getString(6))
                        } else {
                            val asOf=java.time.Instant.parse(c.getString(5))
                            require(!asOf.isAfter(java.time.Instant.parse(m.getString("source_data_as_of"))))
                        }
                        if(status=="matched") {
                            require(!c.isNull(1) && c.getString(1).isNotBlank() && c.getString(1)==c.getString(2))
                            if(m.optBoolean("test_data")) require(c.getString(1).startsWith("SYNTHETIC TEST ONLY "))
                        } else require(c.isNull(1)) { "Unvalidated ownership must not populate owner name" }
                    }
                }
            }
        }
    }

    /** Latest explicit null/conflict masks older owners. Never fall through to stale data. */
    fun enrich(feature: Feature,packages: List<MapPackage>) {
        val key=feature.getProperty("parcel_key")?.asString ?: return
        if(!key.startsWith("41047:marion-open:")) return // preserve original source ownership
        // Never retain a previous lookup's name if identity or installed packages changed.
        for(name in listOf("OWNERNAME","REPORTED_OWNERNAME","owner_source","owner_data_as_of","owner_retrieved_utc","owner_data_status","owner_match_status")) feature.removeProperty(name)
        val lot=feature.getProperty("TAXLOT")?.takeUnless { it.isJsonNull }?.asString?.trim() ?: return
        val account=feature.getProperty("TAXACCT")?.takeUnless { it.isJsonNull }?.asString?.trim() ?: return
        for(pack in packages.sortedWith(compareByDescending<MapPackage> { orderingDate(it.manifest) }.thenByDescending { it.manifest.getInt("revision") }.thenBy { it.id })) {
            val observed=observed(pack.manifest)
            val found=ParcelDatabase.open(File(pack.directory,"owners.sqlite")).use { db ->
                db.rawQuery("SELECT geometry_sha256,status,owner,reported_owner,owner_as_of,note,"+(if(observed) "source_lastupdate" else "NULL")+" FROM owners WHERE county=? COLLATE BINARY AND taxlot=? COLLATE BINARY AND taxacct=? COLLATE BINARY",arrayOf("41047",lot,account)).use cursor@ { c ->
                    if(!c.moveToFirst()) return@cursor false
                    feature.removeProperty("OWNERNAME");feature.removeProperty("REPORTED_OWNERNAME")
                    val binding=feature.getProperty("owner_geometry_binding")?.asString
                    val matches=binding==c.getString(0)
                    val updated=feature.getProperty("LASTUPDATE")?.takeUnless { it.isJsonNull }?.asString
                    val parcelDate=runCatching {
                        if(updated!=null && updated.matches(Regex("[0-9]{13}"))) java.time.Instant.ofEpochMilli(updated.toLong())
                        else java.time.Instant.parse(updated)
                    }.getOrNull()
                    val status=when {
                        !matches -> "geometry_changed"
                        observed && c.getString(1)=="matched" && parcelDate!=null && !c.isNull(6) && parcelDate.isAfter(java.time.Instant.parse(c.getString(6))) -> "newer_parcel_update"
                        !observed && c.getString(1)=="matched" && parcelDate==null -> "date_unverified"
                        !observed && c.getString(1)=="matched" && parcelDate!!.isAfter(java.time.Instant.parse(c.getString(4))) -> "newer_parcel_update"
                        else -> c.getString(1)
                    }
                    if(status=="matched") feature.addStringProperty("OWNERNAME",c.getString(2))
                    else if(matches && !c.isNull(3) && status in listOf("stale","parcel_changed","newer_parcel_update","date_unverified")) feature.addStringProperty("REPORTED_OWNERNAME",c.getString(3))
                    val message=when(status) {
                        "matched" -> if(observed) "Matched by county, tax lot and account. Downloaded ${pack.manifest.getString("retrieved_utc")}. Ownership snapshot date unknown."+(if(c.isNull(6)) " GIS maintenance date unavailable." else " GIS field maintenance: ${c.getString(6)} (not an ownership date).") else "Matched by county, tax lot and account; package-declared date ${c.getString(4)}. Freshness not independently verified."
                        "geometry_changed" -> "Parcel geometry changed since ownership matching; update the private ownership package"
                        "newer_parcel_update" -> "Parcel has a newer update than this ownership record; verify the reported historical name"
                        "date_unverified" -> "Parcel update date is unavailable; the reported owner cannot be validated against this snapshot"
                        else -> c.getString(5)
                    }
                    feature.addStringProperty("owner_data_status",(if(pack.manifest.optBoolean("test_data")) "SYNTHETIC TEST DATA — not real ownership. " else "")+message)
                    feature.addStringProperty("owner_match_status",status)
                    feature.addStringProperty("owner_source",pack.manifest.getString("attribution"))
                    if(!c.isNull(4)) feature.addStringProperty("owner_data_as_of",c.getString(4))
                    if(observed) feature.addStringProperty("owner_retrieved_utc",pack.manifest.getString("retrieved_utc"))
                    true
                }
            }
            if(found) return
        }
        feature.addStringProperty("owner_data_status","Private ownership record not installed or not matched for this county/tax-lot/account. Ownership remains unknown.")
        feature.addStringProperty("owner_match_status","not_installed")
    }
}
