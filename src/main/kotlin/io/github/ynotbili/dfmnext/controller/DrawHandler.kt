package io.github.ynotbili.dfmnext.controller

import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.DisplayMetrics
import io.github.ynotbili.dfmnext.danmaku.model.AbsDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.DanmakuTimer
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.parser.BaseDanmakuParser
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer.RenderingState
import io.github.ynotbili.dfmnext.danmaku.util.AndroidUtils
import io.github.ynotbili.dfmnext.danmaku.util.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class DrawHandler(
    looper: Looper,
    view: IDanmakuView?,
    danmakuVisibile: Boolean
) : Handler(looper) {

    private var mContext: DanmakuContext? = null

    interface Callback {
        fun prepared()
        fun updateTimer(timer: DanmakuTimer)
        fun danmakuShown(danmaku: BaseDanmaku)
        fun drawingFinished()
    }

    private enum class Msg(val what: Int) {
        START(1), UPDATE(2), RESUME(3), SEEK_POS(4), PREPARE(5),
        QUIT(6), PAUSE(7), SHOW_DANMAKUS(8), HIDE_DANMAKUS(9),
        NOTIFY_DISP_SIZE_CHANGED(10), NOTIFY_RENDERING(11),
        UPDATE_WHEN_PAUSED(12), CLEAR_DANMAKUS_ON_SCREEN(13);

        companion object {
            private val byWhat = entries.associateBy { it.what }
            fun from(what: Int): Msg? = byWhat[what]
        }
    }

    companion object {
        @JvmField val START = Msg.START.what
        @JvmField val UPDATE = Msg.UPDATE.what
        @JvmField val RESUME = Msg.RESUME.what
        @JvmField val SEEK_POS = Msg.SEEK_POS.what
        @JvmField val PREPARE = Msg.PREPARE.what
        private const val INDEFINITE_TIME = 10000000L
        private const val MAX_RECORD_SIZE = 500
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }

    private var pausedPosition = 0L
    @Volatile private var quitFlag = true
    @Volatile private var mTimeBase = 0L
    @Volatile private var mReady = false
    private var mCallback: Callback? = null
    val timer = DanmakuTimer()
    private var mParser: BaseDanmakuParser? = null
    var drawTask: IDrawTask? = null

    /**
     * Idle handshake for the update thread. The previous implementation waited
     * on a plain `Object` monitor and, worse, spun with `SystemClock.sleep(1)`
     * in front of it — roughly a thousand wakeups a second while nothing was
     * changing. A `Condition` gives one interruptible, deadline-based wait.
     */
    private val mDrawLock = ReentrantLock()
    private val mRenderingIdle = mDrawLock.newCondition()
    private var mDanmakuView: IDanmakuView? = null
    private var mDanmakusVisible = true
    private var mDisp: AbsDisplayer? = null
    private val mRenderingState = RenderingState()
    private val mDrawTimes = ArrayDeque<Long>()
    private var mThread: UpdateThread? = null
    private val mUpdateInNewThread: Boolean = Runtime.getRuntime().availableProcessors() > 3
    private var mCordonTime = 30L
    private var mCordonTime2 = 60L
    private var mFrameUpdateRate = 16L
    private var mLastDeltaTime = 0L
    @Volatile private var mInSeekingAction = false
    private var mDesireSeekingTime = 0L
    @Volatile private var mRemainingTime = 0L
    private var mInSyncAction = false
    @Volatile private var mInWaitingState = false
    private var mSpeedOffsetNoRender = 0L
    private var mSpeedOffsetRender = 0L
    private var mSpeed = 1.0f

    init {
        bindView(view)
        if (danmakuVisibile) {
            showDanmakus(null)
        } else {
            hideDanmakus(false)
        }
        mDanmakusVisible = danmakuVisibile
    }

    private fun bindView(view: IDanmakuView?) {
        mDanmakuView = view
    }

    fun setConfig(config: DanmakuContext) {
        mContext = config
    }

    fun setParser(parser: BaseDanmakuParser) {
        mParser = parser
    }

    fun setCallback(cb: Callback?) {
        mCallback = cb
    }

    fun quit() {
        sendEmptyMessage(Msg.QUIT.what)
    }

    fun isStop(): Boolean = quitFlag

    override fun handleMessage(msg: Message) {
        when (Msg.from(msg.what)) {
            Msg.PREPARE -> {
                mTimeBase = SystemClock.uptimeMillis()
                if (mParser == null || mDanmakuView?.isViewReady() != true) {
                    sendEmptyMessageDelayed(PREPARE, 100)
                } else {
                    prepare {
                        pausedPosition = 0
                        mReady = true
                        mCallback?.prepared()
                    }
                }
            }
            Msg.SHOW_DANMAKUS -> {
                mDanmakusVisible = true
                val start = msg.obj as? Long
                var resume = false
                if (drawTask != null) {
                    if (start == null) {
                        timer.update(getCurrentTime())
                        drawTask!!.requestClear()
                        drawTask!!.requestClearRetainer()
                        mContext?.mGlobalFlagValues?.updateVisibleFlag()
                        mRenderingState.reset()
                        quitUpdateThread() // Recreate thread to completely flush state, mimicking pause/resume
                    } else {
                        drawTask!!.start()
                        drawTask!!.seek(start)
                        drawTask!!.requestClear()
                        resume = true
                    }
                }
                if (quitFlag && mDanmakuView != null) {
                    mDanmakuView!!.drawDanmakus()
                }
                notifyRendering()
                removeMessages(UPDATE)
                sendEmptyMessage(UPDATE)
                if (resume) {
                    val startTime = msg.obj as? Long
                    pausedPosition = startTime ?: 0
                    resumePlayback()
                }
            }
            Msg.START -> {
                val startTime = msg.obj as? Long
                pausedPosition = startTime ?: 0
                resumePlayback()
            }
            Msg.SEEK_POS -> {
                quitFlag = true
                quitUpdateThread()
                val position = msg.obj as Long
                timer.update(position)
                mRemainingTime = 0
                mSpeedOffsetNoRender = 0
                mSpeedOffsetRender = 0
                mLastDeltaTime = 0
                mContext?.mGlobalFlagValues?.updateMeasureFlag()
                drawTask?.seek(position)
                pausedPosition = position
                resumePlayback()
            }
            Msg.RESUME -> {
                if (quitFlag) {
                    resumePlayback()
                }
            }
            Msg.UPDATE -> {
                if (mUpdateInNewThread) {
                    updateInNewThread()
                } else {
                    updateInCurrentThread()
                }
            }
            Msg.NOTIFY_DISP_SIZE_CHANGED -> {
                val ctx = mContext ?: return
                ctx.mDanmakuFactory.notifyDispSizeChanged(ctx)
                val updateFlag = msg.obj as? Boolean
                if (updateFlag != null && updateFlag) {
                    ctx.mGlobalFlagValues.updateMeasureFlag()
                }
            }
            Msg.HIDE_DANMAKUS -> {
                mDanmakusVisible = false
                mDanmakuView?.clear()
                drawTask?.let {
                    it.requestClear()
                    it.requestHide()
                }
                val quitDrawTask = msg.obj as Boolean
                if (quitDrawTask) {
                    drawTask?.quit()
                    removeMessages(UPDATE)
                    stopPlayback(quitLooper = false)
                }
            }
            Msg.PAUSE -> {
                removeMessages(UPDATE)
                syncTimerIfNeeded()
                stopPlayback(quitLooper = false)
            }
            Msg.QUIT -> { stopPlayback(quitLooper = true) }
            Msg.NOTIFY_RENDERING -> { notifyRendering() }
            Msg.UPDATE_WHEN_PAUSED -> {
                if (quitFlag && mDanmakuView != null) {
                    drawTask?.requestClear()
                    mDanmakuView?.drawDanmakus()
                    notifyRendering()
                }
            }
            Msg.CLEAR_DANMAKUS_ON_SCREEN -> {
                drawTask?.clearDanmakusOnScreen(getCurrentTime())
            }
            null -> {}
        }
    }

    private fun resumePlayback() {
        quitFlag = false
        if (mReady) {
            mRenderingState.reset()
            mDrawTimes.clear()
            mTimeBase = SystemClock.uptimeMillis() - pausedPosition
            timer.update(pausedPosition)
            removeMessages(RESUME)
            sendEmptyMessage(UPDATE)
            drawTask!!.start()
            notifyRendering()
            mInSeekingAction = false
        } else {
            sendEmptyMessageDelayed(RESUME, 100)
        }
    }

    private fun stopPlayback(quitLooper: Boolean = false) {
        if (quitLooper) {
            removeCallbacksAndMessages(null)
        }
        quitFlag = true
        syncTimerIfNeeded()
        if (mThread != null) {
            notifyRendering()
            quitUpdateThread()
        }
        pausedPosition = timer.currMillisecond
        if (quitLooper) {
            drawTask?.quit()
            mParser?.release()
            mDanmakuView = null
            mCallback = null
            if (looper != Looper.getMainLooper()) {
                looper.quit()
            }
        }
    }

    private fun quitUpdateThread() {
        val thread = mThread ?: return
        mThread = null
        thread.quit()
        wakeUpRendering()
        try {
            thread.join(THREAD_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun updateInCurrentThread() {
        if (quitFlag) return
        val startMS = SystemClock.uptimeMillis()
        var d = syncTimer(startMS)
        if (d < 0) {
            removeMessages(UPDATE)
            sendEmptyMessageDelayed(UPDATE, 60 - d)
            return
        }
        val view = mDanmakuView ?: return
        d = view.drawDanmakus()
        removeMessages(UPDATE)
        if (d > mCordonTime2) {
            timer.add(d)
            mDrawTimes.clear()
        }
        if (!mDanmakusVisible) {
            waitRendering(INDEFINITE_TIME)
            return
        } else if (mRenderingState.nothingRendered) {
            val dTime = mRenderingState.endTime - timer.currMillisecond
            if (dTime > 500) {
                waitRendering(dTime - 10)
                return
            }
        }
        if (d < mFrameUpdateRate) {
            sendEmptyMessageDelayed(UPDATE, mFrameUpdateRate - d)
            return
        }
        sendEmptyMessage(UPDATE)
    }

    private fun updateInNewThread() {
        if (mThread != null) return
        mThread = object : UpdateThread("DFM Update") {
            override fun run() {
                var lastTime = SystemClock.uptimeMillis()
                while (!isQuited() && !quitFlag) {
                    val startMS = SystemClock.uptimeMillis()
                    val timeToNextFrame = mFrameUpdateRate - (startMS - lastTime)
                    if (timeToNextFrame > 0) {
                        // One timed wait instead of the old `SystemClock.sleep(1)`
                        // spin: same pacing, ~1000 fewer wakeups per second, and a
                        // seek or pause interrupts it immediately.
                        if (!idleWait(timeToNextFrame)) break
                        continue
                    }
                    lastTime = startMS
                    var d = syncTimer(startMS)
                    if (d < 0) {
                        if (!idleWait(60 - d)) break
                        continue
                    }
                    val view = mDanmakuView ?: break
                    d = view.drawDanmakus()
                    if (d > mCordonTime2) {
                        timer.add(d)
                        clearDrawTimes()
                    }
                    if (!mDanmakusVisible) {
                        waitRendering(INDEFINITE_TIME)
                    } else if (mRenderingState.nothingRendered) {
                        val dTime = mRenderingState.endTime - timer.currMillisecond
                        if (dTime > 500) {
                            notifyRendering()
                            waitRendering(dTime - 10)
                        }
                    }
                }
            }
        }
        mThread!!.start()
    }

    /**
     * Interruptible wait on the render/idle handshake. Returns false when the
     * thread should stop (quit flag set, or interrupted while unwinding).
     */
    private fun idleWait(mills: Long): Boolean {
        mDrawLock.lock()
        try {
            mRenderingIdle.await(mills, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            mDrawLock.unlock()
        }
        return !quitFlag
    }

    private fun clearDrawTimes() {
        mDrawLock.lock()
        try {
            mDrawTimes.clear()
        } finally {
            mDrawLock.unlock()
        }
    }

    fun setSpeed(speed: Float) {
        mSpeed = speed
    }

    fun syncTimer(startMS: Long): Long {
        if (mInSeekingAction || mInSyncAction) return 0
        mInSyncAction = true
        var d = 0L
        val time = startMS - mTimeBase
        val gapTime = time - timer.currMillisecond
        if (!mDanmakusVisible || mInWaitingState || (mRenderingState.nothingRendered && gapTime > 1000)) {
            // Direct wall-clock sync: only when invisible, waiting, or idle for >1s
            val targetTime = time + mSpeedOffsetNoRender + mSpeedOffsetRender
            if (targetTime > timer.currMillisecond || mInSeekingAction) {
                timer.update(targetTime)
            }
            mSpeedOffsetNoRender += (mFrameUpdateRate * (mSpeed - 1)).toLong()
            mRemainingTime = 0
            mCallback?.updateTimer(timer)
        } else {
            var gap = gapTime
            if (gap > 2000) {
                // Very large gap: direct sync to wall-clock
                d = gap
                gap = 0
            } else if (gap > 0) {
                // Timer behind: proportional catch-up
                d = maxOf(gap / 5, mCordonTime)
                mSpeedOffsetRender += (d * (mSpeed - 1)).toLong()
                d = (d * mSpeed).toLong()
                gap -= d
                mLastDeltaTime = d
            } else {
                // Timer ahead or on time: advance at frame rate only
                d = mFrameUpdateRate
                gap -= d
                mLastDeltaTime = d
            }
            mRemainingTime = gap
            timer.add(d)
        }
        mCallback?.updateTimer(timer)
        mInSyncAction = false
        return d
    }

    fun syncTimerIfNeeded() {
        if (mInWaitingState) {
            syncTimer(SystemClock.uptimeMillis())
        }
    }

    private fun initRenderingConfigs() {
        val averageFrameConsumingTime = 16L
        mCordonTime = maxOf(33, (averageFrameConsumingTime * 2.5f).toLong())
        mCordonTime2 = (mCordonTime * 2.5f).toLong()
        mFrameUpdateRate = maxOf(16, averageFrameConsumingTime / 15 * 15)
    }

    private fun prepare(runnable: Runnable) {
        if (drawTask == null) {
            val view = mDanmakuView ?: return
            drawTask = createDrawTask(
                view.isDanmakuDrawingCacheEnabled(),
                timer,
                view.getContext(),
                view.getWidth(),
                view.getHeight(),
                view.isHardwareAccelerated(),
                object : IDrawTask.TaskListener {
                    override fun ready() {
                        initRenderingConfigs()
                        runnable.run()
                    }

                    override fun onDanmakuAdd(danmaku: BaseDanmaku) {
                        if (danmaku.isTimeOut()) return
                        val delay = danmaku.time - timer.currMillisecond
                        if (delay > 0) {
                            sendEmptyMessageDelayed(Msg.NOTIFY_RENDERING.what, delay)
                        } else if (mInWaitingState) {
                            notifyRendering()
                        }
                    }

                    override fun onDanmakuShown(danmaku: BaseDanmaku) {
                        mCallback?.danmakuShown(danmaku)
                    }

                    override fun onDanmakusDrawingFinished() {
                        mCallback?.drawingFinished()
                    }

                    override fun onDanmakuConfigChanged() {
                        redrawIfNeeded()
                    }
                }
            )
        } else {
            runnable.run()
        }
    }

    fun isPrepared(): Boolean = mReady

    private fun createDrawTask(
        useDrawingCache: Boolean,
        timer: DanmakuTimer,
        context: android.content.Context,
        width: Int,
        height: Int,
        isHardwareAccelerated: Boolean,
        taskListener: IDrawTask.TaskListener
    ): IDrawTask {
        val ctx = mContext ?: throw IllegalStateException("mContext not set")
        mDisp = ctx.getDisplayer()
        mDisp!!.setSize(width, height)
        val displayMetrics: DisplayMetrics = context.resources.displayMetrics
        val scaledDensity = displayMetrics.density * context.resources.configuration.fontScale
        mDisp!!.setDensities(displayMetrics.density, displayMetrics.densityDpi, scaledDensity)
        mDisp!!.resetSlopPixel(ctx.scaleTextSize)
        mDisp!!.setHardwareAccelerated(isHardwareAccelerated)
        val task: IDrawTask = if (useDrawingCache) {
            CacheManagingDrawTask(
                timer, ctx, taskListener,
                1024 * 1024 * AndroidUtils.getMemoryClass(context) / 3
            )
        } else {
            DrawTask(timer, ctx, taskListener)
        }
        task.setParser(mParser)
        task.prepare()
        obtainMessage(Msg.NOTIFY_DISP_SIZE_CHANGED.what, false).sendToTarget()
        return task
    }

    fun seekTo(ms: Long?) {
        if (ms == null) return
        mInSeekingAction = true
        mDesireSeekingTime = ms
        removeMessages(UPDATE)
        removeMessages(RESUME)
        removeMessages(SEEK_POS)
        obtainMessage(SEEK_POS, ms).sendToTarget()
    }

    fun addDanmaku(item: BaseDanmaku) {
        drawTask?.let {
            item.flags = mContext?.mGlobalFlagValues
            item.setTimer(timer)
            it.addDanmaku(item)
            obtainMessage(Msg.NOTIFY_RENDERING.what).sendToTarget()
        }
    }

    fun invalidateDanmaku(item: BaseDanmaku?, remeasure: Boolean) {
        if (drawTask != null && item != null) {
            drawTask!!.invalidateDanmaku(item, remeasure)
        }
        redrawIfNeeded()
    }

    fun resume() {
        sendEmptyMessage(RESUME)
    }

    fun prepare() {
        sendEmptyMessage(PREPARE)
    }

    fun pause() {
        removeMessages(RESUME)
        removeMessages(UPDATE)
        syncTimerIfNeeded()
        sendEmptyMessage(Msg.PAUSE.what)
    }

    fun showDanmakus(position: Long?) {
        if (mDanmakusVisible) return
        mDanmakusVisible = true
        removeMessages(Msg.SHOW_DANMAKUS.what)
        removeMessages(Msg.HIDE_DANMAKUS.what)
        obtainMessage(Msg.SHOW_DANMAKUS.what, position).sendToTarget()
    }

    fun hideDanmakus(quitDrawTask: Boolean): Long {
        if (!mDanmakusVisible) return timer.currMillisecond
        mDanmakusVisible = false
        removeMessages(Msg.SHOW_DANMAKUS.what)
        removeMessages(Msg.HIDE_DANMAKUS.what)
        obtainMessage(Msg.HIDE_DANMAKUS.what, quitDrawTask).sendToTarget()
        return timer.currMillisecond
    }

    fun getVisibility(): Boolean = mDanmakusVisible

    fun draw(canvas: Canvas): RenderingState {
        if (drawTask == null) return mRenderingState
        val disp = mDisp ?: return mRenderingState
        disp.setExtraData(canvas)
        mRenderingState.set(drawTask!!.draw(disp))
        recordRenderingTime()
        return mRenderingState
    }

    private fun redrawIfNeeded() {
        if (quitFlag && mDanmakusVisible) {
            obtainMessage(Msg.UPDATE_WHEN_PAUSED.what).sendToTarget()
        }
    }

    private fun notifyRendering() {
        if (!mInWaitingState) return
        drawTask?.requestClear()
        if (mUpdateInNewThread) {
            clearDrawTimes()
            wakeUpRendering()
            removeMessages(UPDATE)
            sendEmptyMessage(UPDATE)
        } else {
            removeMessages(UPDATE)
            sendEmptyMessage(UPDATE)
        }
        mInWaitingState = false
    }

    private fun wakeUpRendering() {
        mDrawLock.lock()
        try {
            mRenderingIdle.signalAll()
        } finally {
            mDrawLock.unlock()
        }
    }

    private fun waitRendering(dTime: Long) {
        mRenderingState.sysTime = SystemClock.uptimeMillis()
        mInWaitingState = true
        if (mUpdateInNewThread) {
            if (mThread == null) return
            mDrawLock.lock()
            try {
                if (dTime == INDEFINITE_TIME) {
                    while (mInWaitingState && !quitFlag) {
                        mRenderingIdle.await()
                    }
                } else {
                    mRenderingIdle.await(dTime, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                mDrawLock.unlock()
            }
            sendEmptyMessage(Msg.NOTIFY_RENDERING.what)
        } else {
            removeMessages(Msg.NOTIFY_RENDERING.what)
            removeMessages(UPDATE)
            if (dTime != INDEFINITE_TIME) {
                sendEmptyMessageDelayed(Msg.NOTIFY_RENDERING.what, dTime)
            }
        }
    }

    @Synchronized
    private fun recordRenderingTime() {
        val lastTime = SystemClock.uptimeMillis()
        mDrawTimes.addLast(lastTime)
        while (mDrawTimes.size > MAX_RECORD_SIZE) {
            mDrawTimes.removeFirst()
        }
    }

    fun getDisplayer(): IDisplayer? = mDisp

    fun notifyDispSizeChanged(width: Int, height: Int) {
        val disp = mDisp ?: return
        if (disp.width != width || disp.height != height) {
            disp.setSize(width, height)
            obtainMessage(Msg.NOTIFY_DISP_SIZE_CHANGED.what, true).sendToTarget()
        }
    }

    fun removeAllDanmakus(isClearDanmakusOnScreen: Boolean) {
        drawTask?.removeAllDanmakus(isClearDanmakusOnScreen)
    }

    fun removeAllLiveDanmakus() {
        drawTask?.removeAllLiveDanmakus()
    }

    fun getCurrentVisibleDanmakus(): IDanmakus? {
        return drawTask?.getVisibleDanmakusOnTime(getCurrentTime())
    }

    fun getCurrentTime(): Long {
        if (!mReady) return 0
        if (mInSeekingAction) return mDesireSeekingTime
        if (quitFlag || !mInWaitingState) return timer.currMillisecond - mRemainingTime
        return SystemClock.uptimeMillis() - mTimeBase
    }

    fun clearDanmakusOnScreen() {
        obtainMessage(Msg.CLEAR_DANMAKUS_ON_SCREEN.what).sendToTarget()
    }

    fun getConfig(): DanmakuContext? = mContext
}
