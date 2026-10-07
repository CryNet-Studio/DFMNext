package io.github.ynotbili.dfmnext.danmaku.model.android

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.Duration
import io.github.ynotbili.dfmnext.danmaku.model.FBDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.FTDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.L2RDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.R2LDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.SpecialDanmaku
import io.github.ynotbili.dfmnext.danmaku.util.isSpecial

class DanmakuFactory protected constructor() {

    companion object {
        const val OLD_BILI_PLAYER_WIDTH = 539f
        const val BILI_PLAYER_WIDTH = 682f
        const val OLD_BILI_PLAYER_HEIGHT = 385f
        const val BILI_PLAYER_HEIGHT = 438f
        const val COMMON_DANMAKU_DURATION = 3800L
        const val DANMAKU_MEDIUM_TEXTSIZE = 25
        const val MIN_DANMAKU_DURATION = 4000L
        const val MAX_DANMAKU_DURATION_HIGH_DENSITY = 9000L

        fun create(): DanmakuFactory = DanmakuFactory()

        fun fillLinePathData(item: BaseDanmaku, points: Array<FloatArray>, scaleX: Float, scaleY: Float) {
            if (!item.isSpecial || points.isEmpty() || points[0].size != 2) return
            for (point in points) {
                point[0] *= scaleX
                point[1] *= scaleY
            }
            (item as SpecialDanmaku).setLinePathData(points)
        }
    }

    var CURRENT_DISP_WIDTH: Int = 0
    var CURRENT_DISP_HEIGHT: Int = 0
    private var CURRENT_DISP_SIZE_FACTOR: Float = 1.0f
    var REAL_DANMAKU_DURATION: Long = COMMON_DANMAKU_DURATION
    var MAX_DANMAKU_DURATION: Long = MIN_DANMAKU_DURATION

    var MAX_Duration_Scroll_Danmaku: Duration? = null
    var MAX_Duration_Fix_Danmaku: Duration? = null
    var MAX_Duration_Special_Danmaku: Duration? = null

    val sSpecialDanmakus: IDanmakus = Danmakus()
    var sLastDisp: IDisplayer? = null
    private var sLastConfig: DanmakuContext? = null

    fun resetDurationsData() {
        sLastDisp = null
        CURRENT_DISP_WIDTH = 0
        CURRENT_DISP_HEIGHT = 0
        sSpecialDanmakus.clear()
        MAX_Duration_Scroll_Danmaku = null
        MAX_Duration_Fix_Danmaku = null
        MAX_Duration_Special_Danmaku = null
        MAX_DANMAKU_DURATION = MIN_DANMAKU_DURATION
    }

    fun notifyDispSizeChanged(context: DanmakuContext) {
        sLastConfig = context
        sLastDisp = context.getDisplayer()
        createDanmaku(BaseDanmaku.TYPE_SCROLL_RL, context)
    }

    fun createDanmaku(type: Int): BaseDanmaku? {
        return createDanmaku(type, sLastConfig)
    }

    fun createDanmaku(type: Int, context: DanmakuContext?): BaseDanmaku? {
        if (context == null) return null
        sLastConfig = context
        sLastDisp = context.getDisplayer()
        return createDanmaku(type, sLastDisp!!.width, sLastDisp!!.height, CURRENT_DISP_SIZE_FACTOR, 1.0f)
    }

    fun createDanmaku(type: Int, disp: IDisplayer, viewportScale: Float, scrollSpeedFactor: Float): BaseDanmaku? {
        sLastDisp = disp
        return createDanmaku(type, disp.width, disp.height, viewportScale, scrollSpeedFactor)
    }

    fun createDanmaku(type: Int, viewportWidth: Int, viewportHeight: Int, viewportScale: Float, scrollSpeedFactor: Float): BaseDanmaku? {
        return createDanmaku(type, viewportWidth.toFloat(), viewportHeight.toFloat(), viewportScale, scrollSpeedFactor)
    }

    fun createDanmaku(type: Int, viewportWidth: Float, viewportHeight: Float, viewportSizeFactor: Float, scrollSpeedFactor: Float): BaseDanmaku? {
        val oldDispWidth = CURRENT_DISP_WIDTH
        val oldDispHeight = CURRENT_DISP_HEIGHT
        val sizeChanged = updateViewportState(viewportWidth, viewportHeight, viewportSizeFactor)

        if (MAX_Duration_Scroll_Danmaku == null) {
            MAX_Duration_Scroll_Danmaku = Duration(REAL_DANMAKU_DURATION)
            MAX_Duration_Scroll_Danmaku!!.setFactor(scrollSpeedFactor)
        } else if (sizeChanged) {
            MAX_Duration_Scroll_Danmaku!!.setValue(REAL_DANMAKU_DURATION)
        }

        if (MAX_Duration_Fix_Danmaku == null) {
            MAX_Duration_Fix_Danmaku = Duration(COMMON_DANMAKU_DURATION)
        }

        if (sizeChanged && viewportWidth > 0) {
            updateMaxDanmakuDuration()
            var scaleX = 1f
            var scaleY = 1f
            if (oldDispWidth > 0 && oldDispHeight > 0) {
                scaleX = viewportWidth / oldDispWidth.toFloat()
                scaleY = viewportHeight / oldDispHeight.toFloat()
            }
            if (viewportHeight > 0) {
                updateSpecialDanmakusDate(scaleX, scaleY)
            }
        }

        return when (type) {
            1 -> R2LDanmaku(MAX_Duration_Scroll_Danmaku!!)
            4 -> FBDanmaku(MAX_Duration_Fix_Danmaku!!)
            5 -> FTDanmaku(MAX_Duration_Fix_Danmaku!!)
            6 -> L2RDanmaku(MAX_Duration_Scroll_Danmaku!!)
            7 -> {
                val instance = SpecialDanmaku()
                instance.duration = MAX_Duration_Fix_Danmaku ?: Duration(COMMON_DANMAKU_DURATION)
                sSpecialDanmakus += instance
                instance
            }
            else -> null
        }
    }

