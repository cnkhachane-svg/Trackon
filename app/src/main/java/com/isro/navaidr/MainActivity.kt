package com.isro.navaidr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin


class MultiMarker(mapView: MapView) : Marker(mapView) {
    override fun showInfoWindow() {
        if (mInfoWindow != null && mPosition != null) {
            mInfoWindow.open(this, mPosition, 0, 0)
        }
    }
}

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var map: MapView
    private lateinit var tvMode: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvHeading: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvSatellites: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvAccuracy: TextView
    private lateinit var tvAccelX: TextView
    private lateinit var tvAccelY: TextView
    private lateinit var tvAccelZ: TextView
    private lateinit var etSourceBox: EditText
    private lateinit var btnUseMyLocation: TextView
    private lateinit var etSearchBox: EditText
    private lateinit var btnSearchAction: TextView
    private lateinit var cardRoutePrompt: androidx.cardview.widget.CardView
    private lateinit var tvGpsStatus: TextView
    private lateinit var viewGpsIndicator: View

    private var vehicleMarker: Marker? = null
    private var searchMarker: Marker? = null
    private var sourceMarker: Marker? = null
    private lateinit var pathOverlay: Polyline
    private val routeOverlays = mutableListOf<Polyline>()

    private var pendingDestination: GeoPoint? = null
    private var searchedQueryText: String = ""
    private var useCurrentLocationAsSource = false

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var linearAcceleration: Sensor? = null
    private lateinit var locationManager: LocationManager

    private val accelReading = FloatArray(3)
    private val magReading = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var isAlignmentCalibrated = false
    private val gravityReading = FloatArray(3)
    private val geomagneticReading = FloatArray(3)
    private val vehicleRotationMatrix = FloatArray(9)
    private var yawOffset = 0.0

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private lateinit var edgeEngine: EdgeNavigationEngine

    private val bufferChannels = 6
    private val windowSize = 50
    private val sensorBuffer = Array(bufferChannels) { FloatArray(windowSize) }
    private var sampleCount = 0

    private var pitch = 0.0
    private var roll = 0.0
    private var isCalibrated = false

    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentHeading = 0.0
    private var lastGpsSpeed = 0.0f
    private var isGnssAvailable = false
    private var isFirstLocationReceived = false
    private val dt = 0.02

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, getPreferences(MODE_PRIVATE))
        setContentView(R.layout.activity_main)

        tvMode = findViewById(R.id.tvMode)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvHeading = findViewById(R.id.tvHeading)
        tvDirection = findViewById(R.id.tvDirection)
        tvSatellites = findViewById(R.id.tvSatellites)
        tvPosition = findViewById(R.id.tvPosition)
        tvAccuracy = findViewById(R.id.tvAccuracy)
        tvAccelX = findViewById(R.id.tvAccelX)
        tvAccelY = findViewById(R.id.tvAccelY)
        tvAccelZ = findViewById(R.id.tvAccelZ)
        etSourceBox = findViewById(R.id.etSourceBox)
        btnUseMyLocation = findViewById(R.id.btnUseMyLocation)
        etSearchBox = findViewById(R.id.etSearchBox)
        btnSearchAction = findViewById(R.id.btnSearchAction)
        cardRoutePrompt = findViewById(R.id.cardRoutePrompt)
        map = findViewById(R.id.mapView)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        viewGpsIndicator = findViewById(R.id.viewGpsIndicator)

        tvPosition.text = getString(R.string.waiting_gps)
        tvAccuracy.text = "Remaining: --"

        setupMap()
        initOnnxEngine()
        initSensors()
        setupSearchListener()
        setupBackPressHandler()
        checkPermissions()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    cardRoutePrompt.isVisible -> {
                        cardRoutePrompt.isVisible = false
                        tvMode.text = getString(R.string.mode_gnss_active)
                        tvMode.setTextColor("#00E676".toColorInt())
                    }

                    routeOverlays.isNotEmpty() || searchMarker != null || sourceMarker != null -> {
                        for (polyline in routeOverlays) {
                            map.overlays.remove(polyline)
                        }
                        routeOverlays.clear()

                        searchMarker?.let { map.overlays.remove(it) }
                        searchMarker = null

                        sourceMarker?.let { map.overlays.remove(it) }
                        sourceMarker = null

                        etSearchBox.setText("")
                        etSourceBox.setText("")
                        cardRoutePrompt.isVisible = false

                        tvMode.text = getString(R.string.mode_gnss_active)
                        tvMode.setTextColor("#00E676".toColorInt())
                        map.invalidate()
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        map.isHorizontalMapRepetitionEnabled = false
        map.isVerticalMapRepetitionEnabled = false
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 20.0

        map.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        map.setScrollableAreaLimitLongitude(MapView.getTileSystem().minLongitude, MapView.getTileSystem().maxLongitude, 0)

        map.controller.setZoom(15.0)

        val defaultCenter = GeoPoint(20.5937, 78.9629)
        map.controller.setCenter(defaultCenter)

        pathOverlay = Polyline().apply {
            outlinePaint.color = "#00E676".toColorInt()
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(pathOverlay)
    }

    private fun setupSearchListener() {
        btnUseMyLocation.setOnClickListener {
            useCurrentLocationAsSource = !useCurrentLocationAsSource
            if (useCurrentLocationAsSource) {
                etSourceBox.setText(getString(R.string.current_location_gps))
                etSourceBox.isEnabled = false
                btnUseMyLocation.setBackgroundColor("#C8E6C9".toColorInt())
                tvMode.text = getString(R.string.mode_using_live_location)

                sourceMarker?.let { map.overlays.remove(it) }
                sourceMarker = null
            } else {
                etSourceBox.setText("")
                etSourceBox.isEnabled = true
                btnUseMyLocation.setBackgroundColor("#E8F5E9".toColorInt())
                tvMode.text = getString(R.string.mode_custom_source)
            }
        }

        etSearchBox.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val query = etSearchBox.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchLocation(query)
                }
                true
            } else {
                false
            }
        }

        btnSearchAction.setOnClickListener {
            val query = etSearchBox.text.toString().trim()
            if (query.isNotEmpty()) {
                tvMode.text = getString(R.string.mode_searching)
                searchLocation(query)
            }
        }

        cardRoutePrompt.setOnClickListener {
            cardRoutePrompt.isVisible = false

            if (useCurrentLocationAsSource) {
                if (!isFirstLocationReceived) {
                    tvMode.text = getString(R.string.mode_waiting_gps_fix)
                    return@setOnClickListener
                }
                pendingDestination?.let { dest ->
                    tvMode.text = getString(R.string.mode_calculating_live)
                    fetchAndDrawRoutes(GeoPoint(currentLat, currentLon), dest)
                }
            } else {
                val sourceQuery = etSourceBox.text.toString().trim()
                if (sourceQuery.isNotEmpty()) {
                    tvMode.text = getString(R.string.mode_finding_source)
                    geocodeLocation(sourceQuery) { sourcePoint ->
                        if (sourcePoint != null && pendingDestination != null) {
                            runOnUiThread {
                                tvMode.text = getString(R.string.mode_calculating_custom)

                                if (sourceMarker == null) {
                                    val blackMarkerDrawable = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)?.mutate()
                                    blackMarkerDrawable?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(android.graphics.Color.BLACK, BlendModeCompat.SRC_IN)

                                    sourceMarker = MultiMarker(map).apply {
                                        position = sourcePoint
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        icon = blackMarkerDrawable
                                        title = getString(R.string.source_label, sourceQuery)
                                    }
                                    map.overlays.add(sourceMarker)
                                } else {
                                    sourceMarker?.position = sourcePoint
                                    sourceMarker?.title = getString(R.string.source_label, sourceQuery)
                                }

                                sourceMarker?.showInfoWindow()

                                fetchAndDrawRoutes(sourcePoint, pendingDestination!!)
                            }
                        } else {
                            runOnUiThread {
                                tvMode.text = getString(R.string.mode_source_not_found)
                            }
                        }
                    }
                } else {
                    pendingDestination?.let { dest ->
                        if (!isFirstLocationReceived) {
                            tvMode.text = getString(R.string.mode_waiting_gps_fix)
                            return@setOnClickListener
                        }
                        tvMode.text = getString(R.string.mode_calculating_multi)
                        fetchAndDrawRoutes(GeoPoint(currentLat, currentLon), dest)
                    }
                }
            }
        }
    }

    private fun geocodeLocation(query: String, callback: (GeoPoint?) -> Unit) {
        Thread {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Trackon-App")

                if (connection.responseCode == 200) {
                    val responseString = connection.inputStream.bufferedReader().use { it.readText() }
                    if (responseString.contains("lat") && responseString.contains("lon")) {
                        val latIndex = responseString.indexOf("\"lat\":\"") + 7
                        val latEnd = responseString.indexOf("\"", latIndex)
                        val lat = responseString.substring(latIndex, latEnd).toDouble()

                        val lonIndex = responseString.indexOf("\"lon\":\"") + 7
                        val lonEnd = responseString.indexOf("\"", lonIndex)
                        val lon = responseString.substring(lonIndex, lonEnd).toDouble()

                        callback(GeoPoint(lat, lon))
                        return@Thread
                    }
                }
                callback(null)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }.start()
    }

    private fun searchLocation(query: String) {
        searchedQueryText = query
        Thread {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val urlString = "https://nominatim.openstreetmap.org/search?q=$encodedQuery&format=json&limit=1"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Trackon-App")

                if (connection.responseCode == 200) {
                    val responseString = connection.inputStream.bufferedReader().use { it.readText() }

                    if (responseString.contains("lat") && responseString.contains("lon")) {
                        val latIndex = responseString.indexOf("\"lat\":\"") + 7
                        val latEnd = responseString.indexOf("\"", latIndex)
                        val searchLat = responseString.substring(latIndex, latEnd).toDouble()

                        val lonIndex = responseString.indexOf("\"lon\":\"") + 7
                        val lonEnd = responseString.indexOf("\"", lonIndex)
                        val searchLon = responseString.substring(lonIndex, lonEnd).toDouble()

                        runOnUiThread {
                            val searchPoint = GeoPoint(searchLat, searchLon)
                            pendingDestination = searchPoint

                            if (searchMarker == null) {
                                val blackMarkerDrawable = ContextCompat.getDrawable(this, org.osmdroid.library.R.drawable.marker_default)?.mutate()
                                blackMarkerDrawable?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(android.graphics.Color.BLACK, BlendModeCompat.SRC_IN)

                                searchMarker = MultiMarker(map).apply {
                                    position = searchPoint
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    icon = blackMarkerDrawable
                                    title = getString(R.string.destination_label, query)
                                }
                                map.overlays.add(searchMarker)
                            } else {
                                searchMarker?.position = searchPoint
                                searchMarker?.title = getString(R.string.destination_label, query)
                            }

                            searchMarker?.showInfoWindow()

                            map.controller.setCenter(searchPoint)
                            map.controller.setZoom(15.0)
                            map.invalidate()

                            for (polyline in routeOverlays) {
                                map.overlays.remove(polyline)
                            }
                            routeOverlays.clear()

                            cardRoutePrompt.isVisible = true
                            tvMode.text = getString(R.string.mode_location_found)
                            tvMode.setTextColor("#1976D2".toColorInt())
                        }
                    } else {
                        runOnUiThread {
                            cardRoutePrompt.isVisible = false
                            tvMode.text = getString(R.string.mode_location_not_found)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    cardRoutePrompt.isVisible = false
                    tvMode.text = getString(R.string.mode_search_error)
                }
            }
        }.start()
    }

    private fun fetchAndDrawRoutes(start: GeoPoint, destination: GeoPoint) {
        Thread {
            try {
                val straightLineDistanceMeters = start.distanceToAsDouble(destination)
                val straightLineDistanceKm = straightLineDistanceMeters / 1000.0

                val osrmUrl = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&alternatives=true"
                val url = URL(osrmUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Trackon-App")

                if (connection.responseCode == 200) {
                    val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(jsonResponse)
                    val routes = jsonObject.optJSONArray("routes")

                    runOnUiThread {
                        for (polyline in routeOverlays) {
                            map.overlays.remove(polyline)
                        }
                        routeOverlays.clear()

                        val sourceQueryText = etSourceBox.text.toString().trim().ifEmpty { getString(R.string.current_location) }
                        sourceMarker?.title = getString(R.string.source_label, sourceQueryText)

                        if (straightLineDistanceKm > 2000.0 || routes == null || routes.length() == 0) {
                            val errorDetails = getString(R.string.error_ground_route, searchedQueryText, straightLineDistanceKm)

                            searchMarker?.title = errorDetails

                            searchMarker?.showInfoWindow()
                            sourceMarker?.showInfoWindow()

                            map.controller.setCenter(destination)
                            map.controller.setZoom(5.0)
                            map.invalidate()

                            tvMode.text = getString(R.string.mode_route_not_possible)
                            tvMode.setTextColor("#FF5252".toColorInt())
                        } else {
                            val limit = if (routes.length() > 3) 3 else routes.length()
                            val routeDetailsBuilder = StringBuilder(getString(R.string.route_details_header, searchedQueryText))

                            for (i in 0 until limit) {
                                val route = routes.getJSONObject(i)
                                val distanceMeters = route.getDouble("distance")
                                val distanceKm = distanceMeters / 1000.0

                                if (i == 0) {
                                    routeDetailsBuilder.append(getString(R.string.best_route_format, distanceKm))
                                } else {
                                    routeDetailsBuilder.append(getString(R.string.alt_route_format, i, distanceKm))
                                }

                                val geometry = route.getJSONObject("geometry")
                                val coordinates = geometry.getJSONArray("coordinates")

                                val coordsList = mutableListOf<GeoPoint>()
                                for (j in 0 until coordinates.length()) {
                                    val point = coordinates.getJSONArray(j)
                                    val lon = point.getDouble(0)
                                    val lat = point.getDouble(1)
                                    coordsList.add(GeoPoint(lat, lon))
                                }

                                val routeColor = if (i == 0) {
                                    "#2196F3".toColorInt()
                                } else {
                                    "#F44336".toColorInt()
                                }

                                val strokeWidth = if (i == 0) 14f else 8f

                                val polyline = Polyline().apply {
                                    setPoints(coordsList)
                                    outlinePaint.color = routeColor
                                    outlinePaint.strokeWidth = strokeWidth
                                }

                                routeOverlays.add(polyline)
                                map.overlays.add(polyline)
                            }

                            searchMarker?.title = routeDetailsBuilder.toString()

                            searchMarker?.showInfoWindow()
                            sourceMarker?.showInfoWindow()

                            if (routeOverlays.isNotEmpty()) {
                                val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(routeOverlays[0].actualPoints)
                                map.zoomToBoundingBox(boundingBox, true, 100)
                            } else {
                                map.controller.setCenter(destination)
                                map.controller.setZoom(10.0)
                            }

                            map.invalidate()
                            tvMode.text = getString(R.string.mode_multi_routing_active)
                            tvMode.setTextColor("#43A047".toColorInt())
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    tvMode.text = getString(R.string.mode_route_failed)
                }
            }
        }.start()
    }

    private fun initOnnxEngine() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val modelFile = copyAssetToFile("nav_velocity_model.onnx")
            copyAssetToFile("nav_velocity_model.onnx.data")
            ortSession = ortEnvironment?.createSession(modelFile.absolutePath)

            edgeEngine = EdgeNavigationEngine(modelFile.absolutePath)

            tvMode.text = getString(R.string.mode_ai_engine_ready)
        } catch (e: Exception) {
            tvMode.text = getString(R.string.error_message, e.message)
            e.printStackTrace()
        }
    }

    private fun copyAssetToFile(assetName: String): File {
        val file = File(filesDir, assetName)
        if (!file.exists()) {
            assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file
    }

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 100)
        } else {
            startFastLocationUpdates()
        }
    }

    private fun startFastLocationUpdates() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                lastKnown?.let { loc ->
                    onLocationChanged(loc)
                }

                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0.0f, this)

                runOnUiThread {
                    tvSatellites.text = "8"
                }

                locationManager.registerGnssStatusCallback(object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var activeSatellites = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) {
                                activeSatellites++
                            }
                        }
                        if (activeSatellites == 0) {
                            activeSatellites = status.satelliteCount
                        }
                        val finalCount = if (activeSatellites > 0) activeSatellites else 12
                        runOnUiThread {
                            tvSatellites.text = "$finalCount"
                        }
                    }
                }, android.os.Handler(mainLooper))
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startFastLocationUpdates()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        linearAcceleration?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::edgeEngine.isInitialized) {
            edgeEngine.release()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelReading, 0, event.values.size.coerceAtMost(3))
                System.arraycopy(event.values, 0, gravityReading, 0, event.values.size.coerceAtMost(3))
                updateCompassHeading()

                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                if (!isCalibrated) {
                    val norm = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
                    if (norm > 9.0 && norm < 11.0) {
                        pitch = kotlin.math.asin((-ax / norm).toDouble())
                        roll = kotlin.math.atan2(ay.toDouble(), az.toDouble())
                        isCalibrated = true
                    }
                }
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magReading, 0, event.values.size.coerceAtMost(3))
                System.arraycopy(event.values, 0, geomagneticReading, 0, event.values.size.coerceAtMost(3))
                updateCompassHeading()
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val lx = event.values[0]
                val ly = event.values[1]
                val lz = event.values[2]

                runOnUiThread {
                    tvAccelX.text = getString(R.string.accel_x, lx)
                    tvAccelY.text = getString(R.string.accel_y, ly)
                    tvAccelZ.text = getString(R.string.accel_z, lz)
                }

                val correctedLx = (lx * cos(pitch) + lz * sin(pitch)).toFloat()
                val correctedLy = (ly * cos(roll) - lz * sin(roll)).toFloat()
                val correctedLz = (lx * sin(pitch) + ly * sin(roll) + lz * cos(pitch)).toFloat()

                if (sampleCount < windowSize) {
                    sensorBuffer[0][sampleCount] = correctedLx
                    sensorBuffer[1][sampleCount] = correctedLy
                    sensorBuffer[2][sampleCount] = correctedLz
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (sampleCount < windowSize) {
                    sensorBuffer[3][sampleCount] = event.values[0]
                    sensorBuffer[4][sampleCount] = event.values[1]
                    sensorBuffer[5][sampleCount] = event.values[2]
                    sampleCount++
                }
            }
        }

        if (!isAlignmentCalibrated && lastGpsSpeed > 2.0f && isGnssAvailable) {
            calibrateVehicleAlignment()
        }

        if (sampleCount >= windowSize) {
            runOnDeviceInference()
            sampleCount = 0
        }
    }

    private fun calibrateVehicleAlignment() {
        val success = SensorManager.getRotationMatrix(vehicleRotationMatrix, null, gravityReading, geomagneticReading)
        if (success) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(vehicleRotationMatrix, orientation)
            val deviceAzimuth = orientation[0].toDouble()
            yawOffset = currentHeading - deviceAzimuth
            isAlignmentCalibrated = true

            runOnUiThread {
                tvMode.text = getString(R.string.mode_alignment_calibrated)
                tvMode.setTextColor("#00E676".toColorInt())
            }
        }
    }

    private fun updateCompassHeading() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelReading, magReading)
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var azimuth = orientationAngles[0].toDouble()
            if (azimuth < 0) {
                azimuth += 2 * Math.PI
            }
            currentHeading = azimuth
        }
    }

    private fun getDirectionString(degrees: Double): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        return directions[((degrees + 22.5) % 360 / 45).toInt()]
    }

    private fun applyNonHolonomicConstraints(rawDx: Double, rawDy: Double, heading: Double): Pair<Double, Double> {
        val forwardSpeedComponent = rawDy * cos(heading) + rawDx * sin(heading)
        val constrainedDx = forwardSpeedComponent * sin(heading)
        val constrainedDy = forwardSpeedComponent * cos(heading)
        return Pair(constrainedDx, constrainedDy)
    }

    private fun snapToNearestRoad(lat: Double, lon: Double, callback: (GeoPoint?) -> Unit) {
        Thread {
            try {
                val overpassUrl = "https://overpass-api.de/api/interpreter?data=[out:json];way(around:15,$lat,$lon)[highway];out center;"
                val url = URL(overpassUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Trackon-App")

                if (connection.responseCode == 200) {
                    val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = org.json.JSONObject(jsonResponse)
                    val elements = jsonObject.optJSONArray("elements")

                    if (elements != null && elements.length() > 0) {
                        val roadElement = elements.getJSONObject(0)
                        val roadLat = roadElement.optDouble("lat", roadElement.optJSONObject("center")?.optDouble("lat") ?: lat)
                        val roadLon = roadElement.optDouble("lon", roadElement.optJSONObject("center")?.optDouble("lon") ?: lon)
                        callback(GeoPoint(roadLat, roadLon))
                        return@Thread
                    }
                }
                callback(null)
            } catch (e: Exception) {
                e.printStackTrace()
                callback(null)
            }
        }.start()
    }

    private fun updateRemainingDistance() {
        val dest = pendingDestination ?: return
        if (!isFirstLocationReceived) return

        val currentPoint = GeoPoint(currentLat, currentLon)
        val distanceMeters = currentPoint.distanceToAsDouble(dest)

        runOnUiThread {
            if (distanceMeters < 1000.0) {
                tvAccuracy.text = "Remaining: %.0f m".format(distanceMeters)
            } else {
                tvAccuracy.text = "Remaining: %.2f km".format(distanceMeters / 1000.0)
            }
        }
    }

    private fun runOnDeviceInference() {
        val session = ortSession ?: return
        val env = ortEnvironment ?: return

        try {
            val flatData = FloatArray(bufferChannels * windowSize)
            var index = 0
            for (c in 0 until bufferChannels) {
                for (w in 0 until windowSize) {
                    flatData[index++] = sensorBuffer[c][w]
                }
            }

            val tensorShape = longArrayOf(1, bufferChannels.toLong(), windowSize.toLong())
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatData), tensorShape)

            val inputName = session.inputNames.iterator().next()
            val output = session.run(mapOf(inputName to inputTensor))

            val outputTensor = output[0] as OnnxTensor
            val velocityArray = outputTensor.floatBuffer.array()

            // Calculate signal variance across windows for ZUPT (Zero-Velocity Update) filtering
            var totalVariance = 0.0f
            for (c in 0 until 3) {
                val mean = sensorBuffer[c].average().toFloat()
                totalVariance += sensorBuffer[c].map { (it - mean) * (it - mean) }.sum() / windowSize
            }

            val rawPredictedSpeed = if (velocityArray.isNotEmpty()) velocityArray[0] else 0.0f
            val predictedSpeed = if (totalVariance < 0.02f || rawPredictedSpeed < 0.5f) {
                0.0f
            } else {
                rawPredictedSpeed
            }

            val isGpsActuallyOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (!isGpsActuallyOn) {
                isGnssAvailable = false
            }

            val headingDeg = Math.toDegrees(currentHeading) % 360

            if (!isGnssAvailable && isFirstLocationReceived && predictedSpeed > 0.0f) {
                val windowDuration = windowSize * dt
                val correctedHeading = currentHeading + yawOffset
                val rawDy = predictedSpeed * windowDuration * cos(correctedHeading)
                val rawDx = predictedSpeed * windowDuration * sin(correctedHeading)

                val (constrainedDx, constrainedDy) = applyNonHolonomicConstraints(rawDx, rawDy, correctedHeading)

                currentLat += (constrainedDy / 110540.0)
                currentLon += (constrainedDx / (111320.0 * cos(Math.toRadians(currentLat))))

                snapToNearestRoad(currentLat, currentLon) { snappedPoint ->
                    snappedPoint?.let { snap ->
                        currentLat = snap.latitude
                        currentLon = snap.longitude
                    }
                }
            }

            runOnUiThread {
                val effectiveGpsSpeed = if (isGnssAvailable && lastGpsSpeed > 0.3f) lastGpsSpeed else 0.0f
                var displaySpeedKmh = if (effectiveGpsSpeed > 0.0f) {
                    effectiveGpsSpeed * 3.6f
                } else {
                    predictedSpeed * 3.6f
                }

                if (displaySpeedKmh < 0.8f) displaySpeedKmh = 0.0f

                tvSpeed.text = getString(R.string.speed_format, displaySpeedKmh)
                tvHeading.text = getString(R.string.heading_format, headingDeg)
                tvDirection.text = getDirectionString(headingDeg)

                val checkingGpsOn = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                if (!checkingGpsOn || !isGnssAvailable) {
                    tvMode.text = getString(R.string.mode_dead_reckoning)
                    tvMode.setTextColor("#FF5252".toColorInt())
                    tvAccuracy.text = getString(R.string.gps_denied_ai_ins)

                    tvGpsStatus.text = getString(R.string.gps_lost)
                    viewGpsIndicator.background = "#FF5252".toColorInt().toDrawable()
                } else {
                    if (!isAlignmentCalibrated) {
                        tvMode.text = getString(R.string.mode_gnss_calibrating)
                    } else {
                        tvMode.text = getString(R.string.mode_gnss_active)
                    }
                    tvMode.setTextColor("#00E676".toColorInt())

                    tvGpsStatus.text = getString(R.string.gps_connected)
                    viewGpsIndicator.background = "#43A047".toColorInt().toDrawable()
                }

                if (isFirstLocationReceived) {
                    val newGeoPoint = GeoPoint(currentLat, currentLon)

                    if (vehicleMarker == null) {
                        val blackArrowDrawable = ContextCompat.getDrawable(this, R.drawable.ic_navigation_arrow)?.mutate()
                        blackArrowDrawable?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(android.graphics.Color.BLACK, BlendModeCompat.SRC_IN)

                        vehicleMarker = Marker(map).apply {
                            position = newGeoPoint
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            icon = blackArrowDrawable
                            title = getString(R.string.current_location)
                        }
                        map.overlays.add(vehicleMarker)
                    } else {
                        vehicleMarker?.position = newGeoPoint
                    }

                    vehicleMarker?.rotation = headingDeg.toFloat()

                    if (displaySpeedKmh > 0.5f) {
                        val lastPoint = if (pathOverlay.actualPoints.isNotEmpty()) pathOverlay.actualPoints.last() else null
                        val distance = lastPoint?.distanceToAsDouble(newGeoPoint) ?: 1.0

                        if (distance > 0.5) {
                            pathOverlay.addPoint(newGeoPoint)
                        }
                    }

                    map.invalidate()
                    updateRemainingDistance()
                }
                tvPosition.text = if (isFirstLocationReceived) getString(R.string.position_format, currentLat, currentLon) else getString(R.string.no_fix)
            }

            inputTensor.close()
            output.close()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        val accuracy = location.accuracy

        if (accuracy <= 100.0f || !isFirstLocationReceived) {
            isGnssAvailable = true
            lastGpsSpeed = location.speed

            val newLat = location.latitude
            val newLon = location.longitude
            val incomingPoint = GeoPoint(newLat, newLon)

            val wasNotReceivedYet = !isFirstLocationReceived
            currentLat = newLat
            currentLon = newLon
            isFirstLocationReceived = true

            val headingDeg = Math.toDegrees(currentHeading) % 360

            runOnUiThread {
                tvAccuracy.text = getString(R.string.accuracy_format, accuracy)

                if (vehicleMarker == null) {
                    val blackArrowDrawable = ContextCompat.getDrawable(this, R.drawable.ic_navigation_arrow)?.mutate()
                    blackArrowDrawable?.colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(android.graphics.Color.BLACK, BlendModeCompat.SRC_IN)

                    vehicleMarker = Marker(map).apply {
                        position = incomingPoint
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = blackArrowDrawable
                        title = getString(R.string.current_location)
                    }
                    map.overlays.add(vehicleMarker)
                } else {
                    vehicleMarker?.position = incomingPoint
                }

                vehicleMarker?.rotation = headingDeg.toFloat()

                if (wasNotReceivedYet) {
                    map.controller.animateTo(incomingPoint)
                    map.controller.setZoom(17.0)
                }

                if (lastGpsSpeed > 0.3f) {
                    val lastPoint = if (pathOverlay.actualPoints.isNotEmpty()) pathOverlay.actualPoints.last() else null
                    val distance = lastPoint?.distanceToAsDouble(incomingPoint) ?: 1.0
                    if (distance > 0.5) {
                        pathOverlay.addPoint(incomingPoint)
                    }
                }

                tvGpsStatus.text = getString(R.string.gps_connected)
                viewGpsIndicator.background = "#43A047".toColorInt().toDrawable()

                map.invalidate()
                tvPosition.text = getString(R.string.position_format, currentLat, currentLon)
                updateRemainingDistance()
            }
        } else {
            isGnssAvailable = false
            lastGpsSpeed = 0.0f
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER) {
            isGnssAvailable = true
            startFastLocationUpdates()

            runOnUiThread {
                tvMode.text = getString(R.string.mode_gps_reconnected)
                tvMode.setTextColor("#00E676".toColorInt())
                tvGpsStatus.text = getString(R.string.gps_connected)
                viewGpsIndicator.background = "#43A047".toColorInt().toDrawable()
            }
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER || provider == LocationManager.NETWORK_PROVIDER) {
            isGnssAvailable = false
            runOnUiThread {
                tvMode.text = getString(R.string.mode_dead_reckoning)
                tvAccuracy.text = getString(R.string.turn_on_gps)
                tvMode.setTextColor("#FF5252".toColorInt())
                tvGpsStatus.text = getString(R.string.gps_off)
                viewGpsIndicator.background = "#FF5252".toColorInt().toDrawable()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}