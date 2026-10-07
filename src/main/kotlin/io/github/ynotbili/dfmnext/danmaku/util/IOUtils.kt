package io.github.ynotbili.dfmnext.danmaku.util

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.nio.charset.Charset

object IOUtils {

    /**
     * Explicit UTF-8. The previous `String(bytes)` used the platform default
     * charset, which is only UTF-8 by convention — a Bilibili xml file with a
     * legacy declaration parsed as ???? instead of the intended text.
     */
    private val UTF_8: Charset = Charset.forName("UTF-8")

    fun getString(input: InputStream): String? {
        val data = getBytes(input) ?: return null
        return String(data, UTF_8)
    }

    /**
     * Fully reads and closes [input]. A pre-sized [ByteArrayOutputStream] avoids
     * the repeated doubling copies a default-sized one makes for a multi-megabyte
     * danmaku file.
     */
    fun getBytes(input: InputStream): ByteArray? {
        return try {
            input.use { stream ->
                val baos = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE.coerceAtLeast(stream.available()))
                stream.copyTo(baos, DEFAULT_BUFFER_SIZE)
                baos.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
