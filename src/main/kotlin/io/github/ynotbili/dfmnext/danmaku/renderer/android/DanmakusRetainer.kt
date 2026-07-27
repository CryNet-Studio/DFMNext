package io.github.ynotbili.dfmnext.danmaku.renderer.android

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import io.github.ynotbili.dfmnext.danmaku.util.isScrollRL
import io.github.ynotbili.dfmnext.danmaku.util.isScrollLR
import io.github.ynotbili.dfmnext.danmaku.util.isFixTop
import io.github.ynotbili.dfmnext.danmaku.util.isFixBottom
import io.github.ynotbili.dfmnext.danmaku.util.isSpecial

class DanmakusRetainer {

    private var rldrInstance: IDanmakusRetainer? = null
    private var lrdrInstance: IDanmakusRetainer? = null
    private var ftdrInstance: IDanmakusRetainer? = null
    private var fbdrInstance: IDanmakusRetainer? = null

    fun fix(danmaku: BaseDanmaku, disp: IDisplayer, verifier: Verifier?) {
        when {
            danmaku.isScrollRL -> {
                if (rldrInstance == null) rldrInstance = RLDanmakusRetainer()
                rldrInstance!!.fix(danmaku, disp, verifier)
            }
            danmaku.isScrollLR -> {
                if (lrdrInstance == null) lrdrInstance = RLDanmakusRetainer()
                lrdrInstance!!.fix(danmaku, disp, verifier)
            }
            danmaku.isFixTop -> {
                if (ftdrInstance == null) ftdrInstance = FTDanmakusRetainer()
                ftdrInstance!!.fix(danmaku, disp, verifier)
            }
            danmaku.isFixBottom -> {
                if (fbdrInstance == null) fbdrInstance = FBDanmakusRetainer()
                fbdrInstance!!.fix(danmaku, disp, verifier)
            }
            danmaku.isSpecial -> {
                danmaku.layout(disp, 0f, 0f)
            }
        }
    }

    fun clear() {
        rldrInstance?.clear()
        lrdrInstance?.clear()
        ftdrInstance?.clear()
        fbdrInstance?.clear()
    }

    fun release() {
        clear()
        rldrInstance = null
        lrdrInstance = null
        ftdrInstance = null
        fbdrInstance = null
    }

    interface Verifier {
        fun skipLayout(danmaku: BaseDanmaku, fixedTop: Float, lines: Int, willHit: Boolean): Boolean
    }

    interface IDanmakusRetainer {
        fun fix(drawItem: BaseDanmaku, disp: IDisplayer, verifier: Verifier?)
        fun clear()
    }

    private open class RLDanmakusRetainer : IDanmakusRetainer {

        protected open val mVisibleDanmakus = Danmakus(Danmakus.ST_BY_YPOS)
        protected var mCancelFixingFlag = false

        override fun fix(drawItem: BaseDanmaku, disp: IDisplayer, verifier: Verifier?) {
            if (drawItem.isOutside()) return
            var topPos = 0f
            var lines = 0
            var willHit = !drawItem.isShown() && !mVisibleDanmakus.isEmpty()
            var isOutOfVerticalEdge = false
            var shown = drawItem.isShown()
            var removeItem: BaseDanmaku? = null

            if (!shown) {
                mCancelFixingFlag = false
                val it = mVisibleDanmakus.iterator()
                var insertItem: BaseDanmaku? = null
                var firstItem: BaseDanmaku? = null
                var lastItem: BaseDanmaku? = null
                var minRightRow: BaseDanmaku? = null
                var overwriteInsert = false

                while (!mCancelFixingFlag && it.hasNext()) {
                    lines++
                    val item = it.next()

                    if (item == drawItem) {
                        insertItem = item
                        lastItem = null
                        shown = true
                        willHit = false
                        break
                    }

                    if (firstItem == null) firstItem = item

                    if (drawItem.paintHeight + item.getTop() > disp.height) {
                        overwriteInsert = true
                        break
                    }

                    if (minRightRow == null || minRightRow.getRight() >= item.getRight()) {
                        minRightRow = item
                    }

                    willHit = DanmakuUtils.willHitInDuration(
                        disp, item, drawItem,
                        drawItem.getDuration(), drawItem.getTimer()!!.currMillisecond
                    )
                    if (!willHit) {
                        insertItem = item
                        break
                    }

                    lastItem = item
                }

                var checkEdge = true
                if (insertItem != null) {
                    topPos = lastItem?.getBottom() ?: insertItem.getTop()
                    if (insertItem !== drawItem) {
                        removeItem = insertItem
                        shown = false
                    }
                } else if (overwriteInsert && minRightRow != null) {
                    topPos = minRightRow.getTop()
                    checkEdge = false
                    shown = false
                } else if (lastItem != null) {
                    topPos = lastItem.getBottom()
                    willHit = false
                } else if (firstItem != null) {
                    topPos = firstItem.getTop()
                    removeItem = firstItem
                    shown = false
                } else {
                    topPos = 0f
                }

                if (checkEdge) {
                    isOutOfVerticalEdge = isOutVerticalEdge(overwriteInsert, drawItem, disp, topPos, firstItem, lastItem)
                }
                if (isOutOfVerticalEdge) {
                    topPos = 0f
                    willHit = true
                }
                if (topPos == 0f) {
                    shown = false
                }
            }

            if (verifier != null && verifier.skipLayout(drawItem, topPos, lines, willHit)) return

            if (isOutOfVerticalEdge) {
                clear()
            }

            drawItem.layout(disp, drawItem.getLeft(), topPos)

            if (!shown) {
                if (removeItem != null) mVisibleDanmakus -= removeItem
                mVisibleDanmakus += drawItem
            }
        }

