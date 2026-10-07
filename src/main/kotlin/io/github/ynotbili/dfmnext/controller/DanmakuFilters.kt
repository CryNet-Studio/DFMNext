package io.github.ynotbili.dfmnext.controller

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.DanmakuTimer
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.util.SystemClock
import java.util.LinkedHashMap
import java.util.TreeMap

class DanmakuFilters {

    interface IDanmakuFilter<T> {
        fun filter(
            danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
            timer: DanmakuTimer?, fromCachingTask: Boolean, config: DanmakuContext
        ): Boolean

        fun setData(data: T?)
        fun reset()
        fun clear()
    }

    abstract class BaseDanmakuFilter<T> : IDanmakuFilter<T> {
        override fun clear() {}
    }

    /**
     * Hides whole danmaku types (top/bottom/scrolling/special toggles).
     *
     * The previous version kept the hidden types in a
     * `Collections.synchronizedList(ArrayList<Int>)` and called `contains` for
     * every danmaku, which means a lock acquisition plus `Integer` boxing and an
     * equality scan per item, per frame. Danmaku types are a small dense range
     * (1..7), so a bitmask answers the same question in one AND.
     */
    class TypeDanmakuFilter : BaseDanmakuFilter<List<Int>>() {

        @Volatile
        private var hiddenTypesMask: Int = 0

        /** Kept for diagnostics/back-compat; the filter itself only reads the mask. */
        private val types: MutableList<Int> = ArrayList()

        fun enableType(type: Int) {
            synchronized(this) {
                if (!types.contains(type)) types.add(type)
                hiddenTypesMask = hiddenTypesMask or bitOf(type)
            }
        }

        fun disableType(type: Int) {
            synchronized(this) {
                types.remove(type)
                hiddenTypesMask = hiddenTypesMask and bitOf(type).inv()
            }
        }

        override fun filter(
            danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
            timer: DanmakuTimer?, fromCachingTask: Boolean, config: DanmakuContext
        ): Boolean {
            val filtered = isHidden(danmaku.getType())
            if (filtered) danmaku.mFilterParam = danmaku.mFilterParam or FILTER_TYPE_TYPE
            return filtered
        }

        /** Reads the volatile mask without locking: the hot path. */
        private fun isHidden(type: Int): Boolean = (hiddenTypesMask and bitOf(type)) != 0

        private fun bitOf(type: Int): Int = if (type in 1..31) 1 shl type else 0

        override fun setData(data: List<Int>?) {
            reset()
            if (data == null) return
            var mask = 0
            for (type in data) mask = mask or bitOf(type)
            synchronized(this) {
                types.clear()
                types.addAll(data)
                hiddenTypesMask = mask
            }
        }

        override fun reset() {
            synchronized(this) {
                types.clear()
                hiddenTypesMask = 0
            }
        }
    }

    class DuplicateMergingFilter : BaseDanmakuFilter<Void?>() {

        private val blockedDanmakus = LinkedHashSet<BaseDanmaku>()
        private val passedDanmakus = LinkedHashSet<BaseDanmaku>()
        private val currentDanmakus = LinkedHashMap<CharSequence, BaseDanmaku>()

        private fun removeTimeoutDanmakus(danmakus: LinkedHashSet<BaseDanmaku>, limitTime: Long, forceFullClean: Boolean) {
            val it = danmakus.iterator()
            val startTime = SystemClock.uptimeMillis()
            while (it.hasNext()) {
                try {
                    val item = it.next()
                    if (item.isTimeOut()) it.remove()
                    else if (!forceFullClean) break
                } catch (_: Exception) {
                    break
                }
                if (!forceFullClean && SystemClock.uptimeMillis() - startTime > limitTime) break
            }
        }

        private fun removeTimeoutDanmakus(danmakus: LinkedHashMap<CharSequence, BaseDanmaku>, limitTime: Int, forceFullClean: Boolean) {
            val it = danmakus.entries.iterator()
            val startTime = SystemClock.uptimeMillis()
            while (it.hasNext()) {
                try {
                    val entry = it.next()
                    if (entry.value.isTimeOut()) it.remove()
                    else if (!forceFullClean) break
                } catch (_: Exception) {
                    break
                }
                if (!forceFullClean && SystemClock.uptimeMillis() - startTime > limitTime) break
            }
        }

