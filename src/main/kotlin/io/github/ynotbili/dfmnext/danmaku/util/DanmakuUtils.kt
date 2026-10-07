package io.github.ynotbili.dfmnext.danmaku.util

import io.github.ynotbili.dfmnext.danmaku.model.AbsDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.android.DrawingCache
import kotlin.math.abs
import kotlin.math.ceil

object DanmakuUtils {

    fun willHitInDuration(
        disp: IDisplayer, d1: BaseDanmaku, d2: BaseDanmaku,
        duration: Long, currTime: Long
    ): Boolean {
        val type1 = d1.getType()
        // Different lanes never collide by design; bail out before touching d2.
        if (type1 != d2.getType()) return false
        if (d1.isOutside()) return false

        val dTime = d2.time - d1.time
        if (dTime <= 0) return true
        if (abs(dTime) >= duration || d1.isTimeOut() || d2.isTimeOut()) return false

        if (type1 == BaseDanmaku.TYPE_FIX_TOP || type1 == BaseDanmaku.TYPE_FIX_BOTTOM) return true

        return checkHitAtTime(disp, d1, d2, currTime) ||
            checkHitAtTime(disp, d1, d2, d1.time + d1.getDuration())
    }

    private fun checkHitAtTime(
        disp: IDisplayer, d1: BaseDanmaku, d2: BaseDanmaku, time: Long
    ): Boolean {
        val rectArr1 = d1.getRectAtTime(disp, time) ?: return false
        val rectArr2 = d2.getRectAtTime(disp, time) ?: return false
        return checkHit(d1.getType(), d2.getType(), rectArr1, rectArr2)
    }

    private fun checkHit(type1: Int, type2: Int, rectArr1: FloatArray, rectArr2: FloatArray): Boolean {
        if (type1 != type2) return false
        return when (type1) {
            BaseDanmaku.TYPE_SCROLL_RL -> rectArr2[0] < rectArr1[2]
            BaseDanmaku.TYPE_SCROLL_LR -> rectArr2[2] > rectArr1[0]
            else -> false
        }
    }

    fun buildDanmakuDrawingCache(
        danmaku: BaseDanmaku, disp: IDisplayer, cache: DrawingCache?
    ): DrawingCache {
        val drawingCache = cache ?: DrawingCache()
        drawingCache.build(
            ceil(danmaku.paintWidth.toDouble()).toInt(),
            ceil(danmaku.paintHeight.toDouble()).toInt(),
            disp.densityDpi,
            false,
        )
        val holder = drawingCache.get() ?: return drawingCache
        (disp as AbsDisplayer).drawDanmaku(danmaku, holder.canvas!!, 0f, 0f, true)
        if (disp.isHardwareAccelerated) {
            holder.splitWith(
                disp.width, disp.height,
                disp.maximumCacheWidth, disp.maximumCacheHeight,
            )
        }
        return drawingCache
    }

    fun getCacheSize(w: Int, h: Int): Int = w * h * 4

    fun isDuplicate(obj1: BaseDanmaku, obj2: BaseDanmaku): Boolean {
        if (obj1 === obj2) return false
        val t1 = obj1.text
        val t2 = obj2.text
        if (t1 === null || t2 === null) return false
        // Identity first: parsed batches intern repeated strings, so most
        // duplicate checks are settled without touching characters at all.
        return t1 === t2 || contentEquals(t1, t2)
    }

    /**
     * Ordering used by the time-sorted danmaku collections.
     *
     * Deliberately allocation free: the previous implementation called
     * `text.toString()` on both operands for every comparison, which meant a
     * `TreeSet` insert/search of a 10k-danmaku list generated tens of
     * thousands of throwaway strings.
     */
    fun compare(obj1: BaseDanmaku, obj2: BaseDanmaku): Int {
        if (obj1 === obj2) return 0

        val timeDiff = obj1.time.compareTo(obj2.time)
        if (timeDiff != 0) return timeDiff

        val indexDiff = obj1.index.compareTo(obj2.index)
        if (indexDiff != 0) return indexDiff

        val typeDiff = obj1.getType() - obj2.getType()
        if (typeDiff != 0) return typeDiff

        val colorDiff = obj1.textColor.compareTo(obj2.textColor)
        if (colorDiff != 0) return colorDiff

        val textResult = compareContent(obj1.text, obj2.text)
        if (textResult != 0) return textResult

        // Stable, total tie-break without hashing the text again.
        return System.identityHashCode(obj1).compareTo(System.identityHashCode(obj2))
    }

    /** CharSequence comparison that never copies the underlying text. */
    internal fun contentEquals(a: CharSequence, b: CharSequence): Boolean = compareContent(a, b) == 0

    private fun compareContent(a: CharSequence?, b: CharSequence?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1
        val len = minOf(a.length, b.length)
        for (i in 0 until len) {
            val diff = a[i] - b[i]
            if (diff != 0) return if (diff < 0) -1 else 1
        }
        return a.length.compareTo(b.length)
    }

    fun isOverSize(disp: IDisplayer, item: BaseDanmaku): Boolean =
        disp.isHardwareAccelerated &&
            (item.paintWidth > disp.maximumCacheWidth || item.paintHeight > disp.maximumCacheHeight)

    /**
     * Splits the "/n" pseudo-newline marker. Uses `indexOf` instead of
     * `contains`+`split` so parsing does not compile a regex or build an
     * intermediate string list for every danmaku.
     */
    fun fillText(danmaku: BaseDanmaku, text: CharSequence?) {
        danmaku.text = text
        if (text == null) return
        val separator = BaseDanmaku.DANMAKU_BR_CHAR
        val first = indexOfToken(text, separator, 0)
        if (first < 0) return

        val lines = ArrayList<String>(4)
        var start = 0
        var cursor = first
        while (cursor >= 0) {
            lines.add(text.substring(start, cursor))
            start = cursor + separator.length
            cursor = indexOfToken(text, separator, start)
        }
        lines.add(text.substring(start))
        // String.split("/") drops trailing empty segments; match that here so
        // multi-line metrics keep behaving as before.
        while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        if (lines.size > 1) danmaku.lines = lines.toTypedArray()
    }

    /** Token search that never builds an intermediate String for [token]. */
    private fun indexOfToken(text: CharSequence, token: String, from: Int): Int {
        if (text is String) return text.indexOf(token, from)
        val tokenLength = token.length
        val limit = text.length - tokenLength
        var i = from
        while (i <= limit) {
            if (text[i] == token[0]) {
                var j = 1
                while (j < tokenLength && text[i + j] == token[j]) j++
                if (j == tokenLength) return i
            }
            i++
        }
        return -1
    }
}
