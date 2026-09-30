package dev.lynx.android.plugins.maps

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.lynx.tasm.behavior.LynxContext
import com.lynx.tasm.behavior.LynxProp
import com.lynx.tasm.behavior.LynxUIMethod
import com.lynx.react.bridge.Callback
import com.lynx.react.bridge.ReadableMap
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.react.bridge.JavaOnlyArray
import com.lynx.tasm.event.LynxCustomEvent
import com.lynx.tasm.behavior.ui.LynxUI
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** A native MapLibre surface exposed as the Lynx `lynx-android-map` element. */
class LynxMapsView(context: LynxContext) : LynxUI<FrameLayout>(context) {
    private var latitude = -33.532290
    private var longitude = -71.584904
    private var zoom = 14.0
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var model = JSONObject()
    private var modelSource = ""
    private var contentSource = ""
    private var cameraSource = ""
    private var baseStyle = ""
    private var ready = false
    private var styleGeneration = 0
    private var offline: OfflinePackages? = null
    private var location: MapLocation? = null
    private var userPosition: JSONObject? = null
    private val featureStates = mutableMapOf<String, JSONObject>()
    private val pendingCallbacks = mutableSetOf<Callback>()
    private var lastCameraEvent = 0L
    private var dragging: JSONObject? = null
    private var selectedPackageId: String? = null
    private var shownFile: File? = null
    private var initializedCamera = false
    private var preservedCamera: CameraPosition? = null
    private var zoomPanel: LinearLayout? = null
    private var owner: LifecycleOwner? = null
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var mapFile: File? = null
    private var active = true
    private var mapStarted = false
    private var mapResumed = false
    private var mapDestroyed = false
    private var attributionView: TextView? = null
    private lateinit var status: TextView
    private lateinit var downloadButton: Button
    private lateinit var statusPanel: LinearLayout

