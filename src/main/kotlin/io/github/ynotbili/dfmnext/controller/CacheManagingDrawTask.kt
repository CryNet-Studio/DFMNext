@file:Suppress("unused")

package io.github.ynotbili.dfmnext.controller

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message

import io.github.ynotbili.dfmnext.danmaku.model.AbsDisplayer
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.DanmakuTimer
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext.DanmakuConfigTag
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import io.github.ynotbili.dfmnext.danmaku.model.android.DrawingCache
import io.github.ynotbili.dfmnext.danmaku.model.objectpool.Pool
import io.github.ynotbili.dfmnext.danmaku.model.objectpool.Pools
import io.github.ynotbili.dfmnext.danmaku.renderer.IRenderer.RenderingState
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import io.github.ynotbili.dfmnext.danmaku.util.SystemClock
import io.github.ynotbili.dfmnext.danmaku.util.isScrollRL
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-renders danmakus into bitmap caches on a background thread.
 *
 * Restructured versus the previous implementation for three reasons:
 *
 *  1. Cache accounting was broken. `evictAllNotInScreen()` reset `mRealSize` to
 *     zero regardless of what was actually freed, so the pool believed it was
 *     empty while bitmaps were still held. The next `push()` therefore allowed
 *     another full budget of allocations — memory could drift to 2x-3x the
 *     configured cap before an OOM forced a reset. Freed bytes are now tracked
 *     per entry.
 *  2. The cache thread slept *inside* `handleMessage` — up to 100 ms per
 *     danmaku while waiting on the draw thread's monitor. Live danmaku requests
 *     (`CACHE_BIND_CACHE`) queued behind that sleep, which is why a newly sent
 *     comment appeared uncached and took the slow text-render path. Building is
 *     now budgeted per pass and paced with `sendMessageDelayed`, so the queue
 *     stays responsive and the pacing honours the observed render cost.
 *  3. Duplicate bitmaps. Finding a reusable cache was a linear scan over the
 *     cache collection (20 probes for the strict match, 50 for the loose one)
 *     on every build. Strict matches — the common case, since danmaku text
 *     repeats heavily — are now an `O(1)` keyed lookup; the loose, "steal a
 *     slightly larger buffer" probe only scans the timed-out prefix, which is
 *     exactly the range it was allowed to accept anyway.
 */
