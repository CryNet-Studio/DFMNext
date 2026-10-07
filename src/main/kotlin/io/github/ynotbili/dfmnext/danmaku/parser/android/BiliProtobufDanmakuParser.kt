package io.github.ynotbili.dfmnext.danmaku.parser.android

import android.graphics.Color
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.SpecialDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuFactory
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import io.github.ynotbili.dfmnext.danmaku.parser.BaseDanmakuParser
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import org.json.JSONArray
import java.lang.reflect.Field

/**
 * Parses Bilibili's protobuf segment list without taking a protobuf dependency:
 * the fields are read reflectively from the app's generated messages.
 *
 * `Class.getField()` is a security-manager-checked lookup that walks the public
 * methods of the class and its interfaces. Doing it five times per danmaku — for
 * a typical file that is 50k lookups — dominated the parse, so the resolved
 * [Field]s are now cached per class and, more importantly, danmakus are
 * collected and sorted once via [Danmakus.setItems] instead of inserted into the
 * sorted container one at a time.
 */
class BiliProtobufDanmakuParser : BaseDanmakuParser() {

    private var mDispScaleX: Float = 0f
    private var mDispScaleY: Float = 0f
    private var mDanmakuSegments: List<*>? = null

    fun setDanmakuSegments(segments: List<*>) {
        this.mDanmakuSegments = segments
    }

    private class ElemFields(val fields: Array<Field>) {

        fun progress(elem: Any): Int = fields[FIELD_PROGRESS].getInt(elem)

        fun mode(elem: Any): Int = fields[FIELD_MODE].getInt(elem)

        fun fontSize(elem: Any): Int = fields[FIELD_FONT_SIZE].getInt(elem)

        fun color(elem: Any): Int = fields[FIELD_COLOR].getInt(elem)

        fun content(elem: Any): String? = fields[FIELD_CONTENT].get(elem) as? String

        companion object {

            private const val FIELD_PROGRESS = 0
            private const val FIELD_MODE = 1
            private const val FIELD_FONT_SIZE = 2
            private const val FIELD_COLOR = 3
            private const val FIELD_CONTENT = 4

            fun of(clazz: Class<*>): ElemFields {
                val names = arrayOf(
                    "progress", "mode", "fontsize", "color", "content"
                )
                return ElemFields(Array(names.size) { clazz.getField(names[it]) })
            }
        }
    }

    private class SegmentFields(val elems: Field)

    override fun parse(): Danmakus {
        val segments = mDanmakuSegments
        if (segments.isNullOrEmpty()) return Danmakus()

        val collected = ArrayList<BaseDanmaku>(1024)
        var index = 0
        val fieldCache = HashMap<Class<*>, Any?>()

        for (segment in segments) {
            if (segment == null) continue
            val elems = readElems(segment, fieldCache) ?: continue
            for (elem in elems) {
                if (elem == null) continue
                val danmaku = parseDanmakuElem(elem, index++, fieldCache) ?: continue
                collected.add(danmaku)
            }
        }
        return Danmakus().apply { setItems(collected) }
    }

    private fun readElems(segment: Any, cache: HashMap<Class<*>, Any?>): List<*>? {
        val clazz = segment.javaClass
        val cached = cache[clazz]
        if (cached is SegmentFields) {
            return cached.elems.get(segment) as? List<*>
        }
        if (cached === MISS) return null
        return try {
            val fields = SegmentFields(clazz.getField("elems"))
            cache[clazz] = fields
            fields.elems.get(segment) as? List<*>
        } catch (_: Exception) {
            cache[clazz] = MISS
            null
        }
    }

    private fun parseDanmakuElem(
        elemObj: Any,
        index: Int,
        cache: HashMap<Class<*>, Any?>,
    ): BaseDanmaku? {
        val clazz = elemObj.javaClass
        var fields = cache[clazz] as? ElemFields
        if (fields == null) {
            if (cache[clazz] === MISS) return null
            fields = try {
                ElemFields.of(clazz).also { cache[clazz] = it }
            } catch (_: Exception) {
                cache[clazz] = MISS
                return null
            }
        }

        val content = try {
            fields.content(elemObj)
        } catch (_: Exception) {
            null
        }
        if (content.isNullOrEmpty()) return null

        var mode = try {
            resolveMode(fields.mode(elemObj))
        } catch (_: Exception) {
            return null
        }
        // Mode 8 is not representable once advanced danmakus are disabled; it is
        // downgraded by resolveMode, and genuinely unsupported mode 8 stays hidden.
        val text = if (mode == MODE_SPECIAL && isAdvancedPayload(content)) {
            mode = MODE_SCROLL_RL
            unwrapAdvancedPayload(content)
        } else {
            content
        }
        if (mode == MODE_ADVANCED) return null

        val progress: Int
        val fontSize: Int
        val color: Int
        try {
            progress = fields.progress(elemObj)
            fontSize = fields.fontSize(elemObj)
            color = fields.color(elemObj)
        } catch (_: Exception) {
            return null
        }

        val item = mContext.mDanmakuFactory.createDanmaku(mode, mContext) ?: return null
        item.time = progress.toLong()
        item.index = index
        item.textSize = fontSize * (mDispDensity - 0.6f)
        item.textColor = color or -0x1000000
        item.textShadowColor = if (item.textColor <= Color.BLACK) Color.WHITE else Color.BLACK
        item.setTimer(mTimer)

        if (mode == MODE_SPECIAL) {
            val frames = SpecialDanmakuParser.parseFromJson(text.trim())
            if (frames == null || frames.size < 5) return null
            SpecialDanmakuParser.parse(
                item as SpecialDanmaku, frames, mContext, mDispScaleX, mDispScaleY
            )
        } else {
            DanmakuUtils.fillText(item, text)
        }
        return item
    }

    private fun unwrapAdvancedPayload(raw: String): String = try {
        val array = JSONArray(raw)
        if (array.length() >= 5) array.getString(4) else raw
    } catch (_: Exception) {
        raw
    }

    override fun setDisplayer(disp: IDisplayer): BaseDanmakuParser {
        super.setDisplayer(disp)
        mDispScaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH
        mDispScaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT
        return this
    }

    companion object {
        private const val MODE_SCROLL_RL = 1
        private const val MODE_SPECIAL = 7
        private const val MODE_ADVANCED = 8

        /** Negative cache marker so a class without the field is tried once. */
        private val MISS = Any()
    }
}
