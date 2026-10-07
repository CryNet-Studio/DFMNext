package io.github.ynotbili.dfmnext.danmaku.model.android

import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Paint.Style
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import io.github.ynotbili.dfmnext.danmaku.model.AbsDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer
import io.github.ynotbili.dfmnext.danmaku.util.isSpecial

class AndroidDisplayer : AbsDisplayer() {

    private val camera = Camera()
    private val matrix = Matrix()

    private val UNDERLINE_HEIGHT = 4

    /**
     * Master paint carrying the user-facing style (typeface, fake bold, stroke
     * width). Drawing never mutates it directly — each thread gets its own
     * scratch copy via [obtainPaint].
     */
    val PAINT: TextPaint = TextPaint().apply { strokeWidth = STROKE_WIDTH_F }

    /**
     * Per-thread scratch paint.
     *
     * Previously there was a single shared `PAINT_DUPLICATE` handed to whichever
     * caller passed `quickly == false`, while the cache thread used `PAINT`.
     * Because measuring runs on the cache thread and drawing runs on the UI
     * thread at the same time, the shared duplicate could be reconfigured in the
     * middle of another thread's draw — a real visual-corruption race, on top of
     * the per-danmaku `TextPaint` churn the copy avoided.
     */
    private val scratchPaint = object : ThreadLocal<TextPaint>() {
        override fun initialValue(): TextPaint = TextPaint(PAINT)
    }

    /** Scratch `RectF` so a rounded background no longer allocates per item. */
    private val scratchRect = object : ThreadLocal<RectF>() {
        override fun initialValue(): RectF = RectF()
    }

    private val ALPHA_PAINT = Paint()
    private val UNDERLINE_PAINT = Paint().apply {
        strokeWidth = UNDERLINE_HEIGHT.toFloat()
        style = Style.STROKE
    }
    private val BORDER_PAINT = Paint().apply {
        style = Style.STROKE
        strokeWidth = BORDER_WIDTH.toFloat()
    }
    private val BACKGROUND_PAINT = Paint().apply { style = Style.FILL }

    var CONFIG_HAS_SHADOW = false
    var CONFIG_HAS_STROKE = true
    var CONFIG_HAS_PROJECTION = false
    val CONFIG_ANTI_ALIAS = true

    /**
     * Effective flags. These were previously mutable instance state written by
     * `drawDanmaku()` on the UI thread and read by the cache thread; they are now
     * derived from the CONFIG_ values only, and the per-draw overrides travel as
     * local variables.
     */
    private val hasShadow: Boolean get() = CONFIG_HAS_SHADOW
    private val hasStrokeConfig: Boolean get() = CONFIG_HAS_STROKE
    private val hasProjection: Boolean get() = CONFIG_HAS_PROJECTION

    private var sStuffer: BaseCacheStuffer = SimpleTextCacheStuffer()
    private var isTranslucent = false
    private var transparency = BaseDanmaku.ALPHA_MAX
    private var scaleTextSize = 1.0f
    private var isTextScaled = false

    private var SHADOW_RADIUS = 4.0f
    private var STROKE_WIDTH = 3.5f
    private var sProjectionOffsetX = 1.0f
    private var sProjectionOffsetY = 1.0f
    private var sProjectionAlpha = 0xCC

    /**
     * Text-size -> scaled-size lookup, per thread and bounded. The old shared
     * `HashMap` grew without limit (one entry per distinct text size ever seen),
     * leaked across config changes, and was read/written by two threads.
     */
    private val scaleCache = object : ThreadLocal<TextSizeScaleCache>() {
        override fun initialValue(): TextSizeScaleCache = TextSizeScaleCache()
    }

    private class TextSizeScaleCache {
        var factor = 1.0f
        val sizes = HashMap<Float, Float>(8)
    }

    private var _canvas: Canvas? = null
    private var _width = 0
    private var _height = 0
    private var _density = 1f
    private var _densityDpi = 160
    private var _scaledDensity = 1f
    private var mSlopPixel = 0
    private var mIsHardwareAccelerated = true
    private var mMaximumBitmapWidth = 2048
    private var mMaximumBitmapHeight = 2048

    private fun update(c: Canvas?) {
        _canvas = c
        if (c != null) {
            _width = c.width
            _height = c.height
            if (mIsHardwareAccelerated) {
                mMaximumBitmapWidth = c.maximumBitmapWidth
                mMaximumBitmapHeight = c.maximumBitmapHeight
            }
        }
    }

    override fun setTypeFace(font: Typeface) {
        PAINT.typeface = font
    }

    fun setShadowRadius(s: Float) { SHADOW_RADIUS = s }

    fun setPaintStorkeWidth(s: Float) {
        PAINT.strokeWidth = s
        STROKE_WIDTH = s
    }

