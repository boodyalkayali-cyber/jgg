package com.scrollhub

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

data class MediaEntry(
    val key: String,
    val type: String,   // image | video
    val url: String,
    val poster: String,
    val link: String,
    val hls: Boolean
)

object MediaUtils {
    const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun download(url: String, dest: File, referer: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        if (referer.isNotBlank()) c.setRequestProperty("Referer", referer)
        CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        if (c.responseCode !in 200..299) return null
        c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        return c.contentType ?: ""
    }

    fun guessMime(ct: String, type: String): String {
        val m = ct.substringBefore(';').trim()
        if (m.startsWith("image/") || m.startsWith("video/")) return m
        return if (type == "video") "video/mp4" else "image/jpeg"
    }

    fun extOf(mime: String) = when {
        mime.contains("jpeg") -> "jpg"
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.contains("webm") -> "webm"
        mime.startsWith("video/") -> "mp4"
        mime == "audio/mp4" -> "m4a"
        else -> "bin"
    }

    fun safe(s: String) = s.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(50).ifBlank { "audio" }

    /** ينسخ الملف إلى المعرض/الموسيقى ليظهر في تطبيقات الجهاز */
    fun publish(ctx: Context, file: File, mime: String, name: String) {
        val (uri, rel) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/ScrollHub"
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/ScrollHub"
            else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/ScrollHub"
        }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val u = ctx.contentResolver.insert(uri, v) ?: return
        ctx.contentResolver.openOutputStream(u)?.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** استخراج المسار الصوتي كما هو (بدون إعادة ترميز) = أعلى جودة ممكنة */
    fun extractAudio(src: File, dst: File): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) return false
            ex.selectTrack(idx)
            val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val t = mux.addTrack(fmt)
            mux.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, ex.sampleTime, flags)
                mux.writeSampleData(t, buf, info)
                ex.advance()
            }
            mux.stop(); mux.release()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            ex.release()
        }
    }
}
