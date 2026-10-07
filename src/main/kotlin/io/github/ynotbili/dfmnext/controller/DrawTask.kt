package io.github.ynotbili.dfmnext.controller

import android.graphics.Canvas
import io.github.ynotbili.dfmnext.danmaku.model.AbsDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.DanmakuTimer
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext.ConfigChangedCallback
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext.DanmakuConfigTag
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import io.github.ynotbili.dfmnext.danmaku.parser.BaseDanmakuParser
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer.RenderingState
import io.github.ynotbili.dfmnext.danmaku.renderer.android.DanmakuRenderer
import io.github.ynotbili.dfmnext.danmaku.util.SystemClock
import io.github.ynotbili.dfmnext.danmaku.util.clearCanvas

open class DrawTask(
    timer: DanmakuTimer,
    protected val mContext: DanmakuContext,
    protected var mTaskListener: IDrawTask.TaskListener?
) : IDrawTask {

    protected val mDisp: AbsDisplayer = mContext.getDisplayer()

    protected var danmakuList: IDanmakus? = null

    protected var mParser: BaseDanmakuParser? = null

    val mRenderer: IRenderer = DanmakuRenderer(mContext)

    var mTimer: DanmakuTimer = timer
        protected set

    private var danmakus: IDanmakus = Danmakus(Danmakus.ST_BY_LIST)

    protected var clearRetainerFlag: Boolean = false

    private var mStartRenderTime: Long = 0

    private var mRenderingState = RenderingState()

    /** Read by the cache thread as well as the draw thread, hence volatile. */
    @Volatile
    protected var mReadyState: Boolean = false

    private var mLastBeginMills: Long = 0

    private var mLastEndMills: Long = 0

    private var mIsHidden: Boolean = false

    private var mLastDanmaku: BaseDanmaku? = null

    private val mLiveDanmakus = Danmakus(Danmakus.ST_BY_LIST)

    private val mConfigChangedCallback = object : ConfigChangedCallback {
        override fun onDanmakuConfigChanged(config: DanmakuContext, tag: DanmakuConfigTag, vararg value: Any?): Boolean {
            return onDanmakuConfigChanged(config, tag, *value)
        }
    }

    init {
        mRenderer.setOnDanmakuShownListener(object : IRenderer.OnDanmakuShownListener {
            override fun onDanmakuShown(danmaku: BaseDanmaku) {
                mTaskListener?.onDanmakuShown(danmaku)
            }
        })
        mRenderer.setVerifierEnabled(mContext.isPreventOverlappingEnabled() || mContext.isMaxLinesLimited())
        initTimer(timer)
        val enable = mContext.isDuplicateMergingEnabled()
        if (enable) {
            mContext.mDanmakuFilters.registerFilter(DanmakuFilters.TAG_DUPLICATE_FILTER)
        } else {
            mContext.mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_DUPLICATE_FILTER)
        }
    }

    protected open fun initTimer(timer: DanmakuTimer) {
        mTimer = timer
    }

    @Synchronized
    override fun addDanmaku(item: BaseDanmaku) {
        val list = danmakuList ?: return
        if (item.isLive) {
            mLiveDanmakus += item
            removeUnusedLiveDanmakusIn(10)
        }
        item.index = list.size()
        var subAdded = true
        if (item.time in mLastBeginMills..mLastEndMills) {
            @Suppress("ReplaceCallWithOperatorAssignment")
            subAdded = danmakus.addItem(item)
        } else if (item.isLive) {
            subAdded = false
        }
        @Suppress("ReplaceCallWithOperatorAssignment")
        val added = list.addItem(item)
        if (!subAdded) {
            mLastBeginMills = 0
            mLastEndMills = 0
        }
        if (added) {
            mTaskListener?.onDanmakuAdd(item)
        }
        val last = mLastDanmaku
        if (last == null || item.time > last.time) {
            mLastDanmaku = item
        }
    }

    override fun invalidateDanmaku(item: BaseDanmaku, remeasure: Boolean) {
        mContext.getDisplayer().getCacheStuffer().clearCache(item)
        if (remeasure) {
            item.paintWidth = -1f
            item.paintHeight = -1f
        }
    }

    @Synchronized
    override fun removeAllDanmakus(isClearDanmakusOnScreen: Boolean) {
        val list = danmakuList ?: return
        if (list.isEmpty()) return
        if (!isClearDanmakusOnScreen) {
            val beginMills = mTimer.currMillisecond - mContext.mDanmakuFactory.MAX_DANMAKU_DURATION - 100
            val endMills = mTimer.currMillisecond + mContext.mDanmakuFactory.MAX_DANMAKU_DURATION
            danmakus = list.subnew(beginMills, endMills)
        }
        list.clear()
    }

    protected open fun onDanmakuRemoved(danmaku: BaseDanmaku) {
        // TODO call callback here
    }

    @Synchronized
    override fun removeAllLiveDanmakus() {
        if (danmakus.isEmpty()) return
        val it = danmakus.iterator()
        while (it.hasNext()) {
            val danmaku = it.next()
            if (danmaku.isLive) {
                it.remove()
                onDanmakuRemoved(danmaku)
            }
        }
    }

    @Synchronized
    protected fun removeUnusedLiveDanmakusIn(msec: Int) {
        val list = danmakuList ?: return
        if (list.isEmpty() || mLiveDanmakus.isEmpty()) return
        val startTime = SystemClock.uptimeMillis()
        val it = mLiveDanmakus.iterator()
        while (it.hasNext()) {
            val danmaku = it.next()
            if (danmaku.isTimeOut()) {
                it.remove()
                @Suppress("ReplaceCallWithOperatorAssignment")
                list.removeItem(danmaku)
                onDanmakuRemoved(danmaku)
            } else {
                break
            }
            if (SystemClock.uptimeMillis() - startTime > msec) {
                break
            }
        }
    }

    override fun getVisibleDanmakusOnTime(time: Long): IDanmakus {
        val visibleDanmakus: IDanmakus = Danmakus()
        val subDanmakus = danmakuList?.sub(
            time - mContext.mDanmakuFactory.MAX_DANMAKU_DURATION - 100,
            time + mContext.mDanmakuFactory.MAX_DANMAKU_DURATION
        ) ?: return visibleDanmakus
        for (danmaku in subDanmakus) {
            if (danmaku.isShown() && !danmaku.isOutside()) {
                visibleDanmakus += danmaku
            }
        }
        return visibleDanmakus
    }

    @Synchronized
    override fun draw(displayer: AbsDisplayer): RenderingState {
        return drawDanmakus(displayer, mTimer) ?: RenderingState()
    }

    override fun reset() {
        danmakus = Danmakus()
        mRenderer.clear()
    }

    override fun seek(mills: Long) {
        reset()
        requestClear()
        mContext.mGlobalFlagValues.updateVisibleFlag()
        mContext.mGlobalFlagValues.updateFirstShownFlag()
        mStartRenderTime = if (mills < 1000) 0 else mills
        mRenderingState.reset()
        mRenderingState.endTime = mStartRenderTime
        val last = danmakuList?.last()
        if (last != null && !last.isTimeOut()) {
            mLastDanmaku = last
        }
    }

    override fun clearDanmakusOnScreen(currMillis: Long) {
        reset()
        mContext.mGlobalFlagValues.updateVisibleFlag()
        mContext.mGlobalFlagValues.updateFirstShownFlag()
        mStartRenderTime = currMillis
    }

    override fun start() {
        mContext.registerConfigChangedCallback(mConfigChangedCallback)
    }

    override fun quit() {
        mContext.unregisterAllConfigChangedCallbacks()
        mRenderer.release()
    }

    override fun prepare() {
        val parser = mParser ?: return
        loadDanmakus(parser)
        mLastBeginMills = 0
        mLastEndMills = 0
        val listener = mTaskListener
        if (listener != null) {
            listener.ready()
            mReadyState = true
        }
    }

    protected fun loadDanmakus(parser: BaseDanmakuParser) {
        val list = parser.setConfig(mContext).setDisplayer(mDisp).setTimer(mTimer).getDanmakus()
        danmakuList = list
        if (list != null && !list.isEmpty()) {
            if (list.first()?.flags == null) {
                for (item in list) {
                    item.flags = mContext.mGlobalFlagValues
                }
            }
            // Pre-measure the first batch so the first frames don't pay for
            // measurement one danmaku at a time. Capped so prepare() stays short
            // on large sets — the cache thread builds the rest off the render path.
            val measureIt = list.iterator()
            var measureCount = 0
            while (measureIt.hasNext() && measureCount < PREMEASURE_LIMIT) {
                val item = measureIt.next()
                if (!item.isMeasured()) {
                    item.measure(mDisp, true)
                }
                measureCount++
            }
            mLastDanmaku = list.last()
        }
        mContext.mGlobalFlagValues.resetAll()
    }

    override fun setParser(parser: BaseDanmakuParser?) {
        mParser = parser
        mReadyState = false
    }

    protected fun drawDanmakus(disp: AbsDisplayer, timer: DanmakuTimer): RenderingState? {
        if (clearRetainerFlag) {
            mRenderer.clearRetainer()
            clearRetainerFlag = false
        }
        val list = danmakuList ?: return null
        val canvas = disp.getExtraData() as Canvas
        canvas.clearCanvas()
        if (mIsHidden) {
            return mRenderingState
        }
        val curr = timer.currMillisecond
        val maxDuration = mContext.mDanmakuFactory.MAX_DANMAKU_DURATION
        var beginMills = curr - maxDuration - 100
        var endMills = curr + maxDuration
        // The per-frame window is a view over the sorted array, so the range is
        // re-resolved only when the previous window no longer covers the cursor;
        // steady state is two binary searches and no allocation.
        if (mLastBeginMills > beginMills || curr > mLastEndMills) {
            danmakus = list.sub(beginMills, endMills)
            mLastBeginMills = beginMills
            mLastEndMills = endMills
        } else {
            beginMills = mLastBeginMills
            endMills = mLastEndMills
        }
        if (danmakus.isEmpty()) {
            val state = mRenderingState
            state.nothingRendered = true
            state.beginTime = beginMills
            state.endTime = endMills
            return state
        }
        val renderingState = mRenderer.draw(mDisp, danmakus, mStartRenderTime).also { mRenderingState = it }
        if (renderingState.nothingRendered) {
            val last = mLastDanmaku
            if (last != null && last.isTimeOut()) {
                mLastDanmaku = null
                mTaskListener?.onDanmakusDrawingFinished()
            }
            if (renderingState.beginTime == RenderingState.UNKNOWN_TIME) {
                renderingState.beginTime = beginMills
            }
            if (renderingState.endTime == RenderingState.UNKNOWN_TIME) {
                renderingState.endTime = endMills
            }
        }
        return renderingState
    }

    override fun requestClear() {
        mLastBeginMills = 0
        mLastEndMills = 0
        mIsHidden = false
    }

    override fun requestClearRetainer() {
        clearRetainerFlag = true
    }

    open fun onDanmakuConfigChanged(config: DanmakuContext, tag: DanmakuConfigTag?, vararg values: Any?): Boolean {
        val handled = handleOnDanmakuConfigChanged(config, tag, values)
        if (mTaskListener != null) {
            mTaskListener!!.onDanmakuConfigChanged()
        }
        return handled
    }

    protected fun handleOnDanmakuConfigChanged(config: DanmakuContext, tag: DanmakuConfigTag?, values: Array<out Any?>): Boolean {
        var handled = false
        if (tag == null) {
            handled = true
        } else if (DanmakuConfigTag.DUPLICATE_MERGING_ENABLED == tag) {
            val enable = values[0] as? Boolean
            if (enable != null) {
                if (enable) {
                    mContext.mDanmakuFilters.registerFilter(DanmakuFilters.TAG_DUPLICATE_FILTER)
                } else {
                    mContext.mDanmakuFilters.unregisterFilter(DanmakuFilters.TAG_DUPLICATE_FILTER)
                }
                handled = true
            }
        } else if (DanmakuConfigTag.SCALE_TEXTSIZE == tag) {
            requestClearRetainer()
            handled = false
        } else if (DanmakuConfigTag.MAXIMUM_LINES == tag || DanmakuConfigTag.OVERLAPPING_ENABLE == tag) {
            mRenderer.setVerifierEnabled(mContext.isPreventOverlappingEnabled() || mContext.isMaxLinesLimited())
            handled = true
        }
        return handled
    }

    override fun requestHide() {
        mIsHidden = true
    }

    companion object {
        /** Upper bound on the synchronous pre-measure pass in [loadDanmakus]. */
        private const val PREMEASURE_LIMIT = 500
    }
}
