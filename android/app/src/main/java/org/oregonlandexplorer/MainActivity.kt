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
        root.addView(locationStatus)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun button(label: String, action: () -> Unit) {
            controls.addView(Button(this).apply { text=label; textSize=12f; setOnClickListener { action() } }, LinearLayout.LayoutParams(0,56.dp,1f))
        }
        button("GPS") { requestGps() }
        button("Region") { home() }
        button("Parcels") { toggleParcels() }
        button("Info") { showInfo() }
        root.addView(controls)
        setContentView(root)
        mapView.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isLogoEnabled = false
            loaded.uiSettings.isAttributionEnabled = false // Full source attribution lives in the Info sheet.
            loaded.setMinZoomPreference(12.0)
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

    private fun loadMap(installed: Region) {
        map?.setStyle(Style.Builder().fromJson(installed.style())) { style ->
            style.addSource(GeoJsonSource("parcels", installed.parcels))
            style.addLayer(FillLayer("parcel-fill", "parcels").withProperties(fillColor("#ffb547"), fillOpacity(0.08f)))
            style.addLayer(LineLayer("parcel-line", "parcels").withProperties(lineColor("#ffb547"), lineWidth(1.6f)))
            style.addSource(GeoJsonSource("selection", FeatureCollection.fromFeatures(arrayOf<Feature>())))
            style.addLayer(LineLayer("selected-line", "selection").withProperties(lineColor("#ffffff"), lineWidth(4f)))
            style.addSource(GeoJsonSource("gps", FeatureCollection.fromFeatures(arrayOf<Feature>())))
            style.addLayer(CircleLayer("gps-dot", "gps").withProperties(circleColor("#298cff"),circleRadius(8f),circleStrokeColor("#ffffff"),circleStrokeWidth(3f)))
            ready = true
            home()
            updateStatus()
        }
    }

    fun selectParcel(point: LatLng): Boolean {
        val installed = region ?: return false
        if (!parcelVisible || !installed.contains(point.latitude, point.longitude)) return false
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
        sheet("Property details", "Owner: ${value(feature,"OWNERNAME")}\n\nTax lot: ${value(feature,"TAXLOT")}\nAcres: ${value(feature,"ACRES")}\n\nOwnership classification: Unknown\nLegal public access: Unknown\n\nMarion County assessor-derived record. Boundaries are approximate, not a survey. Ownership does not establish permission to enter.")
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
        val m = region?.manifest ?: return
        sheet("Installed map data", "${m.getString("name")}\n${m.getInt("parcel_count")} parcels · ${m.getInt("tile_count")} aerial tiles\nImagery: OSIP 2024\nExtracted: ${m.getString("extracted_at")}\n\n${m.getString("attribution")}\n\nParcel source:\n${m.getString("parcel_source")}\n\nImagery source:\n${m.getString("imagery_source")}\n\nAll displayed data is local. Internet permission is absent. Detail ends at zoom ${m.getInt("maxzoom")}; closer zoom magnifies stored imagery.\n\nPublic land classification and legal access layers are not installed. MapLibre Native (BSD-2-Clause).")
    }
    fun home() {
        val b=region?.manifest?.getJSONArray("bbox") ?: return
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng((b.getDouble(1)+b.getDouble(3))/2,(b.getDouble(0)+b.getDouble(2))/2),15.0))
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
        val fullyInside = bounds != null && installed.contains(bounds.latitudeNorth,bounds.longitudeEast) && installed.contains(bounds.latitudeSouth,bounds.longitudeWest)
        status.text = (if(online) "Online · " else "Offline · ") + when {
            target == null || !installed.contains(target.latitude,target.longitude) -> "Area not downloaded · tap Region"
            !fullyInside -> "Local Gervais map · view extends beyond download"
            else -> "Local Gervais map · ready"
        }
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
    override fun onResume() { super.onResume(); mapView.onResume(); if(fix!=null) startGps() }
    override fun onPause() { locations.removeUpdates(this); mapView.onPause(); super.onPause() }
    override fun onStop() { if(networkRegistered) { connectivity.unregisterNetworkCallback(networkCallback); networkRegistered=false }; mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    private val Int.dp get() = (this*resources.displayMetrics.density).toInt()
}
