package org.oregonlandexplorer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class MainActivity : AppCompatActivity(), LocationListener {
    lateinit var mapView: MapView
    var map: MapLibreMap? = null
    var region: Region? = null
    @Volatile var ready = false
    private lateinit var status: TextView
    private lateinit var locationStatus: TextView
    private var parcelVisible = true
    private var aerialVisible = false
    private var importing = false
    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if(uri!=null) importDocument(uri)
    }
    private var fix: Location? = null
    private var recenterOnFix = false
    private var networkRegistered = false
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { runOnUiThread { updateStatus() } }
        override fun onLost(network: Network) { runOnUiThread { updateStatus() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { runOnUiThread { updateStatus() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(22,59,44)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "Oregon Land Explorer"; textSize = 22f; setTextColor(Color.WHITE); setPadding(16,12,16,8)
        })
        status = TextView(this).apply { text = "Checking installed map…"; setTextColor(Color.WHITE); setPadding(16,4,16,8); textSize = 14f }
        root.addView(status)
        mapView = MapView(this)
        mapView.onCreate(savedInstanceState)
        root.addView(mapView, LinearLayout.LayoutParams(-1,0,1f))
        locationStatus = TextView(this).apply { text = "GPS off · Tap GPS to enable"; setTextColor(Color.WHITE); setPadding(16,8,16,4) }
        root.addView(TextView(this).apply { text="© OpenStreetMap contributors · ODbL · Sources: Info"; textSize=11f; setTextColor(Color.WHITE); setPadding(12,3,12,0); setOnClickListener { showInfo() } })
        root.addView(locationStatus)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text=label; textSize=12f; setOnClickListener { action() } }, LinearLayout.LayoutParams(0,56.dp,1f))
        }
        button("GPS") { requestGps() }
        button("Region") { showRegions() }
        button("Parcels") { toggleParcels() }
        button("Info") { showInfo() }
        root.addView(controls)
        setContentView(root)
        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isLogoEnabled = false
            loaded.uiSettings.isAttributionEnabled = false // Full source attribution lives in the Info sheet.
            loaded.setMinZoomPreference(8.0)
            loaded.setMaxZoomPreference(19.0)
            loaded.addOnCameraIdleListener { updateStatus() }
            loaded.addOnMapClickListener { point -> selectParcel(point) }
            Thread {
                try {
                    val installed = Region(this)
                    runOnUiThread { region = installed; loadMap(installed) }
                } catch (error: Exception) {
                    runOnUiThread { status.text = "Map installation failed: ${error.message}" }
                }
            }.start()
        }
    }

    private fun loadMap(installed: Region, resetCamera: Boolean = true) {
        ready=false
        val camera=map?.cameraPosition
        map?.setMinZoomPreference(installed.manifest.getInt("minzoom").toDouble())
        map?.setMaxZoomPreference(19.0)
        map?.setStyle(Style.Builder().fromJson(installed.style(aerialVisible))) { style ->
            style.addSource(GeoJsonSource("parcels", installed.parcels))
            style.addLayer(FillLayer("parcel-fill", "parcels").withProperties(fillColor("#ffb547"), fillOpacity(0.025f)))
            style.addLayer(LineLayer("parcel-line", "parcels").withProperties(lineColor("#b27a25"), lineWidth(1.1f)))
            style.addSource(GeoJsonSource("selection", FeatureCollection.fromFeatures(arrayOf<Feature>())))
            style.addLayer(LineLayer("selected-line", "selection").withProperties(lineColor("#ffffff"), lineWidth(4f)))
            style.addSource(GeoJsonSource("gps", FeatureCollection.fromFeatures(arrayOf<Feature>())))
            style.addLayer(CircleLayer("gps-dot", "gps").withProperties(circleColor("#298cff"),circleRadius(8f),circleStrokeColor("#ffffff"),circleStrokeWidth(3f)))
            for(name in listOf("parcel-fill","parcel-line","selected-line")) style.getLayer(name)?.setProperties(visibility(if(parcelVisible) Property.VISIBLE else Property.NONE))
            fix?.let { onLocationChanged(it) }
            ready = true
            if(resetCamera) home() else camera?.let { map?.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            updateStatus()
        }
    }

    fun selectParcel(point: LatLng): Boolean {
        val installed = region ?: return false
        if (!parcelVisible || !installed.containsParcel(point.latitude, point.longitude)) return false
        val features = map?.queryRenderedFeatures(map!!.projection.toScreenLocation(point), "parcel-fill") ?: return false
        if (features.isEmpty()) return false
        // Shared/overlapping tax lots are not silently collapsed into one owner.
        if (features.size > 1) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Overlapping parcels")
                .setItems(features.map { "Tax lot ${value(it,"TAXLOT")}" }.toTypedArray()) { _, index -> showParcel(features[index]) }.show()
        } else showParcel(features.first())
        return true
    }
    private fun value(feature: Feature, key: String): String =
        feature.getProperty(key)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() } ?: "Not available"

    private fun showParcel(feature: Feature) {
        map?.style?.getSourceAs<GeoJsonSource>("selection")?.setGeoJson(feature)
        val repair=feature.getProperty("geometry_repair_note")?.takeUnless { it.isJsonNull }?.asString?.let { "\n\n$it" } ?: ""
        sheet("Property details", "Owner: ${value(feature,"OWNERNAME")}\n\nTax lot: ${value(feature,"TAXLOT")}\nAcres: ${value(feature,"ACRES")}\n\nOwnership classification: Unknown\nLegal public access: Unknown\n\nMarion County assessor-derived record. Boundaries are approximate, not a survey. Ownership does not establish permission to enter.$repair")
    }
    private fun sheet(title: String, body: String) {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24.dp,20.dp,24.dp,32.dp) }
        content.addView(TextView(this).apply { text=title; textSize=23f; setPadding(0,0,0,16.dp) })
        content.addView(TextView(this).apply { text=body; textSize=16f; setTextIsSelectable(true) })
        content.addView(Button(this).apply { text="Close"; setOnClickListener { dialog.dismiss() } })
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.show()
    }
    private fun showInfo() {
        val installed=region ?: return
        val records=installed.packages.joinToString("\n\n") { p ->
            val m=p.manifest
            "${if(p.enabled) "Enabled" else "Disabled"}: ${m.getString("name")}\n${p.kind} · revision ${m.optInt("revision",1)} · ${"%.2f".format(p.directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }/1048576.0)} MiB\nBounds W,S,E,N: ${m.getJSONArray("bbox")}\nExtracted: ${m.optString("extracted_at")}\n${m.getString("attribution")}"
        }
        sheet("Installed map data", "$records\n\n${installed.parcels.features()?.size ?: 0} unique selectable parcels.\n\n© OpenStreetMap contributors. Open data under ODbL 1.0: https://www.openstreetmap.org/copyright · https://opendatacommons.org/licenses/odbl/1-0/\nOutdoor data: Geofabrik Oregon extract, compiled with Tilemaker. Fonts: Noto Sans (SIL OFL 1.1). MapLibre Native (BSD-2-Clause).\n\nRoads are light; trails are rust dashes; forest tracks are brown dashes; mapped access=no/private is overprinted purple. These tags are not proof of access. Confirm restrictions and current closures locally.\n\nParcel source: https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0\nApproximate assessor-derived boundaries, not surveys; source vintage and completeness are not independently verified. Ownership and legal public access remain Unknown.\n\nAll map data, labels and fonts are local. No internet permission. Vector geometry is stored through zoom 15 and magnified above it; aerial through zoom 17. Home coverage must be checked privately using GPS.")
    }
    private fun showRegions() {
        val items=arrayOf("Return to corridor", "Gervais countryside", "St. Louis Ponds", "Willamette Mission State Park", "Silver Falls State Park", "Import .olepack from Downloads", "Manage installed packages", if(aerialVisible) "Use outdoor basemap" else "Show installed optional aerial")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Offline regions").setItems(items) { _, choice ->
            when(choice) {
                0 -> home()
                1 -> go(45.108,-122.897,13.0)
                2 -> go(45.114,-122.932,15.0)
                3 -> go(45.085,-123.045,14.0)
                4 -> go(44.89,-122.63,13.0)
                5 -> if(!importing) importer.launch(arrayOf("*/*"))
                6 -> managePackages()
                7 -> {
                    if(!aerialVisible && region?.packages?.none { it.enabled && it.kind in listOf("aerial","legacy") }==true) {
                        sheet("Optional aerial", "Download and import gervais-original-aerial.olepack, then select this option. Outdoor coverage remains available outside installed aerial regions.")
                    } else { aerialVisible=!aerialVisible; region?.let { loadMap(it,false) } }
                }
            }
        }.show()
    }
    private fun go(lat: Double,lon: Double,zoom: Double) { map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat,lon),zoom)) }
    private fun managePackages() {
        val installed=region ?: return
        val packs=installed.packages
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Installed packages")
            .setItems(packs.map { "${if(it.enabled) "✓" else "○"} ${it.manifest.getString("name")}" }.toTypedArray()) { _, index ->
                val pack=packs[index];val actions=mutableListOf(if(pack.enabled) "Disable" else "Enable", "Show coverage")
                if(installed.store.hasPrevious(pack.id)) actions.add("Restore previous version")
                if(pack.id !in setOf("willamette-silver-outdoors","gervais-original-parcels")) actions.add("Delete downloaded package")
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(pack.manifest.getString("name"))
                    .setItems(actions.toTypedArray()) { _, action ->
                        when(actions[action]) {
                            "Disable", "Enable" -> {
                                if(pack.enabled && pack.kind=="vector" && packs.count { it.enabled && it.kind=="vector" }==1) {
                                    Toast.makeText(this,"Keep at least one vector basemap enabled",Toast.LENGTH_LONG).show()
                                } else reloadPackages { installed.store.setEnabled(pack.id,!pack.enabled) }
                            }
                            "Show coverage" -> { val b=pack.manifest.getJSONArray("bbox");go((b.getDouble(1)+b.getDouble(3))/2,(b.getDouble(0)+b.getDouble(2))/2,13.0) }
                            "Restore previous version" -> reloadPackages { installed.store.rollback(pack.id) }
                            "Delete downloaded package" -> androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Delete downloaded package?").setMessage("Delete ${pack.manifest.getString("name")} and its retained versions from this app? You can import it again later.")
                                .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _, _ -> reloadPackages { installed.store.remove(pack.id) } }.show()
                        }
                    }.show()
            }.show()
    }
    private fun reloadPackages(action: () -> Unit = {}) {
        if(importing) return
        importing=true
        Thread {
            try {
                action()
                val updated=Region(this)
                runOnUiThread { region=updated;importing=false;loadMap(updated,false) }
            } catch(e: Exception) {
                runOnUiThread { importing=false;sheet("Package operation failed", "${e.message}\nThe previous map remains available.") }
            }
        }.start()
    }
    private fun importDocument(uri: Uri) {
        reloadPackages {
            val store=region?.store ?: PackageStore(this)
            contentResolver.openInputStream(uri)?.use { store.importPackage(it) } ?: error("Cannot open selected document")
        }
    }
    fun home() {
        val b=region?.manifest?.getJSONArray("bbox") ?: return
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng((b.getDouble(1)+b.getDouble(3))/2,(b.getDouble(0)+b.getDouble(2))/2),11.0))
    }
    private fun toggleParcels() {
        parcelVisible = !parcelVisible
        for (name in listOf("parcel-fill","parcel-line","selected-line")) map?.style?.getLayer(name)?.setProperties(visibility(if(parcelVisible) Property.VISIBLE else Property.NONE))
        Toast.makeText(this, if(parcelVisible) "Parcels visible" else "Parcels hidden", Toast.LENGTH_SHORT).show()
    }
    private fun updateStatus() {
        if (!::status.isInitialized || !ready) return
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val target=map?.cameraPosition?.target
        val bounds=map?.projection?.visibleRegion?.latLngBounds
        val installed=region ?: return
        val regionLabel=installed.manifest.getString("name")
        val fullyInside = bounds != null && installed.contains(bounds.latitudeNorth,bounds.longitudeEast) && installed.contains(bounds.latitudeSouth,bounds.longitudeWest)
        val detail=if(target!=null && !installed.containsParcel(target.latitude,target.longitude)) " · parcels not installed here" else ""
        val aerial=if(aerialVisible && target!=null && !installed.containsAerial(target.latitude,target.longitude)) " · aerial not installed here" else ""
        status.text = (if(online) "Online · " else "Offline · ") + when {
            target == null || !installed.contains(target.latitude,target.longitude) -> "Area not downloaded · tap Region"
            !fullyInside -> "Local $regionLabel · view extends beyond download"
            else -> "Local $regionLabel · ready"
        } + detail + aerial
    }
    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),1)
        } else {
            recenterOnFix=true
            startGps()
            fix?.let { if ((android.os.SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos)/1e9 < 30) recenter(it) }
        }
    }
    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val precise=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            val provider=if(precise) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            if (!locations.isProviderEnabled(provider)) { locationStatus.text="Location provider off · enable Android Location"; return }
            locations.requestLocationUpdates(provider,2000L,2f,this,Looper.getMainLooper())
            locationStatus.text=if(precise) "Waiting for GPS fix…" else "Approximate location only · precise GPS recommended offline"
        } catch(error: Exception) { locationStatus.text="GPS unavailable: ${error.message}" }
    }
    private fun recenter(location: Location) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude,location.longitude),17.0))
        recenterOnFix=false
    }
    override fun onLocationChanged(location: Location) {
        fix=location
        map?.style?.getSourceAs<GeoJsonSource>("gps")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(location.longitude,location.latitude)))
        locationStatus.text="GPS ±${location.accuracy.toInt()} m" + if(region?.contains(location.latitude,location.longitude)==true) "" else " · location outside downloaded region"
        if(recenterOnFix) recenter(location)
    }
    override fun onProviderDisabled(provider: String) { locationStatus.text="Location provider disabled" }
    override fun onProviderEnabled(provider: String) { locationStatus.text="Waiting for GPS fix…" }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==1 && grantResults.any { it==PackageManager.PERMISSION_GRANTED }) { recenterOnFix=true; startGps() }
        else locationStatus.text="Location permission denied · map still works offline"
    }
    override fun onStart() { super.onStart(); mapView.onStart(); connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
    override fun onResume() { super.onResume(); mapView.onResume(); startGps() }
    override fun onPause() { locations.removeUpdates(this); mapView.onPause(); super.onPause() }
    override fun onStop() { if(networkRegistered) { connectivity.unregisterNetworkCallback(networkCallback); networkRegistered=false }; mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    private val Int.dp get() = (this*resources.displayMetrics.density).toInt()
}