        protected open fun isOutVerticalEdge(
            overwriteInsert: Boolean, drawItem: BaseDanmaku,
            disp: IDisplayer, topPos: Float, firstItem: BaseDanmaku?, lastItem: BaseDanmaku?
        ): Boolean {
            return topPos < 0 || (firstItem != null && firstItem.getTop() > 0) || topPos + drawItem.paintHeight > disp.height
        }

        override fun clear() {
            mCancelFixingFlag = true
            mVisibleDanmakus.clear()
        }
    }

    private open class FTDanmakusRetainer : RLDanmakusRetainer() {

        override fun isOutVerticalEdge(
            overwriteInsert: Boolean, drawItem: BaseDanmaku,
            disp: IDisplayer, topPos: Float, firstItem: BaseDanmaku?, lastItem: BaseDanmaku?
        ): Boolean {
            return topPos + drawItem.paintHeight > disp.height
        }
    }

    private class FBDanmakusRetainer : FTDanmakusRetainer() {

        override val mVisibleDanmakus = Danmakus(Danmakus.ST_BY_YPOS_DESC)

        override fun fix(drawItem: BaseDanmaku, disp: IDisplayer, verifier: Verifier?) {
            if (drawItem.isOutside()) return
            val shown = drawItem.isShown()
            var topPos = drawItem.getTop()
            var lines = 0
            var willHit = !drawItem.isShown() && !mVisibleDanmakus.isEmpty()
            var isOutOfVerticalEdge = false
            if (topPos < 0) {
                topPos = disp.height - drawItem.paintHeight
            }
            var removeItem: BaseDanmaku? = null
            var firstItem: BaseDanmaku? = null

            if (!shown) {
                mCancelFixingFlag = false
                val it = mVisibleDanmakus.iterator()
                while (!mCancelFixingFlag && it.hasNext()) {
                    lines++
                    val item = it.next()

                    if (item === drawItem) {
                        removeItem = null
                        willHit = false
                        break
                    }

                    if (firstItem == null) {
                        firstItem = item
                        if (firstItem.getBottom() != disp.height.toFloat()) break
                    }

                    if (topPos < 0) {
                        removeItem = null
                        break
                    }

                    willHit = DanmakuUtils.willHitInDuration(
                        disp, item, drawItem,
                        drawItem.getDuration(), drawItem.getTimer()!!.currMillisecond
                    )
                    if (!willHit) {
                        removeItem = item
                        break
                    }

                    topPos = item.getTop() - drawItem.paintHeight
                }

                isOutOfVerticalEdge = isOutVerticalEdge(false, drawItem, disp, topPos, firstItem, null)
                if (isOutOfVerticalEdge) {
                    topPos = disp.height - drawItem.paintHeight
                    willHit = true
                } else if (topPos >= 0) {
                    willHit = false
                }
            }

            if (verifier != null && verifier.skipLayout(drawItem, topPos, lines, willHit)) return

            if (isOutOfVerticalEdge) {
                clear()
            }

            drawItem.layout(disp, drawItem.getLeft(), topPos)

            if (!shown) {
                if (removeItem != null) mVisibleDanmakus -= removeItem
                mVisibleDanmakus += drawItem
            }
        }

        override fun isOutVerticalEdge(
            overwriteInsert: Boolean, drawItem: BaseDanmaku,
            disp: IDisplayer, topPos: Float, firstItem: BaseDanmaku?, lastItem: BaseDanmaku?
        ): Boolean {
            return topPos < 0 || (firstItem != null && firstItem.getBottom() != disp.height.toFloat())
        }

        override fun clear() {
            mCancelFixingFlag = true
            mVisibleDanmakus.clear()
        }
    }
}