        @Synchronized
        fun needFilter(
            danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
            timer: DanmakuTimer?, fromCachingTask: Boolean
        ): Boolean {
            // The expensive sweep-based cleanup only runs while building caches;
            // on the render path containment checks are O(1).
            if (fromCachingTask) {
                val forceFullClean =
                    blockedDanmakus.size > 500 || passedDanmakus.size > 500 || currentDanmakus.size > 500
                removeTimeoutDanmakus(blockedDanmakus, 10, forceFullClean)
                removeTimeoutDanmakus(passedDanmakus, 10, forceFullClean)
                removeTimeoutDanmakus(currentDanmakus, 10, forceFullClean)
            }

            if (blockedDanmakus.contains(danmaku) && !danmaku.isOutside()) return true
            if (passedDanmakus.contains(danmaku)) return false

            // The parsed text object doubles as the key, so no toString() copy.
            val key = danmaku.text ?: return false
            val original = currentDanmakus[key]
            if (original != null && !original.isTimeOut()) {
                original.mMergeCount++
                original.text = original.mOriginalText.toString() + " (x" + (original.mMergeCount + 1) + ")"
                val scale = 1.0f + (original.mMergeCount * 0.1f).coerceAtMost(0.5f)
                original.textSize = original.mOriginalTextSize * scale
                original.measureResetFlag++
                original.requestFlags =
                    original.requestFlags or BaseDanmaku.FLAG_REQUEST_REMEASURE or BaseDanmaku.FLAG_REQUEST_INVALIDATE
                original.cache?.let {
                    it.destroy()
                    original.cache = null
                }
                blockedDanmakus.remove(danmaku)
                blockedDanmakus.add(danmaku)
                return true
            }

            danmaku.mOriginalText = danmaku.text
            danmaku.mOriginalTextSize = danmaku.textSize
            currentDanmakus[key] = danmaku
            passedDanmakus.add(danmaku)
            return false
        }

        override fun filter(
            danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
            timer: DanmakuTimer?, fromCachingTask: Boolean, config: DanmakuContext
        ): Boolean {
            val filtered = needFilter(danmaku, index, totalsizeInScreen, timer, fromCachingTask)
            if (filtered) danmaku.mFilterParam = danmaku.mFilterParam or FILTER_TYPE_DUPLICATE_MERGE
            return filtered
        }

        override fun setData(data: Void?) {}

        @Synchronized
        override fun reset() {
            passedDanmakus.clear()
            blockedDanmakus.clear()
            currentDanmakus.clear()
        }

        override fun clear() = reset()
    }

    /**
     * Caps how many danmakus of a type may occupy the screen. Indexed by
     * danmaku type instead of hashing a `Map<Int, Int>` per item.
     */
    class MaximumLinesFilter : BaseDanmakuFilter<Map<Int, Int>>() {

        private var limitsByType: IntArray? = null
        private var configuredMask: Int = 0

        override fun filter(
            danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
            timer: DanmakuTimer?, fromCachingTask: Boolean, config: DanmakuContext
        ): Boolean {
            val limits = limitsByType ?: return false
            val type = danmaku.getType()
            if (type < 1 || type >= limits.size || (configuredMask and (1 shl type)) == 0) return false
            val filtered = index >= limits[type]
            if (filtered) danmaku.mFilterParam = danmaku.mFilterParam or FILTER_TYPE_MAXIMUM_LINES
            return filtered
        }

        override fun setData(data: Map<Int, Int>?) {
            if (data == null) {
                reset()
                return
            }
            var maxType = 0
            for (type in data.keys) if (type > maxType) maxType = type
            val limits = IntArray(maxType + 1)
            var mask = 0
            for ((type, limit) in data) {
                if (type < 1) continue
                limits[type] = limit
                mask = mask or (1 shl type)
            }
            limitsByType = limits
            configuredMask = mask
        }

        override fun reset() {
            limitsByType = null
            configuredMask = 0
        }
    }

    /** Skips cache-building for types where anti-overlap is enabled. */
    class OverlappingFilter : BaseDanmakuFilter<Map<Int, Boolean>>() {

        private var enabledMask: Int = 0

        override fun filter(
            danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
            timer: DanmakuTimer?, fromCachingTask: Boolean, config: DanmakuContext
        ): Boolean {
            val type = danmaku.getType()
            val filtered = fromCachingTask && type in 1..31 && (enabledMask and (1 shl type)) != 0
            if (filtered) danmaku.mFilterParam = danmaku.mFilterParam or FILTER_TYPE_OVERLAPPING
            return filtered
        }

        override fun setData(data: Map<Int, Boolean>?) {
            if (data == null) {
                reset()
                return
            }
            var mask = 0
            for ((type, enabled) in data) {
                if (enabled && type in 1..31) mask = mask or (1 shl type)
            }
            enabledMask = mask
        }

        override fun reset() {
            enabledMask = 0
        }
    }

    /**
     * Snapshot of the filter chain. Registration is rare (config changes) while
     * `filter()` runs per danmaku per frame, so the array is published as an
     * immutable volatile reference instead of being rebuilt from a
     * synchronized TreeMap on every change.
     */
    @Volatile
    private var filterArray: Array<IDanmakuFilter<*>> = EMPTY_FILTERS

    @Volatile
    private var filterArraySecondary: Array<IDanmakuFilter<*>> = EMPTY_FILTERS

