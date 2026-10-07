package io.github.ynotbili.dfmnext.bench

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku

/**
 * Verbatim copy of the pre-optimisation comparison helpers, kept so the
 * benchmark measures the real old code path (`text.toString()` per comparison)
 * rather than a paraphrase of it.
 */
object LegacyDanmakuUtils {

    fun isDuplicate(obj1: BaseDanmaku, obj2: BaseDanmaku): Boolean {
        if (obj1 === obj2) return false
        if (obj1.text == null || obj2.text == null) return false
        return obj1.text === obj2.text || obj1.text == obj2.text
    }

    fun compare(obj1: BaseDanmaku, obj2: BaseDanmaku): Int {
        if (obj1 === obj2) return 0

        val timeVal = obj1.time - obj2.time
        if (timeVal > 0) return 1 else if (timeVal < 0) return -1

        val indexResult = obj1.index - obj2.index
        if (indexResult > 0) return 1 else if (indexResult < 0) return -1

        val typeResult = obj1.getType() - obj2.getType()
        if (typeResult > 0) return 1 else if (typeResult < 0) return -1

        if (obj1.text == null) return -1
        if (obj2.text == null) return 1

        val r = obj1.text.toString().compareTo(obj2.text.toString())
        if (r != 0) return r

        val colorDiff = obj1.textColor - obj2.textColor
        if (colorDiff != 0) return if (colorDiff < 0) -1 else 1

        val idxDiff = obj1.index - obj2.index
        if (idxDiff != 0) return if (idxDiff < 0) -1 else 1

        return obj1.hashCode() - obj2.hashCode()
    }

    /** Old multi-line splitter: `toString()` plus a regex-backed `split` per item. */
    fun fillText(danmaku: BaseDanmaku, text: CharSequence?) {
        danmaku.text = text
        if (text == null || text.isEmpty() || !text.toString().contains(BaseDanmaku.DANMAKU_BR_CHAR)) return

        val lines = danmaku.text.toString().split(BaseDanmaku.DANMAKU_BR_CHAR).toTypedArray()
        if (lines.size > 1) {
            danmaku.lines = lines
        }
    }
}