    fun updateViewportState(viewportWidth: Float, viewportHeight: Float, viewportSizeFactor: Float): Boolean {
        if (CURRENT_DISP_WIDTH != viewportWidth.toInt() ||
            CURRENT_DISP_HEIGHT != viewportHeight.toInt() ||
            CURRENT_DISP_SIZE_FACTOR != viewportSizeFactor
        ) {
            REAL_DANMAKU_DURATION = (COMMON_DANMAKU_DURATION * (viewportSizeFactor * viewportWidth / BILI_PLAYER_WIDTH)).toLong()
            REAL_DANMAKU_DURATION = REAL_DANMAKU_DURATION.coerceIn(MIN_DANMAKU_DURATION, MAX_DANMAKU_DURATION_HIGH_DENSITY)

            CURRENT_DISP_WIDTH = viewportWidth.toInt()
            CURRENT_DISP_HEIGHT = viewportHeight.toInt()
            CURRENT_DISP_SIZE_FACTOR = viewportSizeFactor
            return true
        }
        return false
    }

    private fun updateSpecialDanmakusDate(scaleX: Float, scaleY: Float) {
        val list = sSpecialDanmakus
        val it: MutableIterator<BaseDanmaku> = list.iterator()
        while (it.hasNext()) {
            val specialDanmaku = it.next() as SpecialDanmaku
            fillTranslationData(
                specialDanmaku, specialDanmaku.beginX, specialDanmaku.beginY,
                specialDanmaku.endX, specialDanmaku.endY,
                specialDanmaku.translationDuration, specialDanmaku.translationStartDelay,
                scaleX, scaleY
            )
            val linePaths = specialDanmaku.linePaths
            if (linePaths != null && linePaths.isNotEmpty()) {
                val length = linePaths.size
                val points = Array(length + 1) { FloatArray(2) }
                for (j in 0 until length) {
                    points[j] = linePaths[j].getBeginPoint()
                    points[j + 1] = linePaths[j].getEndPoint()
                }
                fillLinePathData(specialDanmaku, points, scaleX, scaleY)
            }
        }
    }

    fun updateMaxDanmakuDuration() {
        val maxScrollDuration = MAX_Duration_Scroll_Danmaku?.value ?: 0L
        val maxFixDuration = MAX_Duration_Fix_Danmaku?.value ?: 0L
        val maxSpecialDuration = MAX_Duration_Special_Danmaku?.value ?: 0L

        MAX_DANMAKU_DURATION = maxOf(maxScrollDuration, maxFixDuration, maxSpecialDuration)
        MAX_DANMAKU_DURATION = maxOf(COMMON_DANMAKU_DURATION, MAX_DANMAKU_DURATION)
        MAX_DANMAKU_DURATION = maxOf(REAL_DANMAKU_DURATION, MAX_DANMAKU_DURATION)
    }

    fun updateDurationFactor(f: Float) {
        if (MAX_Duration_Scroll_Danmaku == null || MAX_Duration_Fix_Danmaku == null) return
        MAX_Duration_Scroll_Danmaku!!.setFactor(f)
        updateMaxDanmakuDuration()
    }

    fun fillTranslationData(
        item: BaseDanmaku, beginX: Float, beginY: Float,
        endX: Float, endY: Float,
        translationDuration: Long, translationStartDelay: Long,
        scaleX: Float, scaleY: Float
    ) {
        if (!item.isSpecial) return
        (item as SpecialDanmaku).setTranslationData(
            beginX * scaleX, beginY * scaleY,
            endX * scaleX, endY * scaleY,
            translationDuration, translationStartDelay
        )
        updateSpecicalDanmakuDuration(item)
    }

    fun fillAlphaData(item: BaseDanmaku, beginAlpha: Int, endAlpha: Int, alphaDuraion: Long) {
        if (!item.isSpecial) return
        (item as SpecialDanmaku).setAlphaData(beginAlpha, endAlpha, alphaDuraion)
        updateSpecicalDanmakuDuration(item)
    }

    private fun updateSpecicalDanmakuDuration(item: BaseDanmaku) {
        if (MAX_Duration_Special_Danmaku == null || (item.duration != null && item.duration!!.value > MAX_Duration_Special_Danmaku!!.value)) {
            MAX_Duration_Special_Danmaku = item.duration
            updateMaxDanmakuDuration()
        }
    }
}
