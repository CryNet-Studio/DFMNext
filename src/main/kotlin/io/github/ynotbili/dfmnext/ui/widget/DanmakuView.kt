package io.github.ynotbili.dfmnext.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.HandlerThread
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import io.github.ynotbili.dfmnext.controller.DrawHandler
import io.github.ynotbili.dfmnext.controller.DrawHandler.Callback
import io.github.ynotbili.dfmnext.controller.IDanmakuView
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.util.clearCanvas
import io.github.ynotbili.dfmnext.danmaku.util.drawFps
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.parser.BaseDanmakuParser
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer.RenderingState
import io.github.ynotbili.dfmnext.danmaku.util.SystemClock

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle), IDanmakuView {

    companion object {
        const val TAG = "DanmakuView"
        private const val MAX_RECORD_SIZE = 50
        private const val ONE_SECOND = 1000

        /** How long the draw thread waits for one invalidated frame to be produced. */
        private const val DRAW_HANDOFF_TIMEOUT_MS = 200L

        /** Upper bound for shutting the handler thread down. */
        private const val HANDLER_JOIN_TIMEOUT_MS = 500L
    }

    private var mCallback: Callback? = null
    private var mHandlerThread: HandlerThread? = null
    private var mHandler: DrawHandler? = null
    private var isSurfaceCreated = false
    private var enableDanmakuDrawingCache = true
    private var mOnDanmakuClickListener: IDanmakuView.OnDanmakuClickListener? = null
    private var mTouchHelper: DanmakuTouchHelper? = null
    private var mShowFps = false
    private var mDanmakuVisible = true
    protected var mDrawingThreadType = IDanmakuView.THREAD_TYPE_NORMAL_PRIORITY

    /**
     * Handoff between the draw thread (which blocks in [lockCanvas] until a
     * frame has been produced) and the UI thread's `onDraw`. A `Lock` with a
     * `Condition` replaces the raw monitor so the wait has a real timeout
     * invariant and cannot be woken by an unrelated `notify()`.
     */
    private val mDrawLock = ReentrantLock()
    private val mDrawCompleted = mDrawLock.newCondition()
    private var mDrawFinished = false
    private var mRequestRender = false
    /**
     * The thread that created the view, i.e. the UI thread. Compared by identity
     * instead of `Thread.getId()`, which is deprecated for removal.
     */
    private val mUiThread = Thread.currentThread()
    private val mDrawTimes = ArrayDeque<Long>(MAX_RECORD_SIZE + 1)
    private var mClearFlag = false
    private var mResumeTryCount = 0
    private var mLastDrawDuration = 0L

    private val mResumeRunnable: Runnable = Runnable {
        if (mHandler == null) return@Runnable
        mResumeTryCount++
        if (mResumeTryCount > 4 || super.isShown()) {
            try {
                mHandler!!.resume()
            } catch (_: IllegalStateException) {
                // Handler thread already dead
            }
        } else {
            try {
                mHandler!!.postDelayed(mResumeRunnable, (100 * mResumeTryCount).toLong())
            } catch (_: IllegalStateException) {
                // Handler thread already dead
            }
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        mTouchHelper = DanmakuTouchHelper.instance(this)
        // Stay transparent to touch unless a click listener is registered, so
        // gesture detectors on parent views keep receiving events.
        isClickable = false
        isLongClickable = false
        isFocusable = false
    }

    override fun addDanmaku(item: BaseDanmaku) {
        mHandler?.addDanmaku(item)
    }

    override fun invalidateDanmaku(item: BaseDanmaku, remeasure: Boolean) {
        mHandler?.invalidateDanmaku(item, remeasure)
    }

    override fun removeAllDanmakus(isClearDanmakusOnScreen: Boolean) {
        mHandler?.removeAllDanmakus(isClearDanmakusOnScreen)
    }

    override fun removeAllLiveDanmakus() {
        mHandler?.removeAllLiveDanmakus()
    }

    override fun getCurrentVisibleDanmakus(): IDanmakus? {
        return mHandler?.getCurrentVisibleDanmakus()
    }

    override fun setCallback(callback: Callback?) {
        mCallback = callback
        mHandler?.setCallback(callback)
    }

    override fun getHandler(): DrawHandler? = mHandler

    override fun release() {
        stop()
        mDrawTimes.clear()
        mCallback = null
        mOnDanmakuClickListener = null
        mTouchHelper = null
    }

    override fun stop() {
        stopDraw()
    }

    private fun stopDraw() {
        val h = mHandler
        mHandler = null
        unlockCanvasAndPost()
        h?.quit()
        val handlerThread = mHandlerThread
        if (handlerThread != null) {
            mHandlerThread = null
            // quitSafely() first, then join: the looper exit is what ends the
            // thread, so waiting before it (as the old code did, with a fixed
            // Thread.sleep(50)) only added latency and could still race.
            handlerThread.quitSafely()
            try {
                handlerThread.join(HANDLER_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    protected fun getLooper(type: Int): Looper {
        mHandlerThread?.let {
            it.quit()
            mHandlerThread = null
        }

        val priority: Int
        when (type) {
            IDanmakuView.THREAD_TYPE_MAIN_THREAD -> return Looper.getMainLooper()
            IDanmakuView.THREAD_TYPE_HIGH_PRIORITY -> priority = android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
            IDanmakuView.THREAD_TYPE_LOW_PRIORITY -> priority = android.os.Process.THREAD_PRIORITY_LOWEST
            IDanmakuView.THREAD_TYPE_NORMAL_PRIORITY -> priority = android.os.Process.THREAD_PRIORITY_DEFAULT
            else -> priority = android.os.Process.THREAD_PRIORITY_DEFAULT
        }
        val threadName = "DFM Handler Thread #$priority"
        val ht = HandlerThread(threadName, priority)
        mHandlerThread = ht
        ht.start()
        return ht.looper
    }

    private fun prepare() {
        if (mHandler == null) {
            mHandler = DrawHandler(getLooper(mDrawingThreadType), this, mDanmakuVisible)
        }
    }

    override fun prepare(parser: BaseDanmakuParser?, config: DanmakuContext) {
        prepare()
        val h = mHandler ?: return
        h.setConfig(config)
        h.setParser(parser!!)
        h.setCallback(mCallback)
        h.prepare()
    }

    override fun isPrepared(): Boolean {
        return mHandler?.isPrepared() ?: false
    }

    override fun getConfig(): DanmakuContext? {
        return mHandler?.getConfig()
    }

    override fun showFPS(show: Boolean) {
        mShowFps = show
    }

    private fun fps(): Float {
        mDrawTimes.addLast(SystemClock.uptimeMillis())
        while (mDrawTimes.size > MAX_RECORD_SIZE) mDrawTimes.removeFirst()
        val span = mDrawTimes.last() - mDrawTimes.first()
        return if (span > 0) mDrawTimes.size * ONE_SECOND.toFloat() / span else 0f
    }

    override fun drawDanmakus(): Long {
        if (!isSurfaceCreated) return 0
        if (!isShown()) return -1
        mLastDrawDuration = 0
        val stime = SystemClock.uptimeMillis()
        lockCanvas()
        return SystemClock.uptimeMillis() - stime + mLastDrawDuration
    }

    private fun postInvalidateCompat() {
        mRequestRender = true
        postInvalidateOnAnimation()
    }

    private fun lockCanvas() {
        if (!mDanmakuVisible) return
        postInvalidateCompat()
        mDrawLock.lock()
        try {
            while (!mDrawFinished && mHandler != null) {
                if (!mDanmakuVisible || mHandler?.isStop() == true) break
                if (!mDrawCompleted.await(DRAW_HANDOFF_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    break
                }
            }
            mDrawFinished = false
        } finally {
            mDrawLock.unlock()
        }
    }

    private fun lockCanvasAndClear() {
        mClearFlag = true
        lockCanvas()
    }

    private fun unlockCanvasAndPost() {
        mDrawLock.lock()
        try {
            mDrawFinished = true
            mDrawCompleted.signalAll()
        } finally {
            mDrawLock.unlock()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!mDanmakuVisible && !mRequestRender) {
            super.onDraw(canvas)
            return
        }
        val drawStart = SystemClock.uptimeMillis()
        if (mClearFlag) {
            canvas.clearCanvas()
            mClearFlag = false
        } else {
            val handler = mHandler
            if (handler != null) {
                val rs = handler.draw(canvas)
                if (mShowFps) canvas.drawFps(fpsText(rs))
            }
        }
        mLastDrawDuration = SystemClock.uptimeMillis() - drawStart
        mRequestRender = false
        unlockCanvasAndPost()
    }

    /**
     * Debug overlay text. Built without `String.format`, which costs a format
     * parse plus a locale lookup for every single frame.
     */
    private fun fpsText(rs: RenderingState): String =
        "fps " + fps().formatFraction() +
            ",time:" + (getCurrentTime() / 1000) + " s" +
            ",cache:" + rs.cacheHitCount +
            ",miss:" + rs.cacheMissCount

    private fun Float.formatFraction(): String {
        val scaled = (this * 100).toLong()
        return (scaled / 100).toString() + "." + (scaled % 100).toString().padStart(2, '0')
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        mHandler?.notifyDispSizeChanged(right - left, bottom - top)
        isSurfaceCreated = true
    }

    override fun toggle() {
        if (isSurfaceCreated) {
            if (mHandler == null) start()
            else if (mHandler?.isStop() == true) resume()
            else pause()
        }
    }

    override fun pause() {
        mHandler?.let {
            it.removeCallbacks(mResumeRunnable)
            it.pause()
        }
    }

    override fun resume() {
        val h = mHandler
        if (h != null && h.isPrepared()) {
            h.removeCallbacks(mResumeRunnable)
            mResumeTryCount = 0
            try {
                h.postDelayed(mResumeRunnable, 50)
            } catch (_: IllegalStateException) {
                restart()
            }
        } else if (h == null) {
            restart()
        }
    }

    override fun isPaused(): Boolean {
        return mHandler?.isStop() ?: false
    }

    fun restart() {
        stop()
        start()
    }

    override fun start() {
        start(0)
    }

    override fun start(position: Long) {
        if (mHandler == null) {
            prepare()
        } else {
            mHandler?.removeCallbacks(mResumeRunnable)
        }
        mHandler?.obtainMessage(DrawHandler.START, position)?.sendToTarget()
    }

    /**
     * Touch-transparent by design: hosts that embed this view (a Compose
     * `AndroidView` with a sibling gesture detector, for instance) must keep
     * receiving every event themselves, so nothing here may consume the
     * sequence.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun seekTo(ms: Long?) {
        if (mHandler?.isPrepared() == true) {
            mHandler?.seekTo(ms)
        }
    }

    override fun setSpeed(speed: Float) {
        if (mHandler?.isPrepared() == true) {
            mHandler?.setSpeed(speed)
        }
    }

    override fun adjust() {
        mHandler?.syncTimerIfNeeded()
    }

    override fun enableDanmakuDrawingCache(enable: Boolean) {
        enableDanmakuDrawingCache = enable
    }

    override fun isDanmakuDrawingCacheEnabled(): Boolean = enableDanmakuDrawingCache

    override fun isViewReady(): Boolean = isSurfaceCreated

    override fun getView(): View = this

    override fun show() {
        showAndResumeDrawTask(null)
    }

    override fun showAndResumeDrawTask(position: Long?) {
        mDanmakuVisible = true
        mClearFlag = false
        mHandler?.showDanmakus(position)
    }

    override fun hide() {
        mDanmakuVisible = false
        mHandler?.hideDanmakus(false)
    }

    override fun hideAndPauseDrawTask(): Long {
        mDanmakuVisible = false
        return mHandler?.hideDanmakus(true) ?: 0
    }

    override fun clear() {
        if (!isViewReady()) return
        if (!mDanmakuVisible || Thread.currentThread() === mUiThread) {
            mClearFlag = true
            postInvalidateCompat()
        } else {
            lockCanvasAndClear()
        }
    }

    override fun isShown(): Boolean {
        return mDanmakuVisible && super.isShown()
    }

    override fun setDrawingThreadType(type: Int) {
        mDrawingThreadType = type
    }

    override fun getCurrentTime(): Long {
        return mHandler?.getCurrentTime() ?: 0
    }

    override fun isHardwareAccelerated(): Boolean = super.isHardwareAccelerated()

    override fun clearDanmakusOnScreen() {
        mHandler?.clearDanmakusOnScreen()
    }

    override fun setOnDanmakuClickListener(listener: IDanmakuView.OnDanmakuClickListener?) {
        mOnDanmakuClickListener = listener
        isClickable = listener != null
    }

    override fun getOnDanmakuClickListener(): IDanmakuView.OnDanmakuClickListener? = mOnDanmakuClickListener

    override fun getLayoutParams(): ViewGroup.LayoutParams? = super.getLayoutParams()

    override fun setLayoutParams(params: ViewGroup.LayoutParams?) {
        super.setLayoutParams(params)
    }
}
