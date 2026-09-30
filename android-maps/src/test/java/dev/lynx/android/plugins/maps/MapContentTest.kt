package dev.lynx.android.plugins.maps

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MapContentTest {
    private val base = """{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"white"}}]}"""
    private fun build(model: String = "{}", states: Map<String, JSONObject> = emptyMap()) = JSONObject(MapContent.build(base, JSONObject(model), states))
    @Test fun newCameraDoesNotCreateImplicitMarker() {
        val style = build("""{"initialCamera":{"center":{"latitude":-33,"longitude":-71}}}""")
        assertEquals(0, style.getJSONObject("sources").getJSONObject(MapContent.MARKERS).getJSONObject("data").getJSONArray("features").length())
    }
    @Test fun preservesLegacyMarker() {
        val style = build("""{"latitude":-33,"longitude":-71}""")
        assertEquals(1, style.getJSONObject("sources").getJSONObject(MapContent.MARKERS).getJSONObject("data").getJSONArray("features").length())
    }
    @Test fun explicitEmptyMarkersSuppressLegacyMarker() {
        val style = build("""{"latitude":-33,"longitude":-71,"markers":[]}""")
        assertEquals(0, style.getJSONObject("sources").getJSONObject(MapContent.MARKERS).getJSONObject("data").getJSONArray("features").length())
    }
    @Test fun enablesClusteringAndUsesGeoJsonCoordinateOrder() {
        val style = build("""{"cluster":{"radius":60},"markers":[{"id":"a","position":{"latitude":-33,"longitude":-71}}]}""")
        val source = style.getJSONObject("sources").getJSONObject(MapContent.MARKERS)
        assertTrue(source.getBoolean("cluster")); assertEquals(60, source.getInt("clusterRadius"))
        val point = source.getJSONObject("data").getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(-71.0, point.getDouble(0), 0.0); assertEquals(-33.0, point.getDouble(1), 0.0)
    }
    @Test fun closesPolygonRingsAndPreservesHoles() {
        val style = build("""{"polygons":[{"id":"area","coordinates":[[{"latitude":0,"longitude":0},{"latitude":1,"longitude":0},{"latitude":1,"longitude":1}],[{"latitude":0.2,"longitude":0.2},{"latitude":0.3,"longitude":0.2},{"latitude":0.3,"longitude":0.3}]]}]}""")
        val rings = style.getJSONObject("sources").getJSONObject("__maps_areas").getJSONObject("data").getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(2, rings.length()); assertEquals(4, rings.getJSONArray(0).length())
        assertEquals(rings.getJSONArray(0).getJSONArray(0).toString(), rings.getJSONArray(0).getJSONArray(3).toString())
    }
    @Test fun createsGeodesicCircleWithClosedRing() {
        val style = build("""{"circles":[{"id":"circle","center":{"latitude":-33,"longitude":-71},"radius":100}]}""")
        val ring = style.getJSONObject("sources").getJSONObject("__maps_areas").getJSONObject("data").getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        assertEquals(65, ring.length()); assertEquals(ring.getJSONArray(0).toString(), ring.getJSONArray(64).toString())
    }
    @Test fun appliesFeatureStateWithoutMutatingInput() {
        val model = """{"sources":{"places":{"type":"geojson","data":{"type":"FeatureCollection","features":[{"type":"Feature","id":"a","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}}]}}},"layers":[{"id":"places","type":"circle","source":"places","paint":{"circle-radius":["case",["boolean",["feature-state","selected"],false],10,5]}}]}"""
        val style = build(model, mapOf("places:a" to JSONObject("""{"selected":true}""")))
        val feature = style.getJSONObject("sources").getJSONObject("places").getJSONObject("data").getJSONArray("features").getJSONObject(0)
        assertTrue(feature.getJSONObject("properties").getBoolean("__maps_state_selected"))
        assertTrue(style.getJSONArray("layers").toString().contains("__maps_state_selected"))
        assertFalse(model.contains("__maps_state_selected"))
    }
    @Test fun rejectsDuplicateLayerIds() {
        assertThrows(IllegalArgumentException::class.java) { build("""{"layers":[{"id":"background","type":"background"}]}""") }
    }
    @Test fun rejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException::class.java) { MapContent.coordinate(JSONObject("""{"latitude":100,"longitude":0}""")) }
    }
    @Test fun contentIsDeterministicAcrossRedraws() { assertEquals(build().toString(), build().toString()) }
}
