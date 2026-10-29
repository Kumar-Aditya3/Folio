package com.folio.reader.narration

import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

/** App-private PCM16, 24 kHz mono. Only complete, checksummed passages are published. */
class NarrationAudioCache(private val root: File) {
    @Volatile var maximumBytes: Long = 128L * 1024 * 1024
    private val lock = Any()
    private val activeParts = mutableSetOf<File>()
    private var generation = 0L
    fun key(configuration: NarrationConfiguration, spoken: String): String = digest(
        "pcm16-24k-v1|${NarrationPacks.forModel(configuration.model).revision}|${configuration.voiceId}|${configuration.phononVoiceId}|$spoken")
    private fun directory(bookId: String) = File(root, digest(bookId))
    private fun file(bookId: String, key: String): File {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return File(directory(bookId), "$key.pcm")
    }
    fun bytes(): Long = synchronized(lock) { entries().sumOf { it.length() } }
    fun clear(bookId: String? = null) = synchronized(lock) {
        generation++
        (bookId?.let(::directory) ?: root).takeIf { it.exists() }?.deleteRecursively()
        Unit
    }
    fun trim() = synchronized(lock) {
        root.listFiles()?.forEach { directory ->
            directory.listFiles()?.filter { it.extension == "part" && it !in activeParts }?.forEach { it.delete() }
        }
        val files = entries().sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (file in files) {
            if (bytes <= maximumBytes.coerceAtLeast(0)) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }
    private fun entries(): List<File> = root.listFiles()?.flatMap { it.listFiles()?.filter { f -> f.extension == "pcm" } ?: emptyList() } ?: emptyList()

    /** Null is a miss, false is cancellation. Corrupt files never reach the audio output. */
    fun replay(bookId: String, key: String, output: (FloatArray) -> Boolean): Boolean? {
        val path = file(bookId,key)
        val input = synchronized(lock) { runCatching { RandomAccessFile(path,"r") }.getOrNull() } ?: return null
        return input.use { data ->
            val valid = runCatching {
                check(data.readInt() == MAGIC)
                val samples = data.readInt()
                val checksum = data.readLong()
                check(samples in 1..MAX_SAMPLES && data.length() == HEADER + samples * 2L)
                val crc = CRC32(); val buffer = ByteArray(8192)
                while (true) { val n = data.read(buffer); if (n < 0) break; crc.update(buffer,0,n) }
                check(crc.value == checksum)
                data.seek(HEADER)
                samples
            }.getOrNull()
            if (valid == null) { runCatching { path.delete() }; return@use null }
            path.setLastModified(System.currentTimeMillis())
            var remaining = valid
            while (remaining > 0) {
                val count = minOf(1920,remaining)
                val pcm = FloatArray(count) { data.readShort() / 32768f }
                if (!output(pcm)) return@use false
                remaining -= count
            }
            true
        }
    }

    fun writer(bookId: String, key: String): Writer? = synchronized(lock) {
        if (maximumBytes <= 0) return null
        val target = file(bookId,key)
        runCatching {
            target.parentFile!!.mkdirs()
            // Stale partials from process death never count as reusable audio.
            target.parentFile!!.listFiles()?.filter { it.extension == "part" && System.currentTimeMillis()-it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            Writer(target,File(target.parentFile,"${UUID.randomUUID()}.part"),generation)
        }.getOrNull()
    }
    inner class Writer internal constructor(private val target: File, private val temporary: File, private val epoch: Long) : AutoCloseable {
        private val data = RandomAccessFile(temporary,"rw").apply { writeInt(MAGIC); writeInt(0); writeLong(0) }
        init { synchronized(lock) { activeParts += temporary } }
        private val crc = CRC32()
        private var count = 0
        private var closed = false
        private var usable = true
        fun append(pcm: FloatArray) {
            if (!usable || closed) return
            if (count + pcm.size > MAX_SAMPLES) { usable = false; return }
            try {
                val bytes = ByteArray(pcm.size * 2)
                pcm.forEachIndexed { i, f ->
                    val value = (f.coerceIn(-1f,1f) * 32767).toInt()
                    bytes[i*2] = (value shr 8).toByte(); bytes[i*2+1] = value.toByte()
                }
                data.write(bytes); crc.update(bytes); count += pcm.size
            } catch (_: IOException) { usable = false }
        }
        fun commit() {
            if (closed) return
            try {
                if (!usable || count == 0) return
                data.seek(4); data.writeInt(count); data.writeLong(crc.value); data.fd.sync()
                data.close(); closed = true
                synchronized(lock) {
                    if (epoch == generation && maximumBytes >= temporary.length()) {
                        if (target.exists()) target.delete()
                        temporary.renameTo(target)
                        trim()
                    }
                }
            } finally { close() }
        }
        override fun close() {
            if (!closed) { runCatching { data.close() }; closed = true }
            synchronized(lock) { activeParts -= temporary; temporary.delete() }
        }
    }
    companion object {
        private const val MAGIC = 0x46415031
        private const val HEADER = 16L
        private const val MAX_SAMPLES = 24_000 * 120
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