    override fun createView(context: Context): FrameLayout {
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        status = TextView(context).apply {
            text = "Checking offline map…"
            setTextColor(Color.DKGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        downloadButton = Button(context).apply {
            text = "Download map"
            visibility = View.GONE
        }
        statusPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(status)
            addView(downloadButton)
        }
        frame.addView(statusPanel, FrameLayout.LayoutParams(-1, -1))
        frame.post { if (active) configure() }
        return frame
    }

    private fun configure() {
        if (offline == null) offline = OfflinePackages(mView.context) { progress ->
            emit("offlineprogress", progress)
            if (progress.getString("id") == selectedPackageId) {
                status.text = "${progress.getString("state")}: ${progress.getLong("received")} / ${progress.getLong("total")} bytes"
                if (progress.getString("state") == "ready") offline?.config(selectedPackageId)?.let { showMap(offline!!.file(it)) }
                if (progress.getString("state") == "error") { downloadButton.visibility = View.VISIBLE; emit("error", JSONObject().put("code", "DOWNLOAD_FAILED").put("message", progress.optString("error"))) }
            }
        }
        if (model.has("mapStyle")) { if (mapView == null) showMap(null); return }
        val catalog = offline ?: return
        val config = catalog.config(model.optString("packageId").ifBlank { null })
        if (config == null) {
            ready = false; emit("loading"); statusPanel.visibility = View.VISIBLE; downloadButton.visibility = View.GONE
            status.text = if (model.optString("packageId").isNotBlank()) "Unknown map package: ${model.optString("packageId")}" else "Map source not configured. Set android/maps.json or mapStyle."
            emit("error", JSONObject().put("code", "INVALID_ARGUMENT").put("message", status.text.toString()))
            return
        }
        if (selectedPackageId == config.id && mapView != null) return
        selectedPackageId = config.id
        if (shownFile != catalog.file(config)) { ready = false; emit("loading"); statusPanel.visibility = View.VISIBLE }
        downloadButton.text = "Download ${config.label} map"
        downloadButton.setOnClickListener { download(config) }
        catalog.cached(config) { cached ->
            if (!active || selectedPackageId != config.id || model.has("mapStyle")) return@cached
            if (cached != null) showMap(cached)
            else { status.text = "${config.label} map is not downloaded."; downloadButton.visibility = View.VISIBLE }
        }
    }

    private fun download(config: MapPackageConfig) {
        downloadButton.visibility = View.GONE
        status.text = "Starting download…"
        try { offline?.operate(config.id, "download") } catch (error: Exception) { status.text = error.message; downloadButton.visibility = View.VISIBLE }
    }

    private fun showMap(file: File?) {
        if (!active) return
        if (mapView != null && shownFile == file) return
        preservedCamera = map?.cameraPosition ?: preservedCamera
        shownFile?.let(OfflinePackages::release)
        mapView?.let { destroyMapView(it); mView.removeView(it) }
        attributionView?.let(mView::removeView)
        map = null; ready = false; contentSource = ""; styleGeneration++
        shownFile = file
        file?.let(OfflinePackages::retain)
        mapFile = file
        try {
            val context = mView.context
            baseStyle = if (file != null) context.assets.open("lynx_maps/style.json").bufferedReader().use { it.readText() }
                .replace("__PMTILES_URI__", "pmtiles://file://${file.absolutePath}")
            else """{"version":8,"glyphs":"asset://lynx_maps/glyphs/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#ffffff"}}]}"""
            MapLibre.getInstance(context)
            val view = MapView(context)
            view.onCreate(Bundle())
            mapStarted = false
            mapResumed = false
            mapDestroyed = false
            mView.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            val attribution = TextView(context).apply {
                text = "© OpenStreetMap contributors"
                setTextColor(Color.DKGRAY)
                setBackgroundColor(0xCCFFFFFF.toInt())
                textSize = 10f
                setPadding(8, 4, 8, 4)
            }
            mView.addView(attribution, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
            attributionView = attribution
            mapView = view
            attachLifecycle(context, view)
            view.getMapAsync { readyMap ->
                if (!active || mapView !== view) return@getMapAsync
                map = readyMap
                installEvents(readyMap, view)
                applyModel()
            }
        } catch (error: Exception) {
            status.text = error.message ?: "Could not open the offline map."
            downloadButton.visibility = View.GONE
        }
    }

    private fun attachLifecycle(context: Context, view: MapView) {
        var current: Context? = context
        while (current is ContextWrapper && current !is LifecycleOwner && current !is Activity) {
            current = current.baseContext
        }
        val lifecycleOwner = current as? LifecycleOwner ?: error("Map host must provide an Android lifecycle.")
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!mapStarted && !mapDestroyed) { view.onStart(); mapStarted = true }
                Lifecycle.Event.ON_RESUME -> if (!mapResumed && !mapDestroyed) { view.onResume(); mapResumed = true }
                Lifecycle.Event.ON_PAUSE -> { location?.stop(); if (mapResumed && !mapDestroyed) { view.onPause(); mapResumed = false } }
                Lifecycle.Event.ON_STOP -> if (mapStarted && !mapDestroyed) { view.onStop(); mapStarted = false }
                Lifecycle.Event.ON_DESTROY -> onDetach()
                else -> Unit
            }
        }
        owner = lifecycleOwner
        lifecycleObserver = observer
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    @LynxProp(name = "model")
    fun setModel(value: String) {
        if (value == modelSource) return
        try {
            val next = JSONObject(value)
            val packageChanged = next.optString("packageId") != model.optString("packageId")
            model = next; modelSource = value
            if (packageChanged) { selectedPackageId = null; configure() }
            else if (mapView == null) configure()
            applyModel()
        } catch (error: Exception) { reportError(error) }
    }

    override fun onPropsUpdated() { super.onPropsUpdated(); applyModel() }

    private fun applyModel(force: Boolean = false) {
        val readyMap = map ?: return
        try {
            val content = MapContent.build(baseStyle, model, featureStates)
            if (force || content != contentSource) {
                contentSource = content; ready = false; emit("loading")
                val generation = ++styleGeneration
                readyMap.setStyle(Style.Builder().fromJson(content)) { style ->
                    if (!active || generation != styleGeneration || map !== readyMap) return@setStyle
                    val images = model.optJSONObject("images")
                    try { images?.keys()?.forEach { id ->
                        val path = images.getString(id).removePrefix("asset://")
                        require(!path.contains("..")) { "Image paths must be app assets." }
                        val bitmap = mView.context.assets.open(path).use(android.graphics.BitmapFactory::decodeStream) ?: error("Invalid map image.")
                        style.addImage(id, bitmap)
                    } } catch (error: Exception) { ready = false; reportError(error); return@setStyle }
                    try {
                        ready = true; statusPanel.visibility = View.GONE
                        if (model.has("userLocation")) userPosition = model.optJSONObject("userLocation")
                        preservedCamera?.let { readyMap.moveCamera(CameraUpdateFactory.newCameraPosition(it)); preservedCamera = null }
                        applyCamera(); applySettings(); updateLocationSource(); updateCallout()
                        attributionView?.text = attributionText()
                        emit("ready")
                    } catch (error: Exception) { ready = false; reportError(error) }
                }
            } else if (ready) { applyCamera(); applySettings(); updateCallout(); if (model.has("userLocation")) { userPosition = model.optJSONObject("userLocation"); updateLocationSource() } }
        } catch (error: Exception) { reportError(error) }
    }

