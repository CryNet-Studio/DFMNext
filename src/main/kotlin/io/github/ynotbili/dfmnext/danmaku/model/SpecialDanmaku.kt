package io.github.ynotbili.dfmnext.danmaku.model

import kotlin.math.abs
import kotlin.math.sqrt

class SpecialDanmaku : BaseDanmaku() {

    data class Point(val x: Float, val y: Float) {
        fun getDistance(p: Point): Float {
            val dx = abs(this.x - p.x)
            val dy = abs(this.y - p.y)
            return sqrt(dx * dx + dy * dy)
        }
    }

    inner class LinePath {
        lateinit var pBegin: Point
        lateinit var pEnd: Point
        var duration: Long = 0
        var beginTime: Long = 0
        var endTime: Long = 0
        var delatX: Float = 0f
        var deltaY: Float = 0f

        fun setPoints(pBegin: Point, pEnd: Point) {
            this.pBegin = pBegin
            this.pEnd = pEnd
            this.delatX = pEnd.x - pBegin.x
            this.deltaY = pEnd.y - pBegin.y
        }

        fun getDistance(): Float = pEnd.getDistance(pBegin)

        fun getBeginPoint(): FloatArray = floatArrayOf(pBegin.x, pBegin.y)

        fun getEndPoint(): FloatArray = floatArrayOf(pEnd.x, pEnd.y)
    }

    var beginX: Float = 0f
    var beginY: Float = 0f
    var endX: Float = 0f
    var endY: Float = 0f
    var deltaX: Float = 0f
    var deltaY: Float = 0f
    var translationDuration: Long = 0
    var translationStartDelay: Long = 0
    var beginAlpha: Int = 0
    var endAlpha: Int = 0
    var deltaAlpha: Int = 0
    var alphaDuration: Long = 0
    var rotateX: Float = 0f
    var rotateZ: Float = 0f
    var pivotX: Float = 0f
    var pivotY: Float = 0f
    var linePaths: Array<LinePath>? = null

    private val currStateValues = FloatArray(4)

    override fun layout(displayer: IDisplayer, x: Float, y: Float) {
        getRectAtTime(displayer, mTimer?.currMillisecond ?: 0)
    }

    override fun getRectAtTime(displayer: IDisplayer, currTime: Long): FloatArray? {
        if (!isMeasured()) return null

        val deltaTime = currTime - time

        // calculate alpha
        if (alphaDuration > 0 && deltaAlpha != 0) {
            if (deltaTime >= alphaDuration) {
                alpha = endAlpha
            } else {
                val alphaProgress = deltaTime.toFloat() / alphaDuration
                val vectorAlpha = (deltaAlpha * alphaProgress).toInt()
                alpha = beginAlpha + vectorAlpha
            }
        }

        // calculate x y
        var currX = beginX
        var currY = beginY
        val dtime = deltaTime - translationStartDelay
        if (translationDuration > 0 && dtime in 0..translationDuration) {
            if (linePaths != null) {
                var currentLinePath: LinePath? = null
                for (line in linePaths!!) {
                    if (dtime >= line.beginTime && dtime < line.endTime) {
                        currentLinePath = line
                        break
                    } else {
                        currX = line.pEnd.x
                        currY = line.pEnd.y
                    }
                }
                if (currentLinePath != null) {
                    val lDeltaX = currentLinePath.delatX
                    val lDeltaY = currentLinePath.deltaY
                    val progress = (deltaTime - currentLinePath.beginTime).toFloat() / currentLinePath.duration
                    val lBeginX = currentLinePath.pBegin.x
                    val lBeginY = currentLinePath.pBegin.y
                    if (lDeltaX != 0f) currX = lBeginX + lDeltaX * progress
                    if (lDeltaY != 0f) currY = lBeginY + lDeltaY * progress
                }
            } else {
                val progress = dtime.toFloat() / translationDuration
                if (deltaX != 0f) currX = beginX + deltaX * progress
                if (deltaY != 0f) currY = beginY + deltaY * progress
            }
        } else if (dtime > translationDuration) {
            currX = endX
            currY = endY
        }

        currStateValues[0] = currX
        currStateValues[1] = currY
        currStateValues[2] = currX + paintWidth
        currStateValues[3] = currY + paintHeight

        setVisibility(!isOutside())
        return currStateValues
    }

    override fun getLeft(): Float = currStateValues[0]
    override fun getTop(): Float = currStateValues[1]
    override fun getRight(): Float = currStateValues[2]
    override fun getBottom(): Float = currStateValues[3]
    override fun getType(): Int = TYPE_SPECIAL

    fun setTranslationData(
        beginX: Float, beginY: Float, endX: Float, endY: Float,
        translationDuration: Long, translationStartDelay: Long
    ) {
        this.beginX = beginX
        this.beginY = beginY
        this.endX = endX
        this.endY = endY
        this.deltaX = endX - beginX
        this.deltaY = endY - beginY
        this.translationDuration = translationDuration
        this.translationStartDelay = translationStartDelay
    }

    fun setAlphaData(beginAlpha: Int, endAlpha: Int, alphaDuration: Long) {
        this.beginAlpha = beginAlpha
        this.endAlpha = endAlpha
        this.deltaAlpha = endAlpha - beginAlpha
        this.alphaDuration = alphaDuration
        if (deltaAlpha != 0 && beginAlpha != BaseDanmaku.ALPHA_MAX) {
            alpha = beginAlpha
        }
    }

    fun setLinePathData(points: Array<FloatArray>?) {
        if (points != null) {
            val length = points.size
            beginX = points[0][0]
            beginY = points[0][1]
            endX = points[length - 1][0]
            endY = points[length - 1][1]
            if (points.size > 1) {
                linePaths = Array(points.size - 1) { i ->
                    LinePath().apply {
                        setPoints(
                            Point(points[i][0], points[i][1]),
                            Point(points[i + 1][0], points[i + 1][1])
                        )
                    }
                }
                val totalDistance = linePaths!!.sumOf { it.getDistance().toDouble() }.toFloat()
                var lastLine: LinePath? = null
                for (line in linePaths!!) {
                    line.duration = ((line.getDistance() / totalDistance) * translationDuration).toLong()
                    line.beginTime = lastLine?.endTime ?: 0
                    line.endTime = line.beginTime + line.duration
                    lastLine = line
                }
            }
        }
    }
}