    fun setProjectionConfig(offsetX: Float, offsetY: Float, alpha: Int) {
        sProjectionOffsetX = if (offsetX > 1.0f) offsetX else 1.0f
        sProjectionOffsetY = if (offsetY > 1.0f) offsetY else 1.0f
        sProjectionAlpha = alpha.coerceIn(0, 255)
    }

    override fun setFakeBoldText(bold: Boolean) { PAINT.isFakeBoldText = bold }

    override fun setTransparency(newTransparency: Int) {
        isTranslucent = newTransparency != BaseDanmaku.ALPHA_MAX
        transparency = newTransparency
    }

    override fun setScaleTextSizeFactor(factor: Float) {
        isTextScaled = factor != 1f
        scaleTextSize = factor
    }

    override fun setCacheStuffer(cacheStuffer: BaseCacheStuffer) {
        if (cacheStuffer !== sStuffer) sStuffer = cacheStuffer
    }

    override fun getCacheStuffer(): BaseCacheStuffer = sStuffer

    override val width: Int get() = _width
    override val height: Int get() = _height
    override val density: Float get() = _density
    override val densityDpi: Int get() = _densityDpi
    override val scaledDensity: Float get() = _scaledDensity
    override val slopPixel: Int get() = mSlopPixel
    override val isHardwareAccelerated: Boolean get() = mIsHardwareAccelerated
    override val maximumCacheWidth: Int get() = mMaximumBitmapWidth
    override val maximumCacheHeight: Int get() = mMaximumBitmapHeight

    override fun draw(danmaku: BaseDanmaku): Int {
        val top = danmaku.getTop()
        val left = danmaku.getLeft()
        val c = _canvas ?: return IRenderer.NOTHING_RENDERING

        var alphaPaint: Paint? = null
        var needRestore = false
        if (danmaku.isSpecial) {
            if (danmaku.getAlpha() == BaseDanmaku.ALPHA_TRANSPARENT) return IRenderer.NOTHING_RENDERING
            if (danmaku.rotationZ != 0f || danmaku.rotationY != 0f) {
                saveCanvas(danmaku, c, left, top)
                needRestore = true
            }
            val alpha = danmaku.getAlpha()
            if (alpha != BaseDanmaku.ALPHA_MAX) {
                alphaPaint = ALPHA_PAINT
                alphaPaint.alpha = alpha
            }
        }

        if (alphaPaint != null && alphaPaint.alpha == BaseDanmaku.ALPHA_TRANSPARENT) {
            if (needRestore) c.restore()
            return IRenderer.NOTHING_RENDERING
        }

        var cacheDrawn = false
        var result = IRenderer.CACHE_RENDERING
        if (danmaku.hasDrawingCache()) {
            val holder = (danmaku.cache as? DrawingCache)?.get()
            if (holder != null) {
                cacheDrawn = holder.draw(c, left, top, alphaPaint)
            }
        }
        if (!cacheDrawn) {
            val paint = obtainPaint()
            if (alphaPaint != null) {
                paint.alpha = alphaPaint.alpha
            } else {
                resetPaintAlpha(paint)
            }
            drawDanmaku(danmaku, c, left, top, false)
            result = IRenderer.TEXT_RENDERING
        }

        if (needRestore) c.restore()
        return result
    }

    /** This thread's scratch paint, refreshed from the master style. */
    private fun obtainPaint(): TextPaint {
        val paint = scratchPaint.get()!!
        paint.set(PAINT)
        return paint
    }

    private fun resetPaintAlpha(paint: Paint) {
        if (paint.alpha != BaseDanmaku.ALPHA_MAX) paint.alpha = BaseDanmaku.ALPHA_MAX
    }

    private fun saveCanvas(danmaku: BaseDanmaku, canvas: Canvas, left: Float, top: Float): Int {
        camera.save()
        camera.rotateY(-danmaku.rotationY)
        camera.rotateZ(-danmaku.rotationZ)
        camera.getMatrix(matrix)
        matrix.preTranslate(-left, -top)
        matrix.postTranslate(left, top)
        camera.restore()
        val count = canvas.save()
        canvas.concat(matrix)
        return count
    }

