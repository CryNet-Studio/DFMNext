package io.github.ynotbili.dfmnext.danmaku.renderer.android

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import io.github.ynotbili.dfmnext.danmaku.util.isScrollRL
import io.github.ynotbili.dfmnext.danmaku.util.isScrollLR
import io.github.ynotbili.dfmnext.danmaku.util.isFixTop
import io.github.ynotbili.dfmnext.danmaku.util.isFixBottom
import io.github.ynotbili.dfmnext.danmaku.util.isSpecial

/**
 * Assigns a lane (vertical position) to each danmaku and tracks which lanes are
 * occupied, so that scrolling comments do not overlap.
 *
 * The occupied-lane sets used to be `Danmakus` sorted by `getTop()` — a mutable
 * key, because every scroll step changes it. Sorted containers with drifting
 * keys need an identity fallback on every lookup and rebuild ordering on every
 * insert; [Lanes] replaces that with a small array kept in lane order directly,
 * inserted from the tail (where a new lane almost always belongs) and removed by
 * identity.
 */
class DanmakusRetainer {

    private var rldrInstance: IDanmakusRetainer? = null
    private var lrdrInstance: IDanmakusRetainer? = null
    private var ftdrInstance: IDanmakusRetainer? = null
    private var fbdrInstance: IDanmakusRetainer? = null

    fun fix(danmaku: BaseDanmaku, disp: IDisplayer, verifier: Verifier?) {
        when {
            danmaku.isScrollRL -> RL.instance().fix(danmaku, disp, verifier)
            danmaku.isScrollLR -> LR.instance().fix(danmaku, disp, verifier)
            danmaku.isFixTop -> FT.instance().fix(danmaku, disp, verifier)
            danmaku.isFixBottom -> FB.instance().fix(danmaku, disp, verifier)
            danmaku.isSpecial -> danmaku.layout(disp, 0f, 0f)
        }
    }

    /**
     * One lazily created retainer per direction. Kept separate (rather than
     * sharing the R2L retainer for L2R) so each direction tracks its own lane
     * occupancy, as before.
     */
    private class Slot(val create: () -> IDanmakusRetainer) {

        private var instance: IDanmakusRetainer? = null

        fun instance(): IDanmakusRetainer = instance ?: create().also { instance = it }

        fun clear() {
            instance?.clear()
        }

        fun release() {
            instance?.clear()
            instance = null
        }
    }

    private val RL = Slot { RLDanmakusRetainer() }
    private val LR = Slot { RLDanmakusRetainer() }
    private val FT = Slot { FTDanmakusRetainer() }
    private val FB = Slot { FBDanmakusRetainer() }

    fun clear() {
        RL.clear()
        LR.clear()
        FT.clear()
        FB.clear()
    }

    fun release() {
        RL.release()
        LR.release()
        FT.release()
        FB.release()
    }

    interface Verifier {
        fun skipLayout(danmaku: BaseDanmaku, fixedTop: Float, lines: Int, willHit: Boolean): Boolean
    }

    interface IDanmakusRetainer {
        fun fix(drawItem: BaseDanmaku, disp: IDisplayer, verifier: Verifier?)
        fun clear()
    }

    /**
     * Occupied lanes, ordered top to bottom (or bottom to top for the
     * fixed-bottom retainer). Small by construction — one entry per usable line —
     * so keeping it sorted on every insert is cheaper than the drifting mutable
     * key ordering the previous `Danmakus(ST_BY_YPOS)` relied on.
     */
    private class Lanes(private val descending: Boolean) {

        private val items = ArrayList<BaseDanmaku>(24)

        private val order = Comparator<BaseDanmaku> { a, b ->
            val result = compareValues(a.getTop(), b.getTop())
            if (descending) -result else result
        }

        fun isEmpty(): Boolean = items.isEmpty()

        fun clear() = items.clear()

        operator fun get(index: Int): BaseDanmaku = items[index]

        val size: Int get() = items.size

        /** Re-inserts [item] in lane order, dropping any stale entry for it. */
        fun add(item: BaseDanmaku) {
            items.remove(item)
            items.add(item)
            // Only the tail can be out of place, so this is an insertion step.
            var i = items.size - 1
            while (i > 0 && order.compare(items[i - 1], items[i]) > 0) {
                val tmp = items[i - 1]
                items[i - 1] = items[i]
                items[i] = tmp
                i--
            }
        }

        fun remove(item: BaseDanmaku) {
            items.remove(item)
        }
    }

    private open class RLDanmakusRetainer : IDanmakusRetainer {

        protected open val mVisibleDanmakus = Lanes(descending = false)
        protected var mCancelFixingFlag = false