class CacheManagingDrawTask(
    timer: DanmakuTimer,
    config: DanmakuContext,
    taskListener: IDrawTask.TaskListener?,
    maxCacheSize: Int
) : DrawTask(timer, config, taskListener) {

    private val mMaxCacheSize = maxCacheSize

    private var mCacheManager: CacheManager? = null

    private lateinit var mCacheTimer: DanmakuTimer

    /**
     * Reported by the renderer each frame. Used to back off cache building when
     * the main render loop is already struggling, replacing the per-item
     * wait/notify handshake with the draw thread.
     */
    @Volatile
    private var mLastDrawConsumingTime: Long = 0

    init {
        mCacheManager = CacheManager(mMaxCacheSize, MAX_CACHE_SCREEN_SIZE)
        mRenderer.setCacheManager { mCacheManager?.addDanmaku(it) }
    }

    override fun initTimer(timer: DanmakuTimer) {
        mTimer = timer
        mCacheTimer = DanmakuTimer()
        mCacheTimer.update(timer.currMillisecond)
    }

    override fun addDanmaku(item: BaseDanmaku) {
        super.addDanmaku(item)
        mCacheManager?.addDanmaku(item)
    }

    override fun invalidateDanmaku(item: BaseDanmaku, remeasure: Boolean) {
        val manager = mCacheManager
        if (manager == null) {
            super.invalidateDanmaku(item, remeasure)
            return
        }
        manager.invalidateDanmaku(item, remeasure)
    }

    override fun removeAllDanmakus(isClearDanmakusOnScreen: Boolean) {
        super.removeAllDanmakus(isClearDanmakusOnScreen)
        mCacheManager?.requestClearAll()
    }

    override fun onDanmakuRemoved(danmaku: BaseDanmaku) {
        super.onDanmakuRemoved(danmaku)
        val cache = danmaku.cache as? DrawingCache ?: return
        if (cache.hasReferences()) {
            cache.decreaseReference()
        } else {
            cache.destroy()
        }
        danmaku.cache = null
    }

    override fun draw(displayer: AbsDisplayer): RenderingState {
        val result = super.draw(displayer)
        mLastDrawConsumingTime = result.consumingTime
        if (result.incrementCount < DENSITY_DROP_THRESHOLD) {
            val manager = mCacheManager
            if (manager != null) {
                manager.requestClearTimeout()
                manager.requestBuild(-mContext.mDanmakuFactory.MAX_DANMAKU_DURATION)
            }
        }
        return result
    }

    override fun seek(mills: Long) {
        super.seek(mills)
        val manager = mCacheManager
        if (manager == null) {
            start()
        } else {
            manager.seek(mills)
        }
    }

    override fun start() {
        super.start()
        var manager = mCacheManager
        if (manager == null) {
            manager = CacheManager(mMaxCacheSize, MAX_CACHE_SCREEN_SIZE)
            mCacheManager = manager
            mRenderer.setCacheManager { mCacheManager?.addDanmaku(it) }
            manager.begin()
        } else {
            manager.resume()
        }
    }

    override fun quit() {
        super.quit()
        reset()
        mRenderer.setCacheManager(null)
        mCacheManager?.end()
        mCacheManager = null
    }

    override fun prepare() {
        val parser = mParser ?: return
        loadDanmakus(parser)
        mCacheManager?.begin()
    }

    inner class CacheManager(
        private val mMaxSize: Int,
        private val mScreenSize: Int = MAX_CACHE_SCREEN_SIZE,
    ) {

        private var _mThread: HandlerThread? = null
        val mThread: HandlerThread? get() = _mThread

        /** Cache entries, ordered by appear time — the eviction order. */
        val mCaches = Danmakus()

        private val mCachePool: Pool<DrawingCache> =
            Pools.finitePool(DrawingCache.PoolManager, POOL_LIMIT)

        /**
         * Content -> already-rendered holder. Only touched by the cache thread,
         * so a plain `HashMap` is enough (the previous shared `HashMap` in
         * `SimpleTextCacheStuffer` was not, which is why that one moved to a
         * concurrent map).
         */
        private val reuseIndex = HashMap<CacheKey, BaseDanmaku>(128)

        @Volatile
        private var mRealSize: Int = 0

        private var mHandler: CacheHandler? = null

        @Volatile
        private var mEndFlag: Boolean = false

        fun seek(mills: Long) {
            val handler = mHandler ?: return
            handler.requestCancelCaching()
            handler.removeMessages(CACHE_BUILD_CACHES)
            handler.obtainMessage(CACHE_SEEK, mills).sendToTarget()
        }

        fun addDanmaku(danmaku: BaseDanmaku) {
            val handler = mHandler ?: return
            if (danmaku.isLive) {
                if (danmaku.forceBuildCacheInSameThread) {
                    if (!danmaku.isTimeOut()) handler.createCache(danmaku)
                } else {
                    handler.obtainMessage(CACHE_BIND_CACHE, danmaku).sendToTarget()
                }
            } else {
                handler.obtainMessage(CACHE_ADD_DANMAKKU, danmaku).sendToTarget()
            }
        }

        fun invalidateDanmaku(danmaku: BaseDanmaku, remeasure: Boolean) {
            val handler = mHandler ?: return
            handler.requestCancelCaching()
            handler.obtainMessage(CACHE_REBUILD_CACHE, Pair(danmaku, remeasure)).sendToTarget()
        }

        fun begin() {
            mEndFlag = false
            var thread = _mThread
            if (thread == null) {
                thread = HandlerThread("DFM Cache-Building Thread")
                thread.start()
                _mThread = thread
            }
            var handler = mHandler
            if (handler == null) {
                handler = CacheHandler(thread.looper)
                mHandler = handler
            }
            handler.begin()
        }

        /**
         * No `Thread.sleep(50)` any more: [CacheHandler.pause] posts the quit
         * message and `join` waits for it for real, so shutdown is both faster
         * in the common case and correct when the cache thread is mid-build.
         */
        fun end() {
            mEndFlag = true
            val handler = mHandler
            val thread = _mThread
            mHandler = null
            if (handler != null) {
                handler.pause()
            }
            if (thread != null) {
                try {
                    thread.join(THREAD_JOIN_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                thread.looper.quitSafely()
                _mThread = null
            }
            reuseIndex.clear()
        }

        fun resume() {
            val handler = mHandler
            if (handler != null) handler.resume() else begin()
        }

        fun getPoolPercent(): Float = if (mMaxSize == 0) 0f else mRealSize / mMaxSize.toFloat()

        fun isPoolFull(): Boolean = mRealSize + POOL_HEADROOM_BYTES >= mMaxSize

        // --- eviction -------------------------------------------------------

        private fun evictAll() {
            val victims = ArrayList<BaseDanmaku>(max(16, mCaches.size()))
            val itr = mCaches.iterator()
            while (itr.hasNext()) victims.add(itr.next())
            mCaches.clear()
            for (victim in victims) discardCache(victim)
            reuseIndex.clear()
            mRealSize = 0
        }

        /**
         * Frees caches that can no longer be drawn. Entries whose bitmap is
         * shared with a still-visible danmaku are only dropped when
         * [removeAllReferences] is set, matching the previous full-reset path.
         */
        private fun evictAllNotInScreen(removeAllReferences: Boolean) {
            val victims = ArrayList<BaseDanmaku>(64)
            mCaches.removeWhere { danmaku ->
                val cache = danmaku.cache
                val shared = cache != null && cache.hasReferences()
                val outside = danmaku.isOutside()
                val drop = when {
                    removeAllReferences && shared -> true
                    shared && outside -> true
                    !danmaku.hasDrawingCache() || outside -> true
                    else -> false
                }
                if (drop) victims.add(danmaku)
                drop
            }
            for (victim in victims) discardCache(victim)
        }

        private fun clearTimeOutCaches(time: Long) {
            val victims = ArrayList<BaseDanmaku>(32)
            // Entries are time-ordered, so the timed-out set is a prefix.
            mCaches.removeHeadWhile { danmaku ->
                if (danmaku.isTimeOut(time)) {
                    victims.add(danmaku)
                    true
                } else {
                    false
                }
            }
            for (victim in victims) discardCache(victim)
            if (victims.isNotEmpty()) mCachePool.trimToSize(mCaches.size() / 2)
        }

        /**
         * Drops one entry's cache, releasing the bitmap when this was its last
         * user, and uncharges the bytes that actually went away.
         *
         * The previous version subtracted the size unconditionally *and* zeroed
         * `mRealSize` after every sweep, so the budget recovered after each
         * eviction pass regardless of how much was really freed.
         */
        private fun discardCache(danmaku: BaseDanmaku): Int {
            val cache = danmaku.cache as? DrawingCache ?: return 0
            danmaku.cache = null
            forgetReuse(danmaku, cache)
            if (cache.get() == null) {
                mCachePool.release(cache)
                return 0
            }
            val size = cache.size()
            if (cache.hasReferences()) cache.decreaseReference()
            if (!cache.hasReferences()) {
                cache.destroy()
                mCachePool.release(cache)
                if (size > 0) mRealSize = max(0, mRealSize - size)
            }
            if (danmaku.isTimeOut()) releaseStufferResources(danmaku)
            return size
        }

        private fun releaseStufferResources(danmaku: BaseDanmaku) {
            mContext.getDisplayer().getCacheStuffer().releaseResource(danmaku)
        }

        private fun forgetReuse(danmaku: BaseDanmaku, cache: DrawingCache) {
            if (danmaku.text == null) return
            val key = CacheKey(danmaku)
            if (reuseIndex[key] === danmaku) reuseIndex.remove(key)
        }

        private fun uncharge(size: Int) {
            if (size > 0) mRealSize = max(0, mRealSize - size)
        }

        private fun clearCachePool() {
            var item = mCachePool.acquire()
            while (item != null) {
                item.destroy()
                item = mCachePool.acquire()
            }
        }

        /**
         * Registers [item] as a cache user and keeps the pool under [mMaxSize]
         * by evicting from the head (oldest first). Returns false when the item
         * does not fit and nothing off screen can be sacrificed — the caller then
         * falls back to rendering text directly.
         *
         * [itemSize] is charged only when this entry is the bitmap's first user;
         * shared caches already carry their charge. That is the accounting the
         * old code never performed.
         */
        private fun push(item: BaseDanmaku, itemSize: Int, forcePush: Boolean): Boolean {
            if (mRealSize + itemSize > mMaxSize) {
                val victims = ArrayList<BaseDanmaku>(16)
                mCaches.removeHeadWhile { danmaku ->
                    if (danmaku.isTimeOut() || danmaku.isOutside()) {
                        victims.add(danmaku)
                        true
                    } else {
                        false
                    }
                }
                for (victim in victims) discardCache(victim)
                if (!forcePush && mRealSize + itemSize > mMaxSize) return false
            }
            mCaches.addItem(item)
            mRealSize += itemSize
            return true
        }

        fun getFirstCacheTime(): Long = mCaches.first()?.time ?: 0L

        fun requestBuild(correctionTime: Long) {
            mHandler?.requestBuildCacheAndDraw(correctionTime)
        }

        fun requestClearAll() {
            val handler = mHandler ?: return
            handler.removeMessages(CACHE_BUILD_CACHES)
            handler.requestCancelCaching()
            handler.removeMessages(CACHE_CLEAR_ALL_CACHES)
            handler.sendEmptyMessage(CACHE_CLEAR_ALL_CACHES)
        }

        fun requestClearUnused() {
            val handler = mHandler ?: return
            handler.removeMessages(CACHE_CLEAR_OUTSIDE_CACHES_AND_RESET)
            handler.sendEmptyMessage(CACHE_CLEAR_OUTSIDE_CACHES_AND_RESET)
        }

        fun requestClearTimeout() {
            val handler = mHandler ?: return
            handler.removeMessages(CACHE_CLEAR_TIMEOUT_CACHES)
            handler.sendEmptyMessageDelayed(CACHE_CLEAR_TIMEOUT_CACHES, TIMEOUT_SWEEP_DELAY_MS)
        }

        fun post(runnable: Runnable) {
            mHandler?.post(runnable)
        }

        inner class CacheHandler(looper: Looper) : Handler(looper) {

            @Volatile private var mPause = false
            @Volatile private var mSeekedFlag = false
            @Volatile private var mCancelFlag = false

            fun requestCancelCaching() {
                mCancelFlag = true
            }

            override fun handleMessage(msg: Message) {
                if (mEndFlag && msg.what != CACHE_QUIT) return
                when (msg.what) {
                    CACHE_PREPARE -> {
                        evictAllNotInScreen(false)
                        // Warm the object pool only as far as it pays off: every
                        // entry is a DrawingCache + holder, and the old eager 800
                        // pre-allocation cost both startup time and retained heap.
                        val prealloc = min(mMaxSize / PREALLOC_PER_ENTRY_BYTES, PREALLOC_MAX)
                        for (i in 0 until prealloc) {
                            mCachePool.release(DrawingCache())
                        }
                        dispatchActions()
                    }
                    CACHE_DISPATCH_ACTIONS -> dispatchActions()
                    CACHE_BUILD_CACHES -> {
                        removeMessages(CACHE_BUILD_CACHES)
                        val repositioned = !mReadyState || mSeekedFlag
                        val hasMore = prepareCaches(repositioned)
                        if (repositioned) mSeekedFlag = false
                        if (!mReadyState) {
                            mTaskListener?.ready()
                            mReadyState = true
                        }
                        if (hasMore) scheduleBuild(BUILD_RESUME_DELAY_MS)
                    }
                    CACHE_ADD_DANMAKKU -> {
                        val item = msg.obj as BaseDanmaku
                        addDanmakuAndBuildCache(item)
                    }
                    CACHE_BIND_CACHE -> {
                        val danmaku = msg.obj as BaseDanmaku
                        if (!danmaku.isTimeOut()) createCache(danmaku)
                    }
                    CACHE_REBUILD_CACHE -> rebuildCache(msg.obj)
                    CACHE_CLEAR_TIMEOUT_CACHES -> clearTimeOutCaches(mTimer.currMillisecond)
                    CACHE_SEEK -> seekTo(msg.obj as Long)
                    CACHE_QUIT -> {
                        removeCallbacksAndMessages(null)
                        mPause = true
                        evictAll()
                        clearCachePool()
                        looper.quit()
                    }
                    CACHE_CLEAR_ALL_CACHES -> {
                        evictAll()
                        mCacheTimer.update(mTimer.currMillisecond - maxDuration())
                        mSeekedFlag = true
                    }
                    CACHE_CLEAR_OUTSIDE_CACHES -> {
                        evictAllNotInScreen(true)
                        mCacheTimer.update(mTimer.currMillisecond)
                    }
                    CACHE_CLEAR_OUTSIDE_CACHES_AND_RESET -> {
                        evictAllNotInScreen(true)
                        mCacheTimer.update(mTimer.currMillisecond)
                        requestClear()
                    }
                }
            }

            private fun rebuildCache(payload: Any?) {
                @Suppress("UNCHECKED_CAST")
                val pair = payload as? Pair<BaseDanmaku, Boolean> ?: return
                val (item, remeasure) = pair
                if (remeasure) {
                    item.requestFlags = item.requestFlags or BaseDanmaku.FLAG_REQUEST_REMEASURE
                    item.measureResetFlag++
                }
                item.requestFlags = item.requestFlags or BaseDanmaku.FLAG_REQUEST_INVALIDATE
                val existing = item.cache as? DrawingCache
                if (!remeasure && existing != null && existing.get() != null &&
                    !existing.hasReferences()
                ) {
                    // Re-render into the current bitmap: no allocation, no resize.
                    forgetReuse(item, existing)
                    val previousSize = existing.size()
                    DanmakuUtils.buildDanmakuDrawingCache(item, mDisp, existing)
                    item.cache = existing
                    if (!mCaches.contains(item)) {
                        push(item, existing.size(), true)
                    } else {
                        // The buffer may have grown while re-rendering.
                        uncharge(previousSize)
                        mRealSize += existing.size()
                    }
                    indexForReuse(item)
                    return
                }
                if (item.isLive) {
                    discardCache(item)
                    createCache(item)
                } else {
                    discardCache(item)
                    addDanmakuAndBuildCache(item)
                }
            }

            private fun seekTo(seekMills: Long) {
                val oldCacheTime = mCacheTimer.currMillisecond
                mCacheTimer.update(seekMills)
                mSeekedFlag = true
                val firstCacheTime = getFirstCacheTime()
                if (seekMills > oldCacheTime ||
                    firstCacheTime - seekMills > maxDuration()
                ) {
                    evictAllNotInScreen(false)
                } else {
                    clearTimeOutCaches(seekMills)
                }
                mCancelFlag = false
                mPause = false
                prepareCaches(true)
                resume()
            }

            /**
             * Decides what to do next, on the cache thread. Kept as a small
             * state machine over pool pressure plus how far the cache has
             * run ahead of playback.
             */
            private fun dispatchActions() {
                val maxDuration = maxDuration()
                val currTime = mTimer.currMillisecond
                if (mCacheTimer.currMillisecond <= currTime - maxDuration) {
                    evictAllNotInScreen(false)
                    mCacheTimer.update(currTime)
                    sendEmptyMessage(CACHE_BUILD_CACHES)
                } else {
                    val level = getPoolPercent()
                    val firstCache = mCaches.first()
                    val gapTime = if (firstCache != null) firstCache.time - currTime else 0L
                    val doubleScreenDuration = maxDuration * 2
                    when {
                        level < 0.6f && gapTime > maxDuration -> {
                            mCacheTimer.update(currTime)
                            removeMessages(CACHE_BUILD_CACHES)
                            sendEmptyMessage(CACHE_BUILD_CACHES)
                        }
                        level > 0.4f && gapTime < -doubleScreenDuration -> {
                            requestClearTimeout()
                        }
                        level >= 0.9f -> requestClearTimeout()
                        else -> {
                            val deltaTime = mCacheTimer.currMillisecond - currTime
                            if (firstCache != null && firstCache.isTimeOut() &&
                                deltaTime < -maxDuration
                            ) {
                                mCacheTimer.update(currTime)
                                sendEmptyMessage(CACHE_CLEAR_OUTSIDE_CACHES)
                                sendEmptyMessage(CACHE_BUILD_CACHES)
                            } else if (deltaTime > doubleScreenDuration) {
                                scheduleBuild(maxDuration)
                                return
                            } else {
                                removeMessages(CACHE_BUILD_CACHES)
                                sendEmptyMessage(CACHE_BUILD_CACHES)
                            }
                        }
                    }
                }
                sendEmptyMessageDelayed(CACHE_DISPATCH_ACTIONS, maxDuration / 2)
            }

            private fun scheduleBuild(delay: Long) {
                removeMessages(CACHE_BUILD_CACHES)
                sendEmptyMessageDelayed(CACHE_BUILD_CACHES, buildPace(delay))
            }

            /**
             * Pacing that replaces the old in-loop sleeps: the further the cache
             * has run ahead of playback, and the harder the render thread is
             * working, the longer the next pass waits.
             */
            private fun buildPace(baseDelay: Long): Long {
                val ahead = mCacheTimer.currMillisecond - mTimer.currMillisecond
                var delay = baseDelay + ahead / PACING_AHEAD_DIVISOR
                if (mLastDrawConsumingTime > SLOW_FRAME_MS) {
                    delay += mLastDrawConsumingTime
                }
                return delay.coerceIn(BUILD_RESUME_DELAY_MS, MAX_BUILD_PACE_MS)
            }

            /**
             * Builds caches within the pending range until the time budget is
             * spent. Returns whether there is work left for the next pass.
             */
            private fun prepareCaches(repositioned: Boolean): Boolean {
                val curr = mCacheTimer.currMillisecond
                val maxDuration = maxDuration()
                val end = curr + maxDuration * mScreenSize
                if (end < mTimer.currMillisecond) return false
                val list = danmakuList ?: return false
                val pending: IDanmakus = list.subnew(curr, end)
                if (pending.isEmpty()) {
                    mCacheTimer.update(end)
                    return false
                }
                val last = pending.last() ?: return false
                if (last.time < mTimer.currMillisecond) {
                    mCacheTimer.update(end)
                    return false
                }

                val itr = pending.iterator()
                val startTime = SystemClock.uptimeMillis()
                val sizeInScreen = pending.size()
                var orderInScreen = 0
                var currScreenIndex = 0L
                var lastBuilt: BaseDanmaku? = null
                var stoppedForBudget = false

                while (!mPause && !mCancelFlag && itr.hasNext()) {
                    val item = itr.next()
                    if (item.hasDrawingCache()) continue
                    if (!repositioned && (item.isTimeOut() || !item.isOutside())) continue
                    if (item.priority == 0.toByte() && item.isFiltered()) continue

                    if (!item.hasPassedFilter()) {
                        mContext.mDanmakuFilters.filter(
                            item, orderInScreen, sizeInScreen, null, true, mContext
                        )
                    }

                    if (item.isScrollRL) {
                        val screenIndex = (item.time - curr) / maxDuration
                        if (currScreenIndex == screenIndex) orderInScreen++ else {
                            orderInScreen = 0
                            currScreenIndex = screenIndex
                        }
                    }

                    if (!repositioned &&
                        SystemClock.uptimeMillis() - startTime >= BUILD_BUDGET_MS
                    ) {
                        stoppedForBudget = true
                        break
                    }

                    if (buildCache(item, false) == RESULT_FAILED) break
                    lastBuilt = item
                }

                mCacheTimer.update(if (lastBuilt != null) lastBuilt.time else end)
                return stoppedForBudget && !mPause && !mCancelFlag && !mEndFlag
            }

            fun createCache(item: BaseDanmaku): Boolean =
                buildCache(item, true) == RESULT_SUCCESS

            private fun buildCache(item: BaseDanmaku, forceInsert: Boolean): Byte {
                if (!item.isMeasured()) {
                    item.measure(mDisp, true)
                }
                var cache: DrawingCache? = null
                return try {
                    // 1. Identical content already rendered: share the bitmap.
                    val holder = findReusableCache(item)
                    if (holder != null) {
                        val shared = holder.cache as? DrawingCache
                        if (shared != null && shared.get() != null) {
                            shared.increaseReference()
                            item.cache = shared
                            if (push(item, 0, forceInsert)) {
                                indexForReuse(item)
                                return RESULT_SUCCESS
                            }
                            shared.decreaseReference()
                            item.cache = null
                            return RESULT_FAILED
                        }
                    }

                    // 2. A timed-out, unshared buffer that is big enough: redraw
                    //    into it instead of allocating a new bitmap.
                    val donor = findReusableBuffer(item)
                    if (donor != null) {
                        cache = donor.cache as? DrawingCache
                        if (cache != null) {
                            uncharge(cache.size())
                            mCaches.removeItem(donor)
                            donor.cache = null
                            forgetReuse(donor, cache)
                        }
                        cache = DanmakuUtils.buildDanmakuDrawingCache(item, mDisp, cache)
                        item.cache = cache
                        val pushed = push(item, cache.size(), forceInsert)
                        if (!pushed) releaseDanmakuCache(item, cache) else indexForReuse(item)
                        return if (pushed) RESULT_SUCCESS else RESULT_FAILED
                    }

                    if (!forceInsert) {
                        val cacheSize = DanmakuUtils.getCacheSize(
                            item.paintWidth.toInt(), item.paintHeight.toInt()
                        )
                        if (mRealSize + cacheSize > mMaxSize) return RESULT_FAILED
                    }

                    cache = mCachePool.acquire()
                    cache = DanmakuUtils.buildDanmakuDrawingCache(item, mDisp, cache)
                    item.cache = cache
                    val pushed = push(item, cache.size(), forceInsert)
                    if (!pushed) releaseDanmakuCache(item, cache) else indexForReuse(item)
                    if (pushed) RESULT_SUCCESS else RESULT_FAILED
                } catch (_: OutOfMemoryError) {
                    releaseDanmakuCache(item, cache)
                    evictAllNotInScreen(true)
                    RESULT_FAILED
                } catch (_: Exception) {
                    releaseDanmakuCache(item, cache)
                    RESULT_FAILED
                }
            }

            private fun releaseDanmakuCache(item: BaseDanmaku, cache: DrawingCache?) {
                val actual = cache ?: item.cache as? DrawingCache
                item.cache = null
                if (actual == null) return
                forgetReuse(item, actual)
                uncharge(actual.size())
                actual.destroy()
                mCachePool.release(actual)
            }

            /** `O(1)` duplicate lookup replacing the old 20-entry linear probe. */
            private fun findReusableCache(ref: BaseDanmaku): BaseDanmaku? {
                if (ref.text == null) return null
                val key = CacheKey(ref)
                val holder = reuseIndex[key] ?: return null
                val cache = holder.cache as? DrawingCache
                if (cache == null || cache.get() == null) {
                    reuseIndex.remove(key)
                    return null
                }
                return holder
            }

            /**
             * Buffer steal. Only the timed-out prefix qualifies (the old probe
             * bailed out at the first live entry anyway), and the scan is bounded
             * so a stalled playback clock cannot make this quadratic.
             */
            private fun findReusableBuffer(ref: BaseDanmaku): BaseDanmaku? {
                val slop = mDisp.slopPixel * BUFFER_SLOP_MULTIPLIER
                val currTime = mTimer.currMillisecond
                var candidates = 0
                val itr = mCaches.iterator()
                while (itr.hasNext() && candidates < BUFFER_SCAN_LIMIT) {
                    val danmaku = itr.next()
                    if (!danmaku.isTimeOut(currTime)) break
                    if (danmaku.paintWidth < ref.paintWidth ||
                        danmaku.paintHeight < ref.paintHeight
                    ) {
                        candidates++
                        continue
                    }
                    val widthGap = danmaku.paintWidth - ref.paintWidth
                    val heightGap = danmaku.paintHeight - ref.paintHeight
                    if (widthGap <= slop && heightGap <= slop) {
                        val cache = danmaku.cache as? DrawingCache
                        if (cache != null && cache.get() != null && !cache.hasReferences()) {
                            return danmaku
                        }
                    }
                    candidates++
                }
                return null
            }

            private fun indexForReuse(danmaku: BaseDanmaku) {
                if (danmaku.text == null) return
                if (reuseIndex.size >= REUSE_INDEX_MAX) {
                    reuseIndex.clear()
                }
                reuseIndex[CacheKey(danmaku)] = danmaku
            }

            private fun addDanmakuAndBuildCache(danmaku: BaseDanmaku) {
                if (danmaku.isTimeOut() ||
                    (danmaku.time > mCacheTimer.currMillisecond + maxDuration() && !danmaku.isLive)
                ) {
                    return
                }
                if (danmaku.priority == 0.toByte() && danmaku.isFiltered()) return
                if (!danmaku.hasDrawingCache()) {
                    buildCache(danmaku, true)
                }
            }

            fun begin() {
                mPause = false
                mCancelFlag = false
                removeMessages(CACHE_DISPATCH_ACTIONS)
                removeMessages(CACHE_PREPARE)
                sendEmptyMessage(CACHE_PREPARE)
                sendEmptyMessageDelayed(
                    CACHE_CLEAR_TIMEOUT_CACHES, TIMEOUT_SWEEP_DELAY_MS
                )
            }

            fun pause() {
                mPause = true
                removeCallbacksAndMessages(null)
                sendEmptyMessage(CACHE_QUIT)
            }

            fun resume() {
                mCancelFlag = false
                mPause = false
                removeMessages(CACHE_DISPATCH_ACTIONS)
                sendEmptyMessage(CACHE_DISPATCH_ACTIONS)
                sendEmptyMessageDelayed(
                    CACHE_CLEAR_TIMEOUT_CACHES, TIMEOUT_SWEEP_DELAY_MS
                )
            }

            fun isPause(): Boolean = mPause

            fun requestBuildCacheAndDraw(correctionTime: Long) {
                removeMessages(CACHE_BUILD_CACHES)
                mSeekedFlag = true
                mCancelFlag = false
                mCacheTimer.update(mTimer.currMillisecond + correctionTime)
                sendEmptyMessage(CACHE_BUILD_CACHES)
            }
        }

        private fun maxDuration(): Long = mContext.mDanmakuFactory.MAX_DANMAKU_DURATION
    }

    /**
     * Identity of a rendered bitmap: same text, metrics and colours means the
     * cache can be shared. Mirrors the fields the previous linear probe compared.
     */
    private class CacheKey(danmaku: BaseDanmaku) {

        private val text: CharSequence? = danmaku.text
        private val width: Float = danmaku.paintWidth
        private val height: Float = danmaku.paintHeight
        private val textColor: Int = danmaku.textColor
        private val underlineColor: Int = danmaku.underlineColor
        private val borderColor: Int = danmaku.borderColor
        private val hash: Int = computeHash()

        private fun computeHash(): Int {
            var h = text?.hashCode() ?: 0
            h = h * 31 + width.toRawBits()
            h = h * 31 + height.toRawBits()
            h = h * 31 + textColor
            h = h * 31 + underlineColor
            h = h * 31 + borderColor
            return h
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CacheKey) return false
            return width == other.width &&
                height == other.height &&
                textColor == other.textColor &&
                underlineColor == other.underlineColor &&
                borderColor == other.borderColor &&
                contentEquals(text, other.text)
        }

        private fun contentEquals(a: CharSequence?, b: CharSequence?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return false
            return DanmakuUtils.contentEquals(a, b)
        }
    }

    override fun onDanmakuConfigChanged(config: DanmakuContext, tag: DanmakuConfigTag?, vararg values: Any?): Boolean {
        if (super.handleOnDanmakuConfigChanged(config, tag, values)) {
            // handled by the base task
        } else if (tag != null && tag.isVisibilityRelatedTag()) {
            val trigger = values.firstOrNull()
            if (trigger == null || trigger as? Boolean != false) {
                mCacheManager?.requestBuild(0L)
            }
            requestClear()
        } else if (DanmakuConfigTag.TRANSPARENCY == tag || DanmakuConfigTag.SCALE_TEXTSIZE == tag) {
            if (DanmakuConfigTag.SCALE_TEXTSIZE == tag) {
                mDisp.resetSlopPixel(mContext.scaleTextSize)
            }
            mCacheManager?.requestClearAll()
            mCacheManager?.requestBuild(-mContext.mDanmakuFactory.MAX_DANMAKU_DURATION)
        } else {
            mCacheManager?.requestClearUnused()
            mCacheManager?.requestBuild(0L)
        }

        val manager = mCacheManager
        val listener = mTaskListener
        if (listener != null && manager != null) {
            manager.post { listener.onDanmakuConfigChanged() }
        }
        return true
    }

    companion object {
        private const val MAX_CACHE_SCREEN_SIZE = 3

        /** Object-pool ceiling for reusable `DrawingCache` shells. */
        private const val POOL_LIMIT = 128

        /** Bytes of headroom before the pool counts as full. */
        private const val POOL_HEADROOM_BYTES = 5120

        private const val PREALLOC_PER_ENTRY_BYTES = 100 * 100 * 4
        private const val PREALLOC_MAX = 64

        /** Wall-clock a single build pass may spend, in ms. */
        private const val BUILD_BUDGET_MS = 8L

        /** Minimum gap between two build passes. */
        private const val BUILD_RESUME_DELAY_MS = 6L

        private const val MAX_BUILD_PACE_MS = 100L

        /** Cache-ahead ms divided by this is added to the pacing delay. */
        private const val PACING_AHEAD_DIVISOR = 40L

        /** Above this per-frame render cost, cache building backs off. */
        private const val SLOW_FRAME_MS = 12L

        private const val TIMEOUT_SWEEP_DELAY_MS = 1000L

        private const val BUFFER_SCAN_LIMIT = 32
        private const val BUFFER_SLOP_MULTIPLIER = 2
        private const val REUSE_INDEX_MAX = 4096

        /** A frame lost this many danmakus: the screen just got dense. */
        private const val DENSITY_DROP_THRESHOLD = -20

        private const val THREAD_JOIN_TIMEOUT_MS = 500L

        // CacheHandler message IDs
        const val CACHE_PREPARE = 0x1
        const val CACHE_ADD_DANMAKKU = 0x2
        const val CACHE_BUILD_CACHES = 0x3
        const val CACHE_CLEAR_TIMEOUT_CACHES = 0x4
        const val CACHE_SEEK = 0x5
        const val CACHE_QUIT = 0x6
        const val CACHE_CLEAR_ALL_CACHES = 0x7
        const val CACHE_CLEAR_OUTSIDE_CACHES = 0x8
        const val CACHE_CLEAR_OUTSIDE_CACHES_AND_RESET = 0x9
        const val CACHE_DISPATCH_ACTIONS = 0x10
        const val CACHE_REBUILD_CACHE = 0x11
        const val CACHE_BIND_CACHE = 0x12

        // CacheManager result codes
        const val RESULT_SUCCESS: Byte = 0
        const val RESULT_FAILED: Byte = 1
        const val RESULT_FAILED_OVERSIZE: Byte = 2
    }
}
