package io.github.ynotbili.dfmnext.danmaku.model.android

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import java.lang.ref.SoftReference
import kotlin.math.ceil
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku

class SpannedCacheStuffer : SimpleTextCacheStuffer() {

    override fun measure(danmaku: BaseDanmaku, paint: TextPaint, fromWorkerThread: Boolean) {
        if (danmaku.text is Spanned) {
            mProxy?.prepareDrawing(danmaku, fromWorkerThread)
            val text = danmaku.text
            if (text != null) {
                val staticLayout = newLayout(
                    text, paint,
                    ceil(StaticLayout.getDesiredWidth(text, paint).toDouble()).toInt()
                )
                danmaku.paintWidth = staticLayout.width.toFloat()
                danmaku.paintHeight = staticLayout.height.toFloat()
                danmaku.obj = SoftReference(staticLayout)
                return
            }
        }
        super.measure(danmaku, paint, fromWorkerThread)
    }

    override fun drawStroke(danmaku: BaseDanmaku, lineText: String?, canvas: Canvas, left: Float, top: Float, paint: Paint) {
        if (danmaku.obj == null) {
            super.drawStroke(danmaku, lineText, canvas, left, top, paint)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun drawText(danmaku: BaseDanmaku, lineText: String?, canvas: Canvas, left: Float, top: Float, paint: TextPaint, fromWorkerThread: Boolean) {
        if (danmaku.obj == null) {
            super.drawText(danmaku, lineText, canvas, left, top, paint, fromWorkerThread)
            return
        }

        val reference = danmaku.obj as? SoftReference<StaticLayout>
        var staticLayout = reference?.get()
        val requestRemeasure = 0 != (danmaku.requestFlags and BaseDanmaku.FLAG_REQUEST_REMEASURE)
        val requestInvalidate = 0 != (danmaku.requestFlags and BaseDanmaku.FLAG_REQUEST_INVALIDATE)

        if (requestInvalidate || staticLayout == null) {
            if (requestInvalidate) {
                danmaku.requestFlags = danmaku.requestFlags and BaseDanmaku.FLAG_REQUEST_INVALIDATE.inv()
            } else {
                mProxy?.prepareDrawing(danmaku, fromWorkerThread)
            }
            val text = danmaku.text
            if (text != null) {
                staticLayout = if (requestRemeasure) {
                    val layout = newLayout(
                        text, paint,
                        ceil(StaticLayout.getDesiredWidth(text, paint).toDouble()).toInt()
                    )
                    danmaku.paintWidth = layout.width.toFloat()
                    danmaku.paintHeight = layout.height.toFloat()
                    danmaku.requestFlags = danmaku.requestFlags and BaseDanmaku.FLAG_REQUEST_REMEASURE.inv()
                    layout
                } else {
                    newLayout(text, paint, danmaku.paintWidth.toInt())
                }
                danmaku.obj = SoftReference(staticLayout)
            } else {
                return
            }
        }

        var needRestore = false
        if (left != 0f && top != 0f) {
            canvas.save()
            canvas.translate(left, top + paint.ascent())
            needRestore = true
        }
        staticLayout.draw(canvas)
        if (needRestore) {
            canvas.restore()
        }
    }

    override fun clearCaches() {
        super.clearCaches()
    }

    override fun clearCache(danmaku: BaseDanmaku) {
        super.clearCache(danmaku)
        if (danmaku.obj is SoftReference<*>) {
            @Suppress("UNCHECKED_CAST")
            (danmaku.obj as SoftReference<Any>).clear()
        }
    }

    override fun releaseResource(danmaku: BaseDanmaku) {
        clearCache(danmaku)
        super.releaseResource(danmaku)
    }

    /**
     * `StaticLayout.Builder` is the supported entry point since API 23; the
     * seven-argument constructor is deprecated and, on newer platforms, just
     * forwards to the builder anyway. Below API 23 the constructor is the only
     * option, so the call is kept as the fallback.
     */
    private fun newLayout(source: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(source, 0, source.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0.0f, 1.0f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                source, paint, width,
                Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true
            )
        }
    }
}