        override fun fix(drawItem: BaseDanmaku, disp: IDisplayer, verifier: Verifier?) {
            if (drawItem.isOutside()) return
            var topPos = 0f
            var lines = 0
            var willHit = !drawItem.isShown() && !mVisibleDanmakus.isEmpty()
            var isOutOfVerticalEdge = false
            val shown = drawItem.isShown()
            var removeItem: BaseDanmaku? = null

            if (!shown) {
                mCancelFixingFlag = false
                var insertItem: BaseDanmaku? = null
                var firstItem: BaseDanmaku? = null
                var lastItem: BaseDanmaku? = null
                var minRightRow: BaseDanmaku? = null
                var overwriteInsert = false
                val currTime = drawItem.getTimer()!!.currMillisecond
                val drawDuration = drawItem.getDuration()
                val drawHeight = drawItem.paintHeight
                val count = mVisibleDanmakus.size
                var i = 0

                while (!mCancelFixingFlag && i < count) {
                    lines++
                    val item = mVisibleDanmakus[i++]

                    if (item === drawItem) {
                        insertItem = item
                        lastItem = null
                        willHit = false
                        break
                    }

                    if (firstItem == null) firstItem = item

                    if (drawHeight + item.getTop() > disp.height) {
                        overwriteInsert = true
                        break
                    }

                    if (minRightRow == null || minRightRow.getRight() >= item.getRight()) {
                        minRightRow = item
                    }

                    willHit = DanmakuUtils.willHitInDuration(disp, item, drawItem, drawDuration, currTime)
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
                    }
                } else if (overwriteInsert && minRightRow != null) {
                    topPos = minRightRow.getTop()
                    checkEdge = false
                } else if (lastItem != null) {
                    topPos = lastItem.getBottom()
                    willHit = false
                } else if (firstItem != null) {
                    topPos = firstItem.getTop()
                    removeItem = firstItem
                } else {
                    topPos = 0f
                }

                if (checkEdge) {
                    isOutOfVerticalEdge = isOutVerticalEdge(drawItem, disp, topPos, firstItem)
                }
                if (isOutOfVerticalEdge) {
                    topPos = 0f
                    willHit = true
                }
            }

            if (verifier != null && verifier.skipLayout(drawItem, topPos, lines, willHit)) return

            if (isOutOfVerticalEdge) {
                clear()
            }

            drawItem.layout(disp, drawItem.getLeft(), topPos)

            if (!shown) {
                if (removeItem != null) mVisibleDanmakus.remove(removeItem)
                mVisibleDanmakus.add(drawItem)
            }
        }

        protected open fun isOutVerticalEdge(
            drawItem: BaseDanmaku, disp: IDisplayer, topPos: Float, firstItem: BaseDanmaku?
        ): Boolean {
            return topPos < 0 || (firstItem != null && firstItem.getTop() > 0) ||
                topPos + drawItem.paintHeight > disp.height
        }

        override fun clear() {
            mCancelFixingFlag = true
            mVisibleDanmakus.clear()
        }
    }

    private open class FTDanmakusRetainer : RLDanmakusRetainer() {

        override fun isOutVerticalEdge(
            drawItem: BaseDanmaku, disp: IDisplayer, topPos: Float, firstItem: BaseDanmaku?
        ): Boolean {
            return topPos + drawItem.paintHeight > disp.height
        }
    }

    private class FBDanmakusRetainer : FTDanmakusRetainer() {

        override val mVisibleDanmakus = Lanes(descending = true)

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
                val currTime = drawItem.getTimer()!!.currMillisecond
                val drawDuration = drawItem.getDuration()
                val drawHeight = drawItem.paintHeight
                val count = mVisibleDanmakus.size
                var i = 0

                while (!mCancelFixingFlag && i < count) {
                    lines++
                    val item = mVisibleDanmakus[i++]

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

                    willHit = DanmakuUtils.willHitInDuration(disp, item, drawItem, drawDuration, currTime)
                    if (!willHit) {
                        removeItem = item
                        break
                    }

                    topPos = item.getTop() - drawHeight
                }

                isOutOfVerticalEdge = isOutVerticalEdge(drawItem, disp, topPos, firstItem)
                if (isOutOfVerticalEdge) {
                    topPos = disp.height - drawHeight
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
                if (removeItem != null) mVisibleDanmakus.remove(removeItem)
                mVisibleDanmakus.add(drawItem)
            }
        }

        override fun isOutVerticalEdge(
            drawItem: BaseDanmaku, disp: IDisplayer, topPos: Float, firstItem: BaseDanmaku?
        ): Boolean {
            return topPos < 0 || (firstItem != null && firstItem.getBottom() != disp.height.toFloat())
        }
    }
}
