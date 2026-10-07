package io.github.ynotbili.dfmnext.danmaku.parser.android

import android.graphics.Color
import android.util.Xml
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.SpecialDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuFactory
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import io.github.ynotbili.dfmnext.danmaku.parser.BaseDanmakuParser
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import io.github.ynotbili.dfmnext.danmaku.util.isSpecial
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Parses the legacy Bilibili `xml` danmaku format.
 *
 * Previously this went through `XMLReaderFactory.createXMLReader()` plus a
 * `org.xml.sax.driver` system property set in a companion initializer. That
 * factory is removed from newer Android platform versions and relies on a
 * global side effect; `android.util.Xml.newPullParser()` is the supported,
 * allocation-light API and is what the platform's own SAX driver wraps anyway.
 *
 * Danmakus are collected in arrival order and sorted once at the end
 * ([Danmakus.setItems]) instead of being inserted into a sorted container one by
 * one — for a 10k-comment file that is one `sortWith` pass instead of 10k
 * binary-search insertions.
 */
class BiliDanmukuParser : BaseDanmakuParser() {

    private var mDispScaleX: Float = 0f
    private var mDispScaleY: Float = 0f

    override fun parse(): Danmakus? {
        val source = mDataSource ?: return null
        val input = source.data() as? InputStream ?: return null
        val collected = ArrayList<BaseDanmaku>(1024)
        return try {
            parse(input, collected)
        } catch (_: Exception) {
            // Malformed XML: whatever was parsed up to the failure is still usable.
            if (collected.isEmpty()) null else Danmakus().apply { setItems(collected) }
        }
    }

    private fun parse(input: InputStream, collected: ArrayList<BaseDanmaku>): Danmakus {
        // android.util.Xml.newPullParser() already has namespace processing off,
        // so `d` comes back as the raw tag name and no feature call is needed.
        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        var item: BaseDanmaku? = null
        var index = 0

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG ->
                    if (parser.name == TAG_DANMAKU) {
                        // Assign unconditionally: if the attributes are unusable the
                        // pending item must be dropped, otherwise the following TEXT
                        // event would write this element's content into the previous
                        // danmaku.
                        item = readAttributes(parser)
                    }

                XmlPullParser.TEXT -> {
                    val danmaku = item ?: continue
                    val raw = decodeXmlString(parser.text ?: "")
                    DanmakuUtils.fillText(danmaku, unwrapAdvancedPayload(raw))
                    danmaku.index = index++
                    if (!prepareContent(danmaku)) item = null
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name != TAG_DANMAKU) continue
                    val danmaku = item ?: continue
                    if (danmaku.duration != null) {
                        danmaku.setTimer(mTimer)
                        collected.add(danmaku)
                    }
                    item = null
                }
            }
        }
        return Danmakus().apply { setItems(collected) }
    }

    private fun readAttributes(parser: XmlPullParser): BaseDanmaku? {
        val encoded = parser.getAttributeValue(null, "p") ?: return null
        // The parameter string is `time,type,size,color,...` — read it by index
        // instead of split(), which allocates an array plus a substring per field.
        var cursor = 0
        val fields = arrayOfNulls<String>(FIELD_COUNT)
        for (i in 0 until FIELD_COUNT) {
            val next = encoded.indexOf(',', cursor)
            if (next < 0) {
                fields[i] = encoded.substring(cursor)
                cursor = encoded.length
            } else {
                fields[i] = encoded.substring(cursor, next)
                cursor = next + 1
            }
        }
        val timeField = fields[0] ?: return null
        val typeField = fields[1] ?: return null
        val sizeField = fields[2] ?: return null
        val colorField = fields[3] ?: return null

        val type: Int
        val time: Long
        val textSize: Float
        val color: Int
        try {
            type = typeField.toInt()
            time = (timeField.toFloat() * 1000).toLong()
            textSize = sizeField.toFloat()
            color = colorField.toInt() or -0x1000000
        } catch (_: NumberFormatException) {
            return null
        }

        val created = mContext.mDanmakuFactory.createDanmaku(resolveMode(type), mContext)
            ?: return null
        created.time = time
        created.textSize = textSize * (mDispDensity - 0.6f)
        created.textColor = color
        created.textShadowColor = if (color <= Color.BLACK) Color.WHITE else Color.BLACK
        return created
    }

    /**
     * When advanced rendering is off, an animated danmaku's payload is a JSON
     * array whose element 4 is the plain text.
     */
    private fun unwrapAdvancedPayload(raw: String): String {
        if (!isAdvancedPayload(raw)) return raw
        return try {
            val array = JSONArray(raw)
            if (array.length() >= 5) array.getString(4) else raw
        } catch (_: Exception) {
            raw
        }
    }

    /** Returns false when the item must be discarded (a broken special danmaku). */
    private fun prepareContent(danmaku: BaseDanmaku): Boolean {
        if (!danmaku.isSpecial) return true
        val text = danmaku.text?.toString()?.trim() ?: return false
        val frames = SpecialDanmakuParser.parseFromJson(text) ?: return false
        if (frames.size < 5) return false
        SpecialDanmakuParser.parse(
            danmaku as SpecialDanmaku, frames, mContext, mDispScaleX, mDispScaleY
        )
        return true
    }

    private fun decodeXmlString(title: String): String {
        if (title.indexOf('&') < 0) return title
        var result = title
        if (result.contains("&amp;")) result = result.replace("&amp;", "&")
        if (result.contains("&quot;")) result = result.replace("&quot;", "\"")
        if (result.contains("&gt;")) result = result.replace("&gt;", ">")
        if (result.contains("&lt;")) result = result.replace("&lt;", "<")
        return result
    }

    override fun setDisplayer(disp: IDisplayer): BaseDanmakuParser {
        super.setDisplayer(disp)
        mDispScaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH
        mDispScaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT
        return this
    }

    private companion object {
        const val TAG_DANMAKU = "d"
        const val FIELD_COUNT = 4
    }
}