    private val filters: MutableMap<String, IDanmakuFilter<*>> = TreeMap()
    private val filtersSecondary: MutableMap<String, IDanmakuFilter<*>> = TreeMap()

    fun filter(
        danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
        timer: DanmakuTimer?, fromCachingTask: Boolean, context: DanmakuContext
    ) {
        val chain = filterArray
        val resetFlag = context.mGlobalFlagValues.FILTER_RESET_FLAG
        for (f in chain) {
            if (f.filter(danmaku, index, totalsizeInScreen, timer, fromCachingTask, context)) {
                danmaku.filterResetFlag = resetFlag
                break
            }
        }
        // Unfiltered items must still be marked as processed for this frame.
        danmaku.filterResetFlag = resetFlag
    }

    fun filterSecondary(
        danmaku: BaseDanmaku, index: Int, totalsizeInScreen: Int,
        timer: DanmakuTimer?, willHit: Boolean, context: DanmakuContext
    ): Boolean {
        val chain = filterArraySecondary
        val resetFlag = context.mGlobalFlagValues.FILTER_RESET_FLAG
        for (f in chain) {
            if (f.filter(danmaku, index, totalsizeInScreen, timer, willHit, context)) {
                danmaku.filterResetFlag = resetFlag
                return true
            }
        }
        return false
    }

    operator fun get(tag: String): IDanmakuFilter<*>? = get(tag, true)

    operator fun get(tag: String, primary: Boolean): IDanmakuFilter<*> {
        val registry = if (primary) filters else filtersSecondary
        val existing = synchronized(registry) { registry[tag] }
        return existing ?: registerFilter(tag, primary)!!
    }

    fun registerFilter(tag: String): IDanmakuFilter<*>? = registerFilter(tag, true)

    fun registerFilter(tag: String, primary: Boolean): IDanmakuFilter<*>? {
        val filter = createFilter(tag) ?: return null
        filter.setData(null)
        val registry = if (primary) filters else filtersSecondary
        synchronized(registry) {
            val existing = registry[tag]
            if (existing != null) {
                publish(registry, primary)
                return existing
            }
            registry[tag] = filter
            publish(registry, primary)
        }
        return filter
    }

    private fun createFilter(tag: String): IDanmakuFilter<*>? = when (tag) {
        TAG_TYPE_DANMAKU_FILTER -> TypeDanmakuFilter()
        TAG_DUPLICATE_FILTER -> DuplicateMergingFilter()
        TAG_MAXIMUN_LINES_FILTER -> MaximumLinesFilter()
        TAG_OVERLAPPING_FILTER -> OverlappingFilter()
        else -> null
    }

    private fun publish(registry: MutableMap<String, IDanmakuFilter<*>>, primary: Boolean) {
        // TreeMap iteration is descending by key here; mirror the previous
        // registration order (type -> duplicate -> lines -> overlapping).
        val ordered = REGISTRATION_ORDER.mapNotNull { registry[it] }
        val array = ordered.toTypedArray()
        if (primary) filterArray = array else filterArraySecondary = array
    }

    fun unregisterFilter(tag: String) = unregisterFilter(tag, true)

    fun unregisterFilter(tag: String, primary: Boolean) {
        val registry = if (primary) filters else filtersSecondary
        synchronized(registry) {
            val removed = registry.remove(tag) ?: return
            removed.clear()
            publish(registry, primary)
        }
    }

    fun clear() {
        filterArray.forEach { it.clear() }
        filterArraySecondary.forEach { it.clear() }
    }

    fun reset() {
        filterArray.forEach { it.reset() }
        filterArraySecondary.forEach { it.reset() }
    }

    fun release() {
        clear()
        synchronized(filters) { filters.clear() }
        synchronized(filtersSecondary) { filtersSecondary.clear() }
        filterArray = EMPTY_FILTERS
        filterArraySecondary = EMPTY_FILTERS
    }

    companion object {
        const val FILTER_TYPE_TYPE = 1
        const val FILTER_TYPE_DUPLICATE_MERGE = 128
        const val FILTER_TYPE_MAXIMUM_LINES = 256
        const val FILTER_TYPE_OVERLAPPING = 512

        const val TAG_TYPE_DANMAKU_FILTER = "1010_Filter"
        const val TAG_DUPLICATE_FILTER = "1017_Filter"
        const val TAG_MAXIMUN_LINES_FILTER = "1018_Filter"
        const val TAG_OVERLAPPING_FILTER = "1019_Filter"

        private val EMPTY_FILTERS = emptyArray<IDanmakuFilter<*>>()

        /** Evaluation order preserved from the previous key-sorted chain. */
        private val REGISTRATION_ORDER = arrayOf(
            TAG_TYPE_DANMAKU_FILTER,
            TAG_DUPLICATE_FILTER,
            TAG_MAXIMUN_LINES_FILTER,
            TAG_OVERLAPPING_FILTER,
        )
    }
}
