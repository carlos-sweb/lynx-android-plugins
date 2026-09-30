package dev.lynx.android.plugins.maps

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Produces Style Specification JSON without deprecated annotation APIs. */
internal object MapContent {
    const val MARKERS = "__maps_markers"
    const val LOCATION = "__maps_location"
    fun coordinate(value: JSONObject): JSONArray {
        val latitude = value.getDouble("latitude")
        val longitude = value.getDouble("longitude")
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid coordinate." }
        return JSONArray().put(longitude).put(latitude)
    }
    fun features(items: JSONArray?, geometry: (JSONObject) -> JSONObject): JSONObject {
        val result = JSONArray()
        if (items != null) for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val properties = JSONObject(item.toString()).put("itemId", item.getString("id"))
            result.put(JSONObject().put("type", "Feature").put("id", item.getString("id")).put("properties", properties).put("geometry", geometry(item)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", result)
    }
    private fun line(points: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until points.length()) put(coordinate(points.getJSONObject(index)))
    }
    private fun ring(points: JSONArray): JSONArray = line(points).apply {
        require(length() >= 3) { "Polygon rings need three points." }
        if (getJSONArray(0).toString() != getJSONArray(length() - 1).toString()) put(getJSONArray(0))
    }
    private fun circle(item: JSONObject): JSONObject {
        val center = item.getJSONObject("center")
        coordinate(center)
        val lat = Math.toRadians(center.getDouble("latitude"))
        val lon = Math.toRadians(center.getDouble("longitude"))
        val radius = item.getDouble("radius")
        require(radius.isFinite() && radius > 0) { "Circle radius must be positive." }
        val distance = radius / 6_371_008.8
        val points = JSONArray()
        for (index in 0 until 64) {
            val bearing = index * 2 * PI / 64
            val targetLat = asin(sin(lat) * cos(distance) + cos(lat) * sin(distance) * cos(bearing))
            val targetLon = lon + atan2(sin(bearing) * sin(distance) * cos(lat), cos(distance) - sin(lat) * sin(targetLat))
            points.put(JSONArray().put((Math.toDegrees(targetLon) + 540) % 360 - 180).put(Math.toDegrees(targetLat)))
        }
        points.put(points.getJSONArray(0))
        return JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(points))
    }
    fun build(base: String, model: JSONObject, states: Map<String, JSONObject>): String {
        val style = JSONObject((model.optJSONObject("mapStyle") ?: JSONObject(base)).toString())
        if (!style.has("glyphs")) style.put("glyphs", "asset://lynx_maps/glyphs/{fontstack}/{range}.pbf")
        val sources = style.getJSONObject("sources")
        val layers = style.getJSONArray("layers")
        require(sources.keys().asSequence().none { it.startsWith("__maps_") }) { "Reserved source ID in mapStyle." }
        require((0 until layers.length()).none { layers.getJSONObject(it).getString("id").startsWith("__maps_") }) { "Reserved layer ID in mapStyle." }
        val extraSources = model.optJSONObject("sources")
        extraSources?.keys()?.forEach { id ->
            require(!id.startsWith("__maps_") && !sources.has(id)) { "Duplicate or reserved source: $id" }
            sources.put(id, JSONObject(extraSources.getJSONObject(id).toString()))
        }
        val extraLayers = model.optJSONArray("layers")
        if (extraLayers != null) for (index in 0 until extraLayers.length()) {
            val layer = extraLayers.getJSONObject(index)
            require(!layer.getString("id").startsWith("__maps_")) { "Reserved layer ID." }
            layers.put(JSONObject(layer.toString()))
        }
        fun source(id: String, data: JSONObject): JSONObject {
            val value = JSONObject().put("type", "geojson").put("data", data)
            sources.put(id, value)
            return value
        }
        fun layer(id: String, type: String, source: String, paint: JSONObject, layout: JSONObject? = null, filter: JSONArray? = null) {
            val value = JSONObject().put("id", id).put("type", type).put("source", source).put("paint", paint)
            if (layout != null) value.put("layout", layout)
            if (filter != null) value.put("filter", filter)
            layers.put(value)
        }
        fun property(name: String, fallback: Any): JSONArray = JSONArray().put("coalesce").put(JSONArray().put("get").put(name)).put(fallback)
        var markers = model.optJSONArray("markers")
        if (markers == null && model.has("latitude") && model.has("longitude")) markers = JSONArray().put(JSONObject().put("id", "legacy-center").put("position", JSONObject().put("latitude", model.getDouble("latitude")).put("longitude", model.getDouble("longitude"))))
        val markerSource = source(MARKERS, features(markers) { JSONObject().put("type", "Point").put("coordinates", coordinate(it.getJSONObject("position"))) })
        val cluster = model.opt("cluster")
        if (cluster == true || cluster is JSONObject) {
            markerSource.put("cluster", true).put("clusterRadius", (cluster as? JSONObject)?.optInt("radius", 50) ?: 50).put("clusterMaxZoom", (cluster as? JSONObject)?.optInt("maxZoom", 14) ?: 14)
            layer("__maps_clusters", "circle", MARKERS, JSONObject().put("circle-color", "#334155").put("circle-radius", 20), filter = JSONArray().put("has").put("point_count"))
            layer("__maps_cluster_labels", "symbol", MARKERS, JSONObject().put("text-color", "#ffffff"), JSONObject().put("text-field", JSONArray().put("get").put("point_count_abbreviated")).put("text-font", JSONArray().put("Noto Sans Regular")).put("text-size", 12), JSONArray().put("has").put("point_count"))
        }
        val noCluster = JSONArray().put("!").put(JSONArray().put("has").put("point_count"))
        val selected = JSONArray().put("case").put(JSONArray().put("==").put(JSONArray().put("get").put("itemId")).put(model.optString("selectedMarkerId"))).put(10).put(7)
        layer("__maps_marker_points", "circle", MARKERS, JSONObject().put("circle-color", property("color", "#2563eb")).put("circle-radius", selected).put("circle-stroke-color", "#ffffff").put("circle-stroke-width", 2), filter = noCluster)
        layer("__maps_marker_labels", "symbol", MARKERS, JSONObject().put("text-color", "#111827").put("text-halo-color", "#ffffff").put("text-halo-width", 1), JSONObject().put("text-field", property("title", "")).put("text-font", JSONArray().put("Noto Sans Regular")).put("text-size", 12).put("text-offset", JSONArray().put(0).put(1.5)).put("icon-image", property("icon", "")).put("icon-allow-overlap", true), noCluster)
        source("__maps_lines", features(model.optJSONArray("lines")) { JSONObject().put("type", "LineString").put("coordinates", line(it.getJSONArray("coordinates"))) })
        layer("__maps_line_layer", "line", "__maps_lines", JSONObject().put("line-color", property("color", "#2563eb")).put("line-width", property("width", 4)).put("line-opacity", property("opacity", 1)))
        val polygons = features(model.optJSONArray("polygons")) { item ->
            val rings = item.getJSONArray("coordinates")
            JSONObject().put("type", "Polygon").put("coordinates", JSONArray().apply { for (index in 0 until rings.length()) put(ring(rings.getJSONArray(index))) })
        }
        val circles = features(model.optJSONArray("circles"), ::circle).getJSONArray("features")
        for (index in 0 until circles.length()) polygons.getJSONArray("features").put(circles.getJSONObject(index))
        source("__maps_areas", polygons)
        layer("__maps_area_layer", "fill", "__maps_areas", JSONObject().put("fill-color", property("color", "#2563eb")).put("fill-opacity", property("opacity", 0.25)).put("fill-outline-color", property("outlineColor", "#1d4ed8")))
        source(LOCATION, features(null) { it })
        layer("__maps_location_point", "circle", LOCATION, JSONObject().put("circle-color", "#0284c7").put("circle-radius", 8).put("circle-stroke-color", "#ffffff").put("circle-stroke-width", 3))
        // SDK 11.8 exposes no public native feature-state setter. Emulate it for owned inline GeoJSON.
        sources.keys().forEach { sourceId ->
            val value = sources.getJSONObject(sourceId)
            val data = value.optJSONObject("data") ?: return@forEach
            val collection = if (data.optString("type") == "Feature") JSONArray().put(data) else data.optJSONArray("features")
            if (collection != null) for (index in 0 until collection.length()) {
                val feature = collection.getJSONObject(index)
                val state = states["$sourceId:${feature.opt("id")}"] ?: continue
                val props = feature.optJSONObject("properties") ?: JSONObject().also { feature.put("properties", it) }
                state.keys().forEach { key -> props.put("__maps_state_$key", state.get(key)) }
            }
        }
        fun rewrite(value: Any?): Any? {
            if (value is JSONArray) {
                if (value.length() == 2 && value.optString(0) == "feature-state") return JSONArray().put("get").put("__maps_state_${value.getString(1)}")
                if (value.optString(0) == "literal") return value
                for (index in 0 until value.length()) value.put(index, rewrite(value.get(index)))
            } else if (value is JSONObject) value.keys().forEach { key -> value.put(key, rewrite(value.get(key))) }
            return value
        }
        rewrite(layers)
        val orderedLayers = (0 until layers.length()).map { layers.getJSONObject(it) }.sortedBy {
            when (it.getString("id")) {
                "__maps_area_layer" -> 1
                "__maps_line_layer" -> 2
                "__maps_clusters", "__maps_cluster_labels", "__maps_marker_points", "__maps_marker_labels" -> 3
                "__maps_location_point" -> 4
                else -> 0
            }
        }
        style.put("layers", JSONArray(orderedLayers))
        val ids = mutableSetOf<String>()
        for (index in 0 until layers.length()) require(ids.add(layers.getJSONObject(index).getString("id"))) { "Duplicate layer ID." }
        return style.toString()
    }
}