    override fun drawDanmaku(danmaku: BaseDanmaku, canvas: Canvas, left: Float, top: Float, quickly: Boolean) {
        val _left = left
        val _top = top
        var adjLeft = left + danmaku.padding
        var adjTop = top + danmaku.padding
        if (danmaku.borderColor != 0) {
            adjLeft += BORDER_WIDTH
            adjTop += BORDER_WIDTH
        }

        // Per-draw style state stays local: no shared mutable fields.
        val projection = hasProjection
        val strokeEnabled = hasStrokeConfig
        val antiAlias = quickly && CONFIG_ANTI_ALIAS
        val paint = getPaint(danmaku, quickly, antiAlias)
        val strokeWidth = if (strokeEnabled || projection) STROKE_WIDTH else 0f

        sStuffer.drawBackground(danmaku, canvas, _left, _top)

        if (danmaku.backgroundColor != 0) {
            val bgPaint = getBackgroundPaint(danmaku)
            val rect = scratchRect.get()!!
            rect.set(_left, _top, _left + danmaku.paintWidth, _top + danmaku.paintHeight)
            canvas.drawRoundRect(rect, danmaku.backgroundRadius.toFloat(), danmaku.backgroundRadius.toFloat(), bgPaint)
        }

        val lines = danmaku.lines
        if (lines != null) {
            if (lines.size == 1) {
                drawLine(danmaku, lines[0], canvas, paint, adjLeft, adjTop, strokeWidth, projection, quickly)
            } else {
                val textHeight = (danmaku.paintHeight - 2 * danmaku.padding) / lines.size
                for (t in lines.indices) {
                    val line = lines[t]
                    if (line.isNullOrEmpty()) continue
                    drawLine(danmaku, line, canvas, paint, adjLeft, adjTop + t * textHeight, strokeWidth, projection, quickly)
                }
            }
        } else {
            drawLine(danmaku, null, canvas, paint, adjLeft, adjTop, strokeWidth, projection, quickly)
        }

        if (danmaku.underlineColor != 0) {
            val linePaint = getUnderlinePaint(danmaku)
            val bottom = _top + danmaku.paintHeight - UNDERLINE_HEIGHT
            canvas.drawLine(_left, bottom, _left + danmaku.paintWidth, bottom, linePaint)
        }

        if (danmaku.borderColor != 0) {
            val borderPaint = getBorderPaint(danmaku)
            canvas.drawRect(_left, _top, _left + danmaku.paintWidth, _top + danmaku.paintHeight, borderPaint)
        }
    }

    private fun drawLine(
        danmaku: BaseDanmaku,
        lineText: String?,
        canvas: Canvas,
        paint: TextPaint,
        left: Float,
        top: Float,
        strokeWidth: Float,
        projection: Boolean,
        quickly: Boolean,
    ) {
        if (hasStroke(danmaku, strokeWidth)) {
            applyPaintConfig(danmaku, paint, true, projection)
            var strokeLeft = left
            var strokeTop = top - paint.ascent()
            if (projection) {
                strokeLeft += sProjectionOffsetX
                strokeTop += sProjectionOffsetY
            }
            sStuffer.drawStroke(danmaku, lineText, canvas, strokeLeft, strokeTop, paint)
        }
        applyPaintConfig(danmaku, paint, false, projection)
        sStuffer.drawText(danmaku, lineText, canvas, left, top - paint.ascent(), paint, quickly)
    }

    private fun hasStroke(danmaku: BaseDanmaku, strokeWidth: Float): Boolean =
        (hasStrokeConfig || hasProjection) && strokeWidth > 0 && danmaku.textShadowColor != 0

    private fun getBorderPaint(danmaku: BaseDanmaku): Paint {
        BORDER_PAINT.color = danmaku.borderColor
        return BORDER_PAINT
    }

    private fun getUnderlinePaint(danmaku: BaseDanmaku): Paint {
        UNDERLINE_PAINT.color = danmaku.underlineColor
        return UNDERLINE_PAINT
    }

    private fun getBackgroundPaint(danmaku: BaseDanmaku): Paint {
        BACKGROUND_PAINT.color = danmaku.backgroundColor
        return BACKGROUND_PAINT
    }

    private fun getPaint(danmaku: BaseDanmaku, quickly: Boolean, antiAlias: Boolean): TextPaint {
        // Always the thread-confined scratch paint: the cache thread and the UI
        // thread can never observe each other's configuration.
        val paint = obtainPaint()
        paint.textSize = danmaku.textSize
        applyTextScaleConfig(danmaku, paint)
        if (!hasShadow || SHADOW_RADIUS <= 0 || danmaku.textShadowColor == 0) {
            paint.clearShadowLayer()
        } else {
            paint.setShadowLayer(SHADOW_RADIUS, 0f, 0f, danmaku.textShadowColor)
        }
        paint.isAntiAlias = antiAlias
        return paint
    }

    private fun applyPaintConfig(danmaku: BaseDanmaku, paint: Paint, stroke: Boolean, projection: Boolean) {
        if (isTranslucent) {
            if (stroke) {
                paint.style = if (projection) Style.FILL else Style.STROKE
                paint.color = danmaku.textShadowColor and 0x00FFFFFF
                paint.alpha = if (projection) {
                    (sProjectionAlpha * (transparency.toFloat() / BaseDanmaku.ALPHA_MAX)).toInt()
                } else transparency
            } else {
                paint.style = Style.FILL
                paint.color = danmaku.textColor and 0x00FFFFFF
                paint.alpha = transparency
            }
        } else {
            if (stroke) {
                paint.style = if (projection) Style.FILL else Style.STROKE
                paint.color = danmaku.textShadowColor and 0x00FFFFFF
                paint.alpha = if (projection) sProjectionAlpha else BaseDanmaku.ALPHA_MAX
            } else {
                paint.style = Style.FILL
                paint.color = danmaku.textColor and 0x00FFFFFF
                paint.alpha = BaseDanmaku.ALPHA_MAX
            }
        }
    }

