package io.github.ynotbili.dfmnext.danmaku.renderer.android

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.DanmakuTimer
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer
import io.github.ynotbili.dfmnext.danmaku.util.SystemClock
import io.github.ynotbili.dfmnext.danmaku.util.isScrolling
import io.github.ynotbili.dfmnext.danmaku.util.isSpecial

class DanmakuRenderer(private val mContext: DanmakuContext) : IRenderer {

    private val mStartTimer = DanmakuTimer()
    private val mRenderingState = IRenderer.RenderingState()
    private var mVerifier: DanmakusRetainer.Verifier? = null
    private val verifier = object : DanmakusRetainer.Verifier {
        override fun skipLayout(danmaku: BaseDanmaku, fixedTop: Float, lines: Int, willHit: Boolean): Boolean {
            if (danmaku.priority == 0.toByte() &&
                mContext.mDanmakuFilters.filterSecondary(danmaku, lines, 0, mStartTimer, willHit, mContext)
            ) {
                danmaku.setVisibility(false)
                return true
            }
            return false
        }
    }
    private val mDanmakusRetainer = DanmakusRetainer()
    private var mCacheManager: ((BaseDanmaku) -> Unit)? = null
    private var mOnDanmakuShownListener: IRenderer.OnDanmakuShownListener? = null

    /**
     * Special danmakus are drawn last so their transform/alpha state cannot
     * affect the plain scrolling pass. Reused across frames — no per-frame list.
     */
    private val mSpecialDanmakusToDraw = ArrayList<BaseDanmaku>(32)

    override fun clear() {
        clearRetainer()
        mContext.mDanmakuFilters.clear()
    }

    override fun clearRetainer() {
        mDanmakusRetainer.clear()
    }

    override fun release() {
        mDanmakusRetainer.release()
        mContext.mDanmakuFilters.clear()
    }

    override fun setVerifierEnabled(enabled: Boolean) {
        mVerifier = if (enabled) verifier else null
    }

    override fun draw(disp: IDisplayer, danmakus: IDanmakus, startRenderTime: Long): IRenderer.RenderingState {
        val lastTotalDanmakuCount = mRenderingState.totalDanmakuCount
        mRenderingState.reset()
        mSpecialDanmakusToDraw.clear()

        val firstShownResetFlag = mContext.mGlobalFlagValues.FIRST_SHOWN_RESET_FLAG
        val cacheManager = mCacheManager
        val shownListener = mOnDanmakuShownListener
        val filters = mContext.mDanmakuFilters
        val maximumSpecialCount = SPECIAL_DANMAKU_LIMIT

        mStartTimer.update(SystemClock.uptimeMillis())
        val frameStartMs = SystemClock.uptimeMillis()
        val sizeInScreen = danmakus.size()

        var orderInScreen = 0
        var specialDanmakuCount = 0
        var drawItem: BaseDanmaku? = null

        val itr = danmakus.iterator()
        while (itr.hasNext()) {
            val item = itr.next()
            drawItem = item

            // Frame budget: keep the render loop off the critical path when the
            // screen is unusually dense. Checked cheaply, once per item.
            if (SystemClock.uptimeMillis() - frameStartMs > FRAME_BUDGET_MS) break

            if (!item.hasPassedFilter()) {
                filters.filter(item, orderInScreen, sizeInScreen, mStartTimer, false, mContext)
            }

            if (item.time < startRenderTime || (item.priority == 0.toByte() && item.isFiltered())) continue

            if (item.isLate()) {
                if (cacheManager != null && !item.hasDrawingCache()) cacheManager.invoke(item)
                break
            }

            if (item.isScrolling) {
                orderInScreen++
            } else if (item.isSpecial) {
                if (item.isOutside()) continue
                if (++specialDanmakuCount > maximumSpecialCount) continue
            }

            if (!item.isMeasured()) item.measure(disp, false)

            mDanmakusRetainer.fix(item, disp, mVerifier)

            if (item.isOutside() || !item.isShown()) continue
            if (item.lines == null && item.getBottom() > disp.height) continue

            if (item.isSpecial) {
                mSpecialDanmakusToDraw.add(item)
                continue
            }

            if (!drawItem(item, disp, cacheManager, shownListener, firstShownResetFlag)) continue

            mRenderingState.addCount(item.getType(), 1)
            mRenderingState.addTotalCount(1)
        }

        for (i in mSpecialDanmakusToDraw.indices) {
            val specialItem = mSpecialDanmakusToDraw[i]
            if (!drawItem(specialItem, disp, cacheManager, shownListener, firstShownResetFlag)) continue
            mRenderingState.addCount(specialItem.getType(), 1)
            mRenderingState.addTotalCount(1)
        }

        mRenderingState.nothingRendered = mRenderingState.totalDanmakuCount == 0
        mRenderingState.endTime = drawItem?.time ?: IRenderer.RenderingState.UNKNOWN_TIME
        if (mRenderingState.nothingRendered) {
            mRenderingState.beginTime = IRenderer.RenderingState.UNKNOWN_TIME
        }
        mRenderingState.incrementCount = mRenderingState.totalDanmakuCount - lastTotalDanmakuCount
        mRenderingState.consumingTime = mStartTimer.update(SystemClock.uptimeMillis())
        return mRenderingState
    }

    private fun drawItem(
        item: BaseDanmaku,
        disp: IDisplayer,
        cacheManager: ((BaseDanmaku) -> Unit)?,
        shownListener: IRenderer.OnDanmakuShownListener?,
        firstShownResetFlag: Int,
    ): Boolean {
        val renderingType = try {
            item.draw(disp)
        } catch (_: Exception) {
            // A single malformed danmaku must not abort the frame.
            return false
        }
        when (renderingType) {
            IRenderer.CACHE_RENDERING -> mRenderingState.cacheHitCount++
            IRenderer.TEXT_RENDERING -> {
                mRenderingState.cacheMissCount++
                cacheManager?.invoke(item)
            }
        }
        if (shownListener != null && item.firstShownFlag != firstShownResetFlag) {
            item.firstShownFlag = firstShownResetFlag
            shownListener.onDanmakuShown(item)
        }
        return true
    }

    override fun setCacheManager(addDanmaku: ((BaseDanmaku) -> Unit)?) {
        mCacheManager = addDanmaku
    }

    override fun setOnDanmakuShownListener(listener: IRenderer.OnDanmakuShownListener) {
        mOnDanmakuShownListener = listener
    }

    override fun removeOnDanmakuShownListener() {
        mOnDanmakuShownListener = null
    }

    companion object {
        /** Hard per-frame rendering budget in milliseconds. */
        private const val FRAME_BUDGET_MS = 12L

        /** Cap on how many special (animated) danmakus are drawn per frame. */
        private const val SPECIAL_DANMAKU_LIMIT = 50
    }
}
