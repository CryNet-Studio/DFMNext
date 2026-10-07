package io.github.ynotbili.dfmnext.danmaku.parser.android

import android.net.Uri
import io.github.ynotbili.dfmnext.danmaku.parser.IDataSource
import io.github.ynotbili.dfmnext.danmaku.util.IOUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.MalformedURLException
import java.net.URL

class AndroidFileSource : IDataSource<InputStream> {

    private var inStream: InputStream? = null

    constructor(filepath: String) {
        fillStreamFromFile(File(filepath))
    }

    constructor(uri: Uri) {
        fillStreamFromUri(uri)
    }

    constructor(file: File) {
        fillStreamFromFile(file)
    }

    constructor(stream: InputStream) {
        this.inStream = stream
    }

    fun fillStreamFromFile(file: File) {
        try {
            inStream = BufferedInputStream(FileInputStream(file))
        } catch (e: FileNotFoundException) {
            // File not found
        }
    }

    fun fillStreamFromUri(uri: Uri) {
        val scheme = uri.scheme
        if (IDataSource.SCHEME_HTTP_TAG.equals(scheme, ignoreCase = true) ||
            IDataSource.SCHEME_HTTPS_TAG.equals(scheme, ignoreCase = true)
        ) {
            fillStreamFromHttpFile(uri)
        } else if (IDataSource.SCHEME_FILE_TAG.equals(scheme, ignoreCase = true)) {
            // Uri.path is nullable, so File(uri.path) used to compile only through
            // a platform-type waiver and threw a KotlinNpe-style failure on opaque
            // uris. Return early instead.
            val path = uri.path ?: return
            fillStreamFromFile(File(path))
        }
    }

    fun fillStreamFromHttpFile(uri: Uri) {
        // An http(s) Uri always carries its value in the authority + path, so the
        // old `URL(uri.path)` call reconstructed the endpoint from a partial
        // string. `uri.toString()` is the complete, already-encoded form.
        try {
            val url = URL(uri.toString())
            inStream = BufferedInputStream(url.openStream())
        } catch (e: MalformedURLException) {
            // Invalid URL
        } catch (e: IOException) {
            // IO error
        }
    }

    override fun release() {
        IOUtils.closeQuietly(inStream)
        inStream = null
    }

    override fun data(): InputStream? = inStream
}
