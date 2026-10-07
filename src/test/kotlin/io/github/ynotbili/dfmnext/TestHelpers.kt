package io.github.ynotbili.dfmnext

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.Duration
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer

class StubDisplayer(
    override val width: Int = 480,
    override val height: Int = 800,
    override val density: Float = 2.0f,
    override val densityDpi: Int = 320,
    override val scaledDensity: Float = 2.0f,
    override val slopPixel: Int = 4,
    override val strokeWidth: Float = 1.0f,
    override val maximumCacheWidth: Int = 2048,
    override val maximumCacheHeight: Int = 2048,
    override val isHardwareAccelerated: Boolean = false
) : IDisplayer {
    override fun draw(danmaku: BaseDanmaku): Int = 0
    override fun measure(danmaku: BaseDanmaku, fromWorkerThread: Boolean) {}
    override fun setHardwareAccelerated(enable: Boolean) {}
    override fun resetSlopPixel(factor: Float) {}
    override fun setDensities(density: Float, densityDpi: Int, scaledDensity: Float) {}
    override fun setSize(width: Int, height: Int) {}
    override fun setDanmakuStyle(style: Int, data: FloatArray) {}
}

class TestDanmaku(
    private val danmakuType: Int = BaseDanmaku.TYPE_SCROLL_RL
) : BaseDanmaku() {
    private var lx = 0f; private var ly = 0f
    private var rx = 0f; private var by = 0f

    override fun layout(displayer: IDisplayer, x: Float, y: Float) {
        lx = x; ly = y; rx = x + paintWidth; by = y + paintHeight
    }

    override fun getRectAtTime(displayer: IDisplayer, currTime: Long): FloatArray? =
        floatArrayOf(lx, ly, rx, by)

    override fun getLeft(): Float = lx
    override fun getTop(): Float = ly
    override fun getRight(): Float = rx
    override fun getBottom(): Float = by
    override fun getType(): Int = danmakuType
}

fun createDanmaku(
    type: Int = BaseDanmaku.TYPE_SCROLL_RL,
    time: Long = 0L,
    text: String? = null,
    durationMs: Long = 5000L
): TestDanmaku {
    return TestDanmaku(type).apply {
        this.time = time
        this.text = text
        this.duration = Duration(durationMs)
    }
}
