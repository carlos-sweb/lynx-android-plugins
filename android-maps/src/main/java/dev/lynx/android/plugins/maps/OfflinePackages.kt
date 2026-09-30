package dev.lynx.android.plugins.maps

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** App-private PMTiles catalog. Only verified, complete archives become active. */
internal class OfflinePackages(context: Context, private val changed: (JSONObject) -> Unit) {
    companion object {
        private val transfers = java.util.concurrent.ConcurrentHashMap<String, OfflinePackages>()
        private val readers = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        fun retain(file: File) { readers.computeIfAbsent(file.absolutePath) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
        fun release(file: File) { readers[file.absolutePath]?.decrementAndGet() }
    }
    private val application = context.applicationContext
    private val directory = File(application.filesDir, "lynx_maps").apply { mkdirs() }
    val configs = MapPackageConfig.readAll(application)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val jobs = mutableMapOf<String, Job>()
    @Volatile private var closed = false
    private class Job(val config: MapPackageConfig) {
        @Volatile var state = "missing"
        @Volatile var received = 0L
        @Volatile var total = config.size
        @Volatile var error: String? = null
        @Volatile var stopped = false
        @Volatile var discard = false
        @Volatile var connection: HttpsURLConnection? = null
        @Volatile var generation = 0
    }
    init { configs.forEach { config -> jobs[config.id] = Job(config).apply {
        received = partial(config).length()
        state = if (file(config).isFile) "verifying" else if (received > 0) "paused" else "missing"
    } }
        configs.filter { file(it).isFile }.forEach { config -> executor.execute {
            val job = job(config.id)
            val valid = runCatching { MapArchive.verified(file(config), config.sha256) }.getOrDefault(false)
            job.state = if (valid) "ready" else "error"; job.error = if (valid) null else "CHECKSUM_MISMATCH"
            if (valid) { job.received = file(config).length(); if (job.total == 0L) job.total = job.received }
            publish(job)
        } }
    }
    private fun job(id: String): Job = jobs[id] ?: throw IllegalArgumentException("Unknown map package: $id")
    fun config(id: String?): MapPackageConfig? = if (id.isNullOrBlank()) configs.firstOrNull() else configs.firstOrNull { it.id == id }
    fun file(config: MapPackageConfig): File = File(directory, "${config.id}-${config.sha256}.pmtiles")
    private fun partial(config: MapPackageConfig): File = File(directory, "${config.id}-${config.sha256}.part")
    fun status(id: String): JSONObject = job(id).let { job -> JSONObject().put("id", id).put("version", job.config.version).put("state", job.state).put("received", job.received).put("total", job.total).apply { job.error?.let { put("error", it) } } }
    fun list(): List<JSONObject> = configs.map { status(it.id) }
    private fun publish(job: Job) { val value = status(job.config.id); main.post { if (!closed) changed(value) } }
    fun cached(config: MapPackageConfig, done: (File?) -> Unit) {
        executor.execute {
            val target = file(config)
            val valid = target.isFile && job(config.id).state == "ready"
            if (!valid && target.isFile) { job(config.id).state = "error"; job(config.id).error = "CHECKSUM_MISMATCH" }
            main.post { if (!closed) done(if (valid) target else null) }
        }
    }
    fun operate(id: String, operation: String): JSONObject {
        val job = job(id)
        when (operation) {
            "download", "resume" -> {
                if (job.state !in listOf("downloading", "verifying", "ready")) {
                    require(transfers.putIfAbsent(file(job.config).absolutePath, this) == null || transfers[file(job.config).absolutePath] === this) { "Another map is downloading this package." }
                    job.stopped = false; job.discard = false; job.error = null; job.state = "downloading"
                    val generation = ++job.generation
                    executor.execute { transfer(job, generation) }
                }
            }
            "pause", "cancel" -> {
                require(job.state != "verifying") { "Wait for archive verification to finish." }
                job.stopped = true; job.generation++; job.discard = operation == "cancel"; job.connection?.disconnect()
                job.state = if (file(job.config).isFile) "ready" else if (operation == "cancel") "missing" else "paused"
                if (operation == "cancel") executor.execute { partial(job.config).delete(); job.received = file(job.config).length(); if (job.state != "downloading") publish(job) }
            }
            "remove" -> {
                require(job.state !in listOf("downloading", "verifying")) { "Cancel the download before removing it." }
                require(transfers[file(job.config).absolutePath] == null && (readers[file(job.config).absolutePath]?.get() ?: 0) == 0) { "Another map is using this package." }
                require(!file(job.config).exists() || file(job.config).delete()) { "Could not remove map archive." }
                require(!partial(job.config).exists() || partial(job.config).delete()) { "Could not remove partial map archive." }
                job.state = "missing"; job.received = 0
            }
            else -> throw IllegalArgumentException("Invalid offline operation.")
        }
        publish(job)
        return status(id)
    }
    private fun connect(url: String, offset: Long): HttpsURLConnection {
        var next = url
        repeat(6) { redirect ->
            val connection = URL(next).openConnection() as? HttpsURLConnection ?: error("HTTPS is required.")
            connection.instanceFollowRedirects = false; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            connection.connect()
            if (connection.responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(location != null && redirect < 5) { "Invalid map redirect." }
                next = URL(URL(next), location).toString()
                require(next.startsWith("https://")) { "HTTPS redirects are required." }
            } else return connection
        }
        error("Too many map redirects.")
    }
    private fun transfer(job: Job, generation: Int) {
        val temp = partial(job.config)
        fun stopped() = closed || job.generation != generation
        try {
            if (stopped()) return
            var offset = temp.length()
            var connection = connect(job.config.url, offset)
            if (connection.responseCode == 416 && offset > 0) {
                connection.disconnect()
                if (MapArchive.verified(temp, job.config.sha256)) {
                    if (stopped()) return
                    require(temp.renameTo(file(job.config))) { "DOWNLOAD_FAILED: Could not activate map." }
                    job.received = offset; job.total = offset; job.state = "ready"; publish(job); return
                }
                temp.delete(); offset = 0; connection = connect(job.config.url, 0)
            }
            job.connection = connection
            try {
                job.received = MapArchive.resumeOffset(connection.responseCode, connection.getHeaderField("Content-Range"), offset)
                val append = job.received > 0
                job.total = if (connection.responseCode == 206) MapArchive.rangeTotal(connection.getHeaderField("Content-Range")) ?: if (connection.contentLengthLong >= 0) job.received + connection.contentLengthLong else job.config.size
                else if (connection.contentLengthLong >= 0) connection.contentLengthLong else job.config.size
                require(job.total == 0L || directory.usableSpace > job.total - job.received + 8 * 1024 * 1024) { "INSUFFICIENT_STORAGE" }
                var reported = 0L
                connection.inputStream.use { input -> java.io.FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(65536)
                    while (!stopped()) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); job.received += count
                        require(directory.usableSpace > 1024 * 1024) { "INSUFFICIENT_STORAGE" }
                        val now = System.currentTimeMillis()
                        if (now - reported >= 250) { reported = now; publish(job) }
                    }
                    output.fd.sync()
                } }
                if (stopped()) return
                require(job.total == 0L || temp.length() == job.total) { "DOWNLOAD_FAILED: Incomplete download." }
                job.state = "verifying"; publish(job)
                require(MapArchive.verified(temp, job.config.sha256)) { "CHECKSUM_MISMATCH" }
                if (stopped()) return
                require(temp.renameTo(file(job.config))) { "DOWNLOAD_FAILED: Could not activate map." }
                job.state = "ready"; publish(job)
            } finally { connection.disconnect(); job.connection = null }
        } catch (error: Exception) {
            if (!stopped()) {
                job.state = "error"; job.error = error.message ?: "DOWNLOAD_FAILED"
                if (job.error == "CHECKSUM_MISMATCH") { temp.delete(); job.received = 0 }
                publish(job)
            }
        } finally {
            if (job.discard) temp.delete()
            if (job.generation == generation || closed || job.state !in listOf("downloading", "verifying")) transfers.remove(file(job.config).absolutePath, this)
        }
    }
    fun close() { closed = true; jobs.values.forEach { it.stopped = true; it.generation++; it.connection?.disconnect() }; executor.shutdown() }
}
