package dev.lynx.android.plugins.maps

import androidx.core.content.FileProvider

/** Distinct provider class prevents manifest collisions with the camera plugin. */
class MapSnapshotProvider : FileProvider()
