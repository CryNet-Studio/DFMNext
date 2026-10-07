package io.github.ynotbili.dfmnext.danmaku.model.android

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import java.util.concurrent.ConcurrentHashMap

open class SimpleTextCacheStuffer : BaseCacheStuffer() {

    /**
     * Line height per text size. This used to be a plain `static HashMap`
     * mutated from both the UI thread and the cache-building thread, which is
     * an undefined-behaviour race (and on older platforms a corruption source).
     * Reading `paint.fontMetrics` also allocates a `FontMetrics` object per
     * call, so the metrics are hoisted into a thread-local.
     */
    private val textHeightCache = ConcurrentHashMap<Float, Float>()

    protected fun getCacheHeight(danmaku: BaseDanmaku, paint: Paint): Float {
        val textSize = paint.textSize
        textHeightCache[textSize]?.let { return it }
        // Only reached once per text size, so the metrics object is not hot.
        val metrics = Paint.FontMetricsInt()
        paint.getFontMetricsInt(metrics)
        val height = (metrics.descent - metrics.ascent + metrics.leading).toFloat()
        textHeightCache[textSize] = height
        return height
    }

    override fun measure(danmaku: BaseDanmaku, paint: TextPaint, fromWorkerThread: Boolean) {
        mProxy?.prepareDrawing(danmaku, fromWorkerThread)

        var w = 0f
        var textHeight = 0f
        val lines = danmaku.lines
        if (lines == null) {
            val text = danmaku.text
            if (text != null) {
                // measureText on the CharSequence avoids a toString() copy for
                // every measured danmaku (Spanned / parsed slices alike).
                w = measureText(paint, text)
                textHeight = getCacheHeight(danmaku, paint)
            }
            danmaku.paintWidth = w
            danmaku.paintHeight = textHeight
        } else {
            textHeight = getCacheHeight(danmaku, paint)
            for (tempStr in lines) {
                if (tempStr.isNotEmpty()) {
                    val tr = paint.measureText(tempStr)
                    if (tr > w) w = tr
                }
            }
            danmaku.paintWidth = w
            danmaku.paintHeight = lines.size * textHeight
        }
    }

    private fun measureText(paint: TextPaint, text: CharSequence): Float = when (text) {
        is String -> paint.measureText(text)
        else -> paint.measureText(text, 0, text.length)
    }

    override fun drawStroke(danmaku: BaseDanmaku, lineText: String?, canvas: Canvas, left: Float, top: Float, paint: Paint) {
        if (lineText != null) {
            canvas.drawText(lineText, left, top, paint)
        } else {
            drawText(danmaku, danmaku.text, canvas, left, top, paint)
        }
    }

    override fun drawText(danmaku: BaseDanmaku, lineText: String?, canvas: Canvas, left: Float, top: Float, paint: TextPaint, fromWorkerThread: Boolean) {
        if (lineText != null) {
            canvas.drawText(lineText, left, top, paint)
        } else {
            drawText(danmaku, danmaku.text, canvas, left, top, paint)
        }
    }

    private fun drawText(danmaku: BaseDanmaku, text: CharSequence?, canvas: Canvas, left: Float, top: Float, paint: Paint) {
        when (text) {
            null -> return
            is String -> canvas.drawText(text, left, top, paint)
            else -> canvas.drawText(text, 0, text.length, left, top, paint)
        }
    }

    override fun clearCaches() {
        textHeightCache.clear()
    }

    override fun drawBackground(danmaku: BaseDanmaku, canvas: Canvas, left: Float, top: Float) {}
}