    private fun applyCamera() {
        val camera = model.optJSONObject("camera") ?: if (!initializedCamera) model.optJSONObject("initialCamera") else null
        val legacy = if (model.has("latitude") && model.has("longitude")) JSONObject().put("center", JSONObject().put("latitude", model.getDouble("latitude")).put("longitude", model.getDouble("longitude"))).put("zoom", model.optDouble("zoom", 14.0)) else null
        val value = camera ?: legacy
        if (value != null && value.toString() != cameraSource) { val position = cameraPosition(value); map?.moveCamera(CameraUpdateFactory.newCameraPosition(position)); cameraSource = value.toString() }
        initializedCamera = true
    }

    private fun applySettings() {
        val readyMap = map ?: return
        val gestures = model.optJSONObject("gestures") ?: JSONObject()
        readyMap.uiSettings.apply {
            isScrollGesturesEnabled = gestures.optBoolean("scroll", true); isZoomGesturesEnabled = gestures.optBoolean("zoom", true)
            isRotateGesturesEnabled = gestures.optBoolean("rotate", true); isTiltGesturesEnabled = gestures.optBoolean("pitch", true)
            isDoubleTapGesturesEnabled = gestures.optBoolean("doubleTap", true)
            isCompassEnabled = model.optJSONObject("controls")?.optBoolean("compass", true) ?: true
        }
        val minimum = model.optDouble("minZoom", 0.0); val maximum = model.optDouble("maxZoom", 22.0)
        require(minimum.isFinite() && maximum.isFinite() && minimum in 0.0..25.5 && maximum in minimum..25.5) { "Invalid zoom limits." }
        readyMap.setMinZoomPreference(minimum); readyMap.setMaxZoomPreference(maximum)
        readyMap.setLatLngBoundsForCameraTarget(model.optJSONObject("maxBounds")?.let(::bounds))
        if (model.optJSONObject("controls")?.optBoolean("zoom", false) == true && zoomPanel == null) {
            zoomPanel = LinearLayout(mView.context).apply {
                orientation = LinearLayout.VERTICAL
                for ((label, amount) in listOf("+" to 1.0, "−" to -1.0)) addView(Button(context).apply { text = label; contentDescription = if (amount > 0) "Zoom in" else "Zoom out"; setOnClickListener { map?.easeCamera(CameraUpdateFactory.zoomBy(amount)) } })
            }
            mView.addView(zoomPanel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        }
        zoomPanel?.visibility = if (model.optJSONObject("controls")?.optBoolean("zoom", false) == true) View.VISIBLE else View.GONE
    }

    @LynxProp(name = "latitude")
    fun setLatitude(value: Double) { latitude = value; model.put("latitude", value) }

    @LynxProp(name = "longitude")
    fun setLongitude(value: Double) { longitude = value; model.put("longitude", value) }

    @LynxProp(name = "zoom")
    fun setZoom(value: Double) { zoom = value; model.put("zoom", value) }

    override fun onDetach() {
        active = false
        location?.close(); location = null
        offline?.close(); offline = null
        pendingCallbacks.toList().forEach { finish(it, error = IllegalStateException("MAP_UNMOUNTED: Map was removed.")) }
        ready = false; styleGeneration++
        preservedCamera = map?.cameraPosition
        shownFile?.let(OfflinePackages::release)
        lifecycleObserver?.let { observer -> owner?.lifecycle?.removeObserver(observer) }
        lifecycleObserver = null
        owner = null
        mapView?.let(::destroyMapView)
        mapView?.let(mView::removeView)
        attributionView?.let(mView::removeView)
        attributionView = null
        callout?.let(mView::removeView); callout = null
        zoomPanel?.let(mView::removeView); zoomPanel = null
        mapView = null
        map = null
        shownFile = null
        super.onDetach()
    }

    override fun onAttach() {
        super.onAttach()
        active = true
        if (mapView == null) configure()
    }

    private fun destroyMapView(view: MapView) {
        if (mapDestroyed) return
        if (mapResumed) { view.onPause(); mapResumed = false }
        if (mapStarted) { view.onStop(); mapStarted = false }
        view.onDestroy()
        mapDestroyed = true
    }

    private fun point(value: JSONObject): LatLng { MapContent.coordinate(value); return LatLng(value.getDouble("latitude"), value.getDouble("longitude")) }
    private fun coordinate(value: LatLng): JSONObject = JSONObject().put("latitude", value.latitude).put("longitude", value.longitude)
    private fun bounds(value: JSONObject): LatLngBounds {
        val southwest = point(value.getJSONObject("southwest")); val northeast = point(value.getJSONObject("northeast"))
        require(southwest.latitude <= northeast.latitude && southwest.longitude <= northeast.longitude) { "Bounds must be ordered and must not cross the antimeridian." }
        return LatLngBounds.Builder().include(southwest).include(northeast).build()
    }
    private fun cameraPosition(value: JSONObject): CameraPosition {
        val current = map!!.cameraPosition
        val builder = CameraPosition.Builder(current)
        value.optJSONObject("center")?.let { builder.target(point(it)) }
        if (value.has("zoom")) { val zoom = value.getDouble("zoom"); require(zoom.isFinite() && zoom in 0.0..25.5) { "Invalid zoom." }; builder.zoom(zoom) }
        if (value.has("bearing")) { val bearing = value.getDouble("bearing"); require(bearing.isFinite()) { "Invalid bearing." }; builder.bearing(bearing) }
        if (value.has("pitch")) { val pitch = value.getDouble("pitch"); require(pitch.isFinite() && pitch in 0.0..60.0) { "Pitch must be between 0 and 60." }; builder.tilt(pitch) }
        value.optJSONObject("padding")?.let { padding ->
            val values = listOf("left", "top", "right", "bottom").map { padding.optDouble(it, 0.0) * mView.resources.displayMetrics.density }
            require(values.all { it.isFinite() && it >= 0 }) { "Invalid padding." }
            builder.padding(values.toDoubleArray())
        }
        return builder.build()
    }
    private fun cameraState(): JSONObject {
        val readyMap = map!!
        val camera = readyMap.cameraPosition
        val area = readyMap.projection.visibleRegion.latLngBounds
        val padding = camera.padding ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val density = mView.resources.displayMetrics.density
        return JSONObject().put("center", coordinate(camera.target!!)).put("zoom", camera.zoom).put("bearing", camera.bearing).put("pitch", camera.tilt).put("padding", JSONObject().put("left", padding[0] / density).put("top", padding[1] / density).put("right", padding[2] / density).put("bottom", padding[3] / density)).put("bounds", JSONObject().put("southwest", coordinate(area.southWest)).put("northeast", coordinate(area.northEast)))
    }
    private fun tapData(value: LatLng): JSONObject {
        val screen = map!!.projection.toScreenLocation(value)
        val density = mView.resources.displayMetrics.density
        return JSONObject().put("coordinate", coordinate(value)).put("point", JSONObject().put("x", screen.x / density).put("y", screen.y / density))
    }
    private fun installEvents(readyMap: MapLibreMap, view: MapView) {
        readyMap.addOnCameraMoveListener {
            val now = SystemClock.uptimeMillis()
            if (active && ready && now - lastCameraEvent >= 100) { lastCameraEvent = now; repositionCallout(); emit("camerachange", cameraState()) }
        }
        readyMap.addOnCameraIdleListener { if (active && ready) { repositionCallout(); emit("camerachange", cameraState()); emit("cameraidle", cameraState()) } }
        view.addOnDidFailLoadingMapListener { message -> ready = false; reportError(IllegalStateException("NATIVE_ERROR: $message")) }
        readyMap.addOnMapClickListener { position ->
            if (ready) {
                val screen = readyMap.projection.toScreenLocation(position)
                val radius = 20 * mView.resources.displayMetrics.density
                val hitbox = RectF(screen.x - radius, screen.y - radius, screen.x + radius, screen.y + radius)
                val hits = readyMap.queryRenderedFeatures(hitbox, "__maps_clusters", "__maps_marker_points", "__maps_marker_labels")
                val hit = hits.firstOrNull()
                val event = tapData(position)
                if (hit?.hasProperty("cluster_id") == true) emit("clustertap", event.put("sourceId", MapContent.MARKERS).put("feature", JSONObject(hit.toJson())))
                else if (hit?.hasProperty("itemId") == true) {
                    val id = hit.getStringProperty("itemId")
                    val marker = marker(id)
                    emit("markertap", event.put("id", id).put("marker", marker ?: JSONObject()))
                    marker?.let { showCallout(it) }
                }
                val features = readyMap.queryRenderedFeatures(screen)
                if (features.isNotEmpty()) emit("featuretap", tapData(position).put("features", JSONArray(features.map { JSONObject(it.toJson()) })))
                emit("tap", tapData(position))
            }
            true
        }
        readyMap.addOnMapLongClickListener { position ->
            if (ready) {
                val screen = readyMap.projection.toScreenLocation(position)
                val radius = 20 * mView.resources.displayMetrics.density
                val hit = readyMap.queryRenderedFeatures(RectF(screen.x - radius, screen.y - radius, screen.x + radius, screen.y + radius), "__maps_marker_points").firstOrNull()
                val marker = hit?.takeIf { it.hasProperty("itemId") }?.getStringProperty("itemId")?.let(::marker)
                if (marker?.optBoolean("draggable") == true) { dragging = JSONObject(marker.toString()); readyMap.uiSettings.isScrollGesturesEnabled = false }
                emit("longpress", tapData(position))
            }
            true
        }
        view.setOnTouchListener { _, event ->
            val marker = dragging
            if (marker != null && ready) {
                val position = readyMap.projection.fromScreenLocation(PointF(event.x, event.y))
                marker.put("position", coordinate(position))
                val items = JSONArray((model.optJSONArray("markers") ?: JSONArray()).toString())
                for (index in 0 until items.length()) if (items.getJSONObject(index).getString("id") == marker.getString("id")) items.put(index, marker)
                readyMap.style?.getSourceAs<GeoJsonSource>(MapContent.MARKERS)?.setGeoJson(MapContent.features(items) { JSONObject().put("type", "Point").put("coordinates", MapContent.coordinate(it.getJSONObject("position"))) }.toString())
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    dragging = null; applySettings()
                    readyMap.style?.getSourceAs<GeoJsonSource>(MapContent.MARKERS)?.setGeoJson(MapContent.features(model.optJSONArray("markers")) { JSONObject().put("type", "Point").put("coordinates", MapContent.coordinate(it.getJSONObject("position"))) }.toString())
                    if (event.actionMasked == MotionEvent.ACTION_UP) emit("markerdragend", tapData(position).put("id", marker.getString("id")).put("marker", marker))
                }
                true
            } else false
        }
    }
    private fun marker(id: String): JSONObject? {
        val items = model.optJSONArray("markers") ?: return if (id == "legacy-center" && model.has("latitude") && model.has("longitude")) JSONObject().put("id", id).put("position", JSONObject().put("latitude", model.getDouble("latitude")).put("longitude", model.getDouble("longitude"))) else null
        return (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
    }
    private var callout: TextView? = null
    private var calloutMarkerId: String? = null
    private var selectionSource = ""
    private fun updateCallout() {
        val selection = model.optString("selectedMarkerId")
        if (selection != selectionSource) {
            selectionSource = selection
            marker(selection)?.let(::showCallout) ?: run { callout?.let(mView::removeView); callout = null; calloutMarkerId = null }
        } else if (calloutMarkerId != null && marker(calloutMarkerId!!) == null) { callout?.let(mView::removeView); callout = null; calloutMarkerId = null }
        repositionCallout()
    }
    private fun repositionCallout() {
        val popup = callout ?: return
        val marker = calloutMarkerId?.let(::marker) ?: return
        val screen = map?.projection?.toScreenLocation(point(marker.getJSONObject("position"))) ?: return
        val params = popup.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = (screen.x.toInt() - popup.width / 2).coerceIn(0, (mView.width - popup.width).coerceAtLeast(0))
        params.topMargin = (screen.y.toInt() - popup.height - 16).coerceAtLeast(0)
        popup.layoutParams = params
    }
    private fun showCallout(marker: JSONObject) {
        callout?.let(mView::removeView)
        callout = null; calloutMarkerId = null
        val title = marker.optString("title")
        val description = marker.optString("description")
        if (title.isBlank() && description.isBlank()) return
        calloutMarkerId = marker.getString("id")
        callout = TextView(mView.context).apply {
            text = listOf(title, description).filter { it.isNotBlank() }.joinToString("\n")
            setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE); setPadding(24, 16, 24, 16)
            contentDescription = text
            setOnClickListener { mView.removeView(this); callout = null; calloutMarkerId = null }
        }
        val screen = map!!.projection.toScreenLocation(point(marker.getJSONObject("position")))
        mView.addView(callout, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = screen.x.toInt().coerceAtLeast(0); topMargin = (screen.y.toInt() - 80).coerceAtLeast(0) })
        callout?.post(::repositionCallout)
    }
    private fun locationManager(): MapLocation = location ?: MapLocation(mView.context, { lynxContext.activity }) { position ->
        userPosition = position; updateLocationSource(); emit("locationchange", position)
        val mode = location?.tracking ?: "none"
        if (mode != "none" && ready) {
            val camera = JSONObject().put("center", position)
            if (mode == "heading" || mode == "course") camera.put("bearing", position.optDouble("bearing", 0.0))
            map?.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition(camera)), 300)
        }
    }.also { location = it }
    private fun updateLocationSource() {
        val features = JSONArray()
        userPosition?.let { position -> features.put(JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", JSONObject().put("type", "Point").put("coordinates", MapContent.coordinate(position)))) }
        map?.style?.getSourceAs<GeoJsonSource>(MapContent.LOCATION)?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", features).toString())
    }
    private fun bridge(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> JavaOnlyMap().apply { value.keys().forEach { key -> put(key, bridge(value.get(key))) } }
        is JSONArray -> JavaOnlyArray().apply { for (index in 0 until value.length()) add(bridge(value.get(index))) }
        else -> value
    }
    private fun emit(name: String, detail: JSONObject = JSONObject()) {
        if (active) lynxContext.eventEmitter.sendCustomEvent(LynxCustomEvent(sign, name, (bridge(detail) as JavaOnlyMap)))
    }
    private fun errorCode(error: Throwable): String {
        if (error is SecurityException) return "PERMISSION_DENIED"
        val prefix = error.message?.substringBefore(":")
        return if (prefix in setOf("MAP_UNMOUNTED", "MAP_NOT_READY", "PERMISSION_DENIED", "POSITION_UNAVAILABLE", "DOWNLOAD_FAILED", "CHECKSUM_MISMATCH", "INSUFFICIENT_STORAGE", "NOT_SUPPORTED", "CANCELLED", "TIMEOUT", "NATIVE_ERROR")) prefix!! else if (error is IllegalArgumentException || error is org.json.JSONException) "INVALID_ARGUMENT" else "NATIVE_ERROR"
    }
    private fun reportError(error: Throwable) { emit("error", JSONObject().put("code", errorCode(error)).put("message", error.message ?: "Map failed.")) }
    private fun finish(callback: Callback, data: Any? = null, error: Throwable? = null) {
        if (!pendingCallbacks.remove(callback)) return
        val result = JSONObject().put("ok", error == null)
        if (error == null) result.put("data", data ?: JSONObject.NULL)
        else result.put("code", errorCode(error)).put("message", error.message ?: "Map operation failed.")
        callback.invoke(0, bridge(result))
    }

    /** One generated UI method keeps dispatch instance-scoped and the public JS API typed. */
    @LynxUIMethod
    fun command(params: ReadableMap, callback: Callback) {
        pendingCallbacks.add(callback)
        try {
            check(active) { "MAP_UNMOUNTED: Map was removed." }
            val operation = params.getString("operation")
            val payload = JSONObject(params.getString("payload") ?: "{}")
            if (operation == "getState") { finish(callback, JSONObject().put("ready", ready)); return }
            if (operation?.startsWith("offline.") == true) {
                configure()
                val catalog = offline ?: error("NOT_SUPPORTED: No offline catalog.")
                val action = operation.substringAfter(".")
                val id = payload.optString("id")
                val result: Any = when (action) {
                    "list" -> JSONArray(catalog.list())
                    "getStatus" -> catalog.status(id)
                    "remove" -> catalog.operate(id, action)
                    else -> catalog.operate(id, action)
                }
                finish(callback, result); return
            }
            if (operation == "location.stop") { finish(callback, locationManager().stop()); return }
            if (operation == "location.getStatus") { finish(callback, locationManager().status()); return }
            check(ready && map != null) { "MAP_NOT_READY: Wait for the map style to load." }
            val readyMap = map!!
            when (operation) {
                "getCamera" -> finish(callback, cameraState())
                "stop" -> { readyMap.cancelTransitions(); finish(callback) }
                "jumpTo", "easeTo", "flyTo", "fitBounds" -> {
                    val duration = payload.optInt("duration", 500)
                    require(duration in 0..30_000) { "Animation duration must be between 0 and 30000 ms." }
                    val update = if (operation == "fitBounds") {
                        val padding = payload.optJSONObject("padding") ?: JSONObject()
                        val density = mView.resources.displayMetrics.density
                        val values = listOf("left", "top", "right", "bottom").map { (padding.optDouble(it, 0.0) * density).toInt() }
                        require(values.all { it >= 0 }) { "Invalid padding." }
                        CameraUpdateFactory.newLatLngBounds(bounds(payload.getJSONObject("bounds")), payload.optDouble("bearing", 0.0), payload.optDouble("pitch", 0.0), values[0], values[1], values[2], values[3])
                    } else CameraUpdateFactory.newCameraPosition(cameraPosition(payload))
                    val completion = object : MapLibreMap.CancelableCallback {
                        override fun onFinish() { finish(callback) }
                        override fun onCancel() { finish(callback, error = IllegalStateException("CANCELLED: Camera animation was cancelled.")) }
                    }
                    if (operation == "jumpTo" || duration == 0) readyMap.moveCamera(update, completion)
                    else if (operation == "flyTo") readyMap.animateCamera(update, duration, completion)
                    else readyMap.easeCamera(update, duration, completion)
                }
                "project" -> { val screen = readyMap.projection.toScreenLocation(point(payload)); val density = mView.resources.displayMetrics.density; finish(callback, JSONObject().put("x", screen.x / density).put("y", screen.y / density)) }
                "unproject" -> { val density = mView.resources.displayMetrics.density; finish(callback, coordinate(readyMap.projection.fromScreenLocation(PointF(payload.getDouble("x").toFloat() * density, payload.getDouble("y").toFloat() * density)))) }
                "queryRenderedFeatures" -> {
                    val density = mView.resources.displayMetrics.density
                    val layerIds = payload.optJSONArray("layerIds")?.let { items -> (0 until items.length()).map { items.getString(it) }.toTypedArray() } ?: emptyArray()
                    val filter = payload.optJSONArray("filter")?.let { Expression.Converter.convert(it.toString()) }
                    val screen = payload.optJSONObject("point")
                    val rectangle = payload.optJSONObject("rectangle")
                    val features = if (screen != null) readyMap.queryRenderedFeatures(PointF(screen.getDouble("x").toFloat() * density, screen.getDouble("y").toFloat() * density), filter, *layerIds)
                    else readyMap.queryRenderedFeatures(if (rectangle != null) RectF(rectangle.getDouble("left").toFloat() * density, rectangle.getDouble("top").toFloat() * density, rectangle.getDouble("right").toFloat() * density, rectangle.getDouble("bottom").toFloat() * density) else RectF(0f, 0f, mView.width.toFloat(), mView.height.toFloat()), filter, *layerIds)
                    finish(callback, JSONArray(features.map { JSONObject(it.toJson()) }))
                }
                "querySourceFeatures", "getClusterExpansionZoom", "getClusterChildren", "getClusterLeaves" -> {
                    val source = readyMap.style?.getSourceAs<GeoJsonSource>(payload.getString("sourceId")) ?: error("NOT_SUPPORTED: This operation requires a GeoJSON source.")
                    val result: Any = when (operation) {
                        "querySourceFeatures" -> JSONArray(source.querySourceFeatures(payload.optJSONArray("filter")?.let { Expression.Converter.convert(it.toString()) }).map { JSONObject(it.toJson()) })
                        "getClusterExpansionZoom" -> source.getClusterExpansionZoom(Feature.fromJson(payload.getJSONObject("feature").toString()))
                        "getClusterChildren" -> JSONObject(source.getClusterChildren(Feature.fromJson(payload.getJSONObject("feature").toString())).toJson())
                        else -> { val limit = payload.optLong("limit", 100); val offset = payload.optLong("offset", 0); require(limit in 1..10_000 && offset >= 0) { "Invalid cluster pagination." }; JSONObject(source.getClusterLeaves(Feature.fromJson(payload.getJSONObject("feature").toString()), limit, offset).toJson()) }
                    }
                    finish(callback, result)
                }
                "setFeatureState", "removeFeatureState" -> {
                    val sourceId = payload.getString("sourceId")
                    val source = model.optJSONObject("sources")?.optJSONObject(sourceId) ?: model.optJSONObject("mapStyle")?.optJSONObject("sources")?.optJSONObject(sourceId)
                    require(source?.optString("type") == "geojson" && source.optJSONObject("data") != null) { "NOT_SUPPORTED: Feature state requires owned inline GeoJSON in SDK 11.8." }
                    val data = source!!.getJSONObject("data")
                    val features = if (data.optString("type") == "Feature") JSONArray().put(data) else data.optJSONArray("features") ?: JSONArray()
                    require((0 until features.length()).any { features.getJSONObject(it).opt("id")?.toString() == payload.get("featureId").toString() }) { "Feature state requires an existing, stable feature ID." }
                    val key = "$sourceId:${payload.get("featureId")}"
                    if (operation == "removeFeatureState") featureStates.remove(key)
                    else { val state = featureStates.getOrPut(key) { JSONObject() }; val update = payload.getJSONObject("state"); update.keys().forEach { state.put(it, update.get(it)) } }
                    applyModel(true); finish(callback)
                }
                "location.start" -> locationManager().start(payload) { result -> result.onSuccess { finish(callback, it) }.onFailure { finish(callback, error = it) } }
                "location.setTracking" -> { val mode = payload.getString("mode"); require(mode in listOf("none", "follow", "heading", "course")) { "Invalid tracking mode." }; locationManager().tracking = mode; finish(callback) }
                "location.setPosition" -> { userPosition = payload.optJSONObject("position"); userPosition?.let(MapContent::coordinate); updateLocationSource(); finish(callback) }
                "takeSnapshot" -> snapshot(payload, callback)
                else -> error("NOT_SUPPORTED: Unknown map command: $operation")
            }
        } catch (error: Exception) { finish(callback, error = error) }
    }
    private fun snapshot(options: JSONObject, callback: Callback) {
        val width = options.optInt("width", mView.width)
        val height = options.optInt("height", mView.height)
        require(width in 256..4096 && height in 128..4096 && width.toLong() * height <= 4_194_304) { "Snapshot dimensions must be at least 256x128 pixels, at most 4096 per side and 4 megapixels." }
        map!!.snapshot snapshotReady@{ bitmap ->
            if (!pendingCallbacks.contains(callback)) return@snapshotReady
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                val image = scaled.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(image)
                val label = attributionText()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val fontSize = (width / 45f).coerceIn(8f, 18f)
                val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = fontSize }
                val footer = android.text.StaticLayout.Builder.obtain(label, 0, label.length, textPaint, width - 8).build()
                require(footer.height + 8 <= height / 2) { "Increase snapshot dimensions to preserve all provider attribution." }
                canvas.drawRect(0f, (height - footer.height - 8).toFloat(), width.toFloat(), height.toFloat(), paint)
                canvas.save(); canvas.translate(4f, (height - footer.height - 4).toFloat()); footer.draw(canvas); canvas.restore()
                val directory = File(mView.context.cacheDir, "lynx_maps_snapshots").apply { mkdirs() }
                val target = File.createTempFile("map-", ".png", directory)
                target.outputStream().use { require(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Could not encode snapshot." } }
                image.recycle()
                if (scaled !== bitmap) scaled.recycle()
                val uri = FileProvider.getUriForFile(mView.context, "${mView.context.packageName}.lynxmaps.files", target)
                finish(callback, JSONObject().put("uri", uri.toString()).put("width", width).put("height", height))
            } catch (error: Exception) { finish(callback, error = error) }
        }
    }
    private fun attributionText(): String {
        val sources = runCatching { JSONObject(contentSource).getJSONObject("sources") }.getOrNull()
        val labels = mutableSetOf("MapLibre")
        sources?.keys()?.forEach { id -> sources.getJSONObject(id).optString("attribution").takeIf { it.isNotBlank() }?.let { labels.add(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()) } }
        map?.style?.sources?.forEach { source -> source.attribution?.takeIf { it.isNotBlank() }?.let { labels.add(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()) } }
        return labels.joinToString(" · ")
    }

    override fun destroy() { if (active || mapView != null) onDetach(); super.destroy() }
}