    private fun applyTextScaleConfig(danmaku: BaseDanmaku, paint: Paint) {
        if (!isTextScaled) return
        val cache = scaleCache.get()!!
        if (cache.factor != scaleTextSize) {
            cache.factor = scaleTextSize
            cache.sizes.clear()
        }
        val size = cache.sizes[danmaku.textSize]
            ?: (danmaku.textSize * scaleTextSize).also { cache.sizes[danmaku.textSize] = it }
        paint.textSize = size
    }

    override fun measure(danmaku: BaseDanmaku, fromWorkerThread: Boolean) {
        val paint = getPaint(danmaku, fromWorkerThread, CONFIG_ANTI_ALIAS)
        if (hasStrokeConfig) applyPaintConfig(danmaku, paint, true, hasProjection)
        sStuffer.measure(danmaku, paint, fromWorkerThread)
        setDanmakuPaintWidthAndHeight(danmaku, danmaku.paintWidth, danmaku.paintHeight)
        if (hasStrokeConfig) applyPaintConfig(danmaku, paint, false, hasProjection)
    }

    private fun setDanmakuPaintWidthAndHeight(danmaku: BaseDanmaku, w: Float, h: Float) {
        var pw = w + 2 * danmaku.padding
        var ph = h + 2 * danmaku.padding
        if (danmaku.borderColor != 0) {
            pw += 2 * BORDER_WIDTH
            ph += 2 * BORDER_WIDTH
        }
        danmaku.paintWidth = pw + strokeWidth
        danmaku.paintHeight = ph
    }

    override fun clearTextHeightCache() {
        sStuffer.clearCaches()
        scaleCache.get()?.let {
            it.factor = 0f
            it.sizes.clear()
        }
    }

    override fun resetSlopPixel(factor: Float) {
        val d = maxOf(factor, width / DanmakuFactory.BILI_PLAYER_WIDTH)
        val slop = d * DanmakuFactory.DANMAKU_MEDIUM_TEXTSIZE
        mSlopPixel = if (factor > 1f) (slop * factor).toInt() else slop.toInt()
    }

    override fun setDensities(density: Float, densityDpi: Int, scaledDensity: Float) {
        this._density = density
        this._densityDpi = densityDpi
        this._scaledDensity = scaledDensity
    }

    override fun setSize(width: Int, height: Int) {
        this._width = width
        this._height = height
    }

    override fun setDanmakuStyle(style: Int, data: FloatArray) {
        when (style) {
            IDisplayer.DANMAKU_STYLE_NONE -> {
                CONFIG_HAS_SHADOW = false; CONFIG_HAS_STROKE = false; CONFIG_HAS_PROJECTION = false
            }
            IDisplayer.DANMAKU_STYLE_SHADOW -> {
                CONFIG_HAS_SHADOW = true; CONFIG_HAS_STROKE = false; CONFIG_HAS_PROJECTION = false
                setShadowRadius(data[0])
            }
            IDisplayer.DANMAKU_STYLE_DEFAULT, IDisplayer.DANMAKU_STYLE_STROKEN -> {
                CONFIG_HAS_SHADOW = false; CONFIG_HAS_STROKE = true; CONFIG_HAS_PROJECTION = false
                setPaintStorkeWidth(data[0])
            }
            IDisplayer.DANMAKU_STYLE_PROJECTION -> {
                CONFIG_HAS_SHADOW = false; CONFIG_HAS_STROKE = false; CONFIG_HAS_PROJECTION = true
                setProjectionConfig(data[0], data[1], data[2].toInt())
            }
        }
    }

    override fun setExtraData(data: Canvas) { update(data) }
    override fun getExtraData(): Canvas? = _canvas

    override val strokeWidth: Float
        get() = when {
            CONFIG_HAS_SHADOW && CONFIG_HAS_STROKE -> maxOf(SHADOW_RADIUS, STROKE_WIDTH)
            CONFIG_HAS_SHADOW -> SHADOW_RADIUS
            CONFIG_HAS_STROKE -> STROKE_WIDTH
            else -> 0f
        }

    override fun setHardwareAccelerated(enable: Boolean) { mIsHardwareAccelerated = enable }

    companion object {
        const val BORDER_WIDTH = 4
        private const val STROKE_WIDTH_F = 3.5f
    }
}
