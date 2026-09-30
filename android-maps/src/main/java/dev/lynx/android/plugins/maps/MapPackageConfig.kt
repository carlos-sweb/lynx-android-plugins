package dev.lynx.android.plugins.maps

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Backward-compatible, build-time catalog of immutable HTTPS PMTiles archives. */
internal data class MapPackageConfig(val url: String, val sha256: String, val version: String, val id: String = "chile", val label: String = id, val size: Long = 0) {
    companion object {
        fun readAll(context: Context): List<MapPackageConfig> = try {
            val json = JSONObject(context.assets.open("lynx_maps/config.json").bufferedReader().use { it.readText() })
            val packages = json.optJSONArray("packages") ?: JSONArray().put(JSONObject(json.toString()).put("id", "chile"))
            val ids = mutableSetOf<String>()
            (0 until packages.length()).map { index ->
                val item = packages.getJSONObject(index)
                val id = item.getString("id")
                val url = item.getString("url")
                val sha256 = item.getString("sha256").lowercase()
                val version = item.getString("version")
                require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && ids.add(id)) { "Invalid or duplicate map package ID." }
                require(url.startsWith("https://") && sha256.matches(Regex("[a-f0-9]{64}")) && version.isNotBlank() && item.optLong("size", 0) >= 0) { "Invalid map package configuration." }
                MapPackageConfig(url, sha256, version, id, item.optString("label", id), item.optLong("size", 0))
            }
        } catch (_: Exception) { emptyList() }
    }
}
