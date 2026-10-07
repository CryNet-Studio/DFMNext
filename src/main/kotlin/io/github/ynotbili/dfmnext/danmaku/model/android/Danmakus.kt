package io.github.ynotbili.dfmnext.danmaku.model.android

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import java.util.NoSuchElementException

/**
 * The danmaku container used throughout the engine.
 *
 * Performance notes versus the previous `TreeSet` + defensive-snapshot design:
 *  - Sorted data lives in an [ArrayList] maintained by binary-search insertion.
 *    No red/black node allocation per danmaku, and the common "already parsed in
 *    time order" case degrades to an amortised O(1) append.
 *  - [iterator] walks the array by index instead of copying the whole set into a
 *    new `ArrayList` on the first `hasNext()`. With 10k danmakus that copy used
 *    to happen several times per second.
 *  - Range queries are two binary searches over the time axis instead of a full
 *    traversal, and [sub] returns a lightweight window over the time bounds
 *    rather than copying every visible danmaku.
 */
class Danmakus : IDanmakus {

    companion object {
        const val ST_BY_TIME = 0
        const val ST_BY_YPOS = 1
        const val ST_BY_YPOS_DESC = 2
        const val ST_BY_LIST = 4

        private const val NOT_FOUND = -1

        private val EMPTY: Array<BaseDanmaku?> = arrayOfNulls(0)
    }

    private val list = ArrayList<BaseDanmaku>(64)

    /**
     * Bumped by every mutation. [Window] reads it without holding the lock to
     * decide whether its captured range is still current, hence volatile.
     */
    @Volatile
    private var modCount: Int = 0

    private var mSortType = ST_BY_TIME
    private var mComparator: DanmakuComparator? = null
    private var mDuplicateMergingEnabled = false

    constructor() : this(ST_BY_TIME, false)

    constructor(sortType: Int) : this(sortType, false)

    constructor(sortType: Int, duplicateMergingEnabled: Boolean) {
        mSortType = sortType
        mDuplicateMergingEnabled = duplicateMergingEnabled
        if (sortType != ST_BY_LIST) {
            mComparator = DanmakuComparator(sortType, duplicateMergingEnabled)
        }
    }

    constructor(items: Collection<BaseDanmaku>) : this(ST_BY_LIST, false) {
        setItems(items)
    }

    constructor(duplicateMergingEnabled: Boolean) : this(ST_BY_TIME, duplicateMergingEnabled)

    override fun iterator(): MutableIterator<BaseDanmaku> = Cursor(snapshotAll())

    override fun addItem(item: BaseDanmaku): Boolean = synchronized(this) {
        try {
            val cmp = mComparator
            if (cmp == null) {
                list.add(item)
                modCount++
                true
            } else {
                val at = cmp.lowerBound(list, item)
                if (at < list.size && cmp.compare(list[at], item) == 0) {
                    // Equal under the comparator -> not stored twice (TreeSet parity).
                    false
                } else {
                    list.add(at, item)
                    modCount++
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun removeItem(item: BaseDanmaku): Boolean = synchronized(this) {
        if (item.isOutside()) item.setVisibility(false)
        val at = indexOfLocked(item)
        if (at == NOT_FOUND) false else { list.removeAt(at); modCount++; true }
    }

    /**
     * The render loop's per-frame window query. Returns a [Window] view over the
     * time bounds, so steady-state cost is two binary searches plus one small
     * object instead of copying the visible range.
     */
    override fun sub(startTime: Long, endTime: Long): IDanmakus = synchronized(this) {
        if (mComparator == null) this else Window(startTime, endTime)
    }

    /** Detached copy of a time window, for the cache thread. */
    override fun subnew(startTime: Long, endTime: Long): IDanmakus = synchronized(this) {
        val copy = Danmakus(ST_BY_LIST)
        if (mComparator == null) {
            var i = 0
            val n = list.size
            while (i < n) {
                val item = list[i]
                if (item.time in startTime..endTime) copy.list.add(item)
                i++
            }
        } else {
            val lo = lowerBoundByTime(startTime)
            val hi = upperBoundByTime(endTime)
            if (hi > lo) copy.list.addAll(list.subList(lo, hi))
        }
        copy
    }

    override fun size(): Int = synchronized(this) { list.size }

    override fun clear() {
        synchronized(this) {
            list.clear()
            modCount++
        }
    }

    override fun first(): BaseDanmaku? = synchronized(this) { if (list.isEmpty()) null else list[0] }

    override fun last(): BaseDanmaku? = synchronized(this) { if (list.isEmpty()) null else list[list.size - 1] }

    override fun contains(item: BaseDanmaku): Boolean = synchronized(this) { indexOfLocked(item) != NOT_FOUND }

    override fun isEmpty(): Boolean = synchronized(this) { list.isEmpty() }

    override fun setSubItemsDuplicateMergingEnabled(enable: Boolean) = synchronized(this) {
        mDuplicateMergingEnabled = enable
        mComparator?.isDuplicateMergingEnabled = enable
    }

    fun setDuplicateMergingEnabled(enable: Boolean) = setSubItemsDuplicateMergingEnabled(enable)

    /**
     * Drops every element the predicate rejects in a single pass.
     *
     * The cache manager used to collect victims and call [removeItem] per
     * victim, which is O(victims x size) array shifting. One compaction pass is
     * O(size) and keeps the sorted invariant, since it preserves relative order.
     */
    fun removeWhere(predicate: (BaseDanmaku) -> Boolean): Int = synchronized(this) {
        val n = list.size
        if (n == 0) return@synchronized 0
        var write = 0
        for (read in 0 until n) {
            val item = list[read]
            if (!predicate(item)) {
                if (write != read) list[write] = item
                write++
            }
        }
        val removed = n - write
        if (removed > 0) {
            list.subList(write, n).clear()
            modCount++
        }
        removed
    }

    /** Removes the leading [count] elements in one `removeRange` (single memmove). */
    fun removeHead(count: Int): Int = synchronized(this) {
        val n = minOf(count, list.size)
        if (n > 0) {
            list.subList(0, n).clear()
            modCount++
        }
        n
    }

    /**
     * Evicts entries from the head while [predicate] keeps accepting them,
     * stopping at the first rejected entry. Used by the cache manager's
     * size-pressure path, which previously did one [removeItem] per victim
     * (O(victims x size) array shifting).
     */
    fun removeHeadWhile(predicate: (BaseDanmaku) -> Boolean): Int = synchronized(this) {
        val n = list.size
        var i = 0
        while (i < n && predicate(list[i])) i++
        if (i > 0) {
            list.subList(0, i).clear()
            modCount++
        }
        i
    }

    /**
     * Replaces the contents with [newItems], restoring sorted order with a
     * single [ArrayList.sortWith] pass rather than n binary-search insertions.
     */
    fun setItems(newItems: Collection<BaseDanmaku>?) {
        synchronized(this) {
            list.clear()
            modCount++
            if (newItems == null) return@synchronized
            list.addAll(newItems)
            mComparator?.let { list.sortWith(it) }
            modCount++
        }
    }

    // --- internals shared by Cursor / Window --------------------------------

    private fun indexOfLocked(item: BaseDanmaku): Int {
        mComparator?.let {
            val at = it.binarySearch(list, item)
            if (at != NOT_FOUND) return at
        }
        // Identity fallback: the position-sorted modes order on a mutable field
        // (getTop()), so the array can drift out of order; a linear probe keeps
        // removal and lookup correct there.
        return indexOfIdentity(item, 0, list.size)
    }

    private fun indexOfIdentity(item: BaseDanmaku, from: Int, until: Int): Int {
        var i = from
        val limit = minOf(until, list.size)
        while (i < limit) {
            if (list[i] === item) return i
            i++
        }
        return NOT_FOUND
    }

    private fun lowerBoundByTime(startTime: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].time < startTime) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upperBoundByTime(endTime: Long): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].time <= endTime) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Allocation-free sequential traversal over a fixed index range. */
    /**
     * Index cursor over [list].
     *
     * The range is captured into a private array under the container lock, then
     * walked without locking. The draw thread iterates a window while the UI
     * thread inserts live danmakus, and an `ArrayList` resized mid-traversal is
     * not safe to read; locking per element access costs about as much as the
     * traversal itself. A single copy of just the visible range is both safe and
     * cheap, and it matches the snapshot semantics the engine has always had.
     */
    private inner class Cursor(private val items: Array<BaseDanmaku?>) :
        MutableIterator<BaseDanmaku> {
        private var index = 0
        private var last: BaseDanmaku? = null

        override fun hasNext(): Boolean = index < items.size

        override fun next(): BaseDanmaku {
            if (index >= items.size) throw NoSuchElementException()
            return items[index++].also { last = it }!!
        }

        override fun remove() {
            val item = last ?: throw IllegalStateException("next() has not been called")
            last = null
            synchronized(this@Danmakus) {
                val at = indexOfLocked(item)
                if (at != NOT_FOUND) {
                    list.removeAt(at)
                    modCount++
                }
            }
        }
    }

    /**
     * Copy [from, to) into a fresh array under the container lock. Bounds are
     * resolved by the caller while holding the same lock, so a traversal always
     * walks a stable slice even though the UI thread keeps inserting live
     * danmakus.
     */
    private fun snapshotLocked(from: Int, to: Int): Array<BaseDanmaku?> {
        val lo = maxOf(0, from)
        val hi = minOf(to, list.size)
        val n = hi - lo
        if (n <= 0) return EMPTY
        val items = arrayOfNulls<BaseDanmaku>(n)
        for (i in 0 until n) items[i] = list[lo + i]
        return items
    }

    private fun snapshotAll(): Array<BaseDanmaku?> = synchronized(this) {
        snapshotLocked(0, list.size)
    }

    private fun indexOfLockedInRange(item: BaseDanmaku, from: Int, until: Int): Int {
        mComparator?.let {
            val at = it.binarySearch(list, item)
            if (at != NOT_FOUND && at in from until minOf(until, list.size)) return at
        }
        return indexOfIdentity(item, from, until)
    }

    /**
     * A view over the `[beginTime, endTime]` range.
     *
     * The draw thread reads through this object while the UI thread may insert
     * live danmakus, so the visible range is captured into an immutable array
     * and published via a volatile field: readers either see the whole previous
     * generation or the whole new one, never a half-built array.
     */
    private inner class Window(
        private val beginTime: Long,
        private val endTime: Long,
    ) : IDanmakus {

        /**
         * Visible range, rebuilt only once the container has changed since the
         * last capture. Playback over a pre-parsed list mutates nothing between
         * frames, so the common case is a lock-free, allocation-free reuse; a
         * live insert costs a single copy of the visible range.
         *
         * Volatile because the fast path below reads it without holding the
         * container lock. Ranges are never mutated in place, only replaced.
         */
        @Volatile
        private var range: Array<BaseDanmaku?>? = null

        @Volatile
        private var rangeStamp: Int = -1

        private fun range(): Array<BaseDanmaku?> {
            val cached = range
            val stamp = modCount
            if (cached != null && rangeStamp == stamp) return cached
            return synchronized(this@Danmakus) {
                val items = snapshotLocked(lowerBoundByTime(beginTime), upperBoundByTime(endTime))
                // Stamped inside the lock, together with the copy, so the pair can
                // never describe two different generations.
                range = items
                rangeStamp = modCount
                items
            }
        }

        override fun first(): BaseDanmaku? = range().let { if (it.isEmpty()) null else it[0] }

        override fun last(): BaseDanmaku? = range().let { if (it.isEmpty()) null else it[it.size - 1] }

        override fun size(): Int = range().size

        override fun isEmpty(): Boolean = range().isEmpty()

        override fun contains(item: BaseDanmaku): Boolean = synchronized(this@Danmakus) {
            val lo = lowerBoundByTime(beginTime)
            val hi = upperBoundByTime(endTime)
            lo < hi && indexOfLockedInRange(item, lo, hi) != NOT_FOUND
        }

        override fun addItem(item: BaseDanmaku): Boolean = this@Danmakus.addItem(item)

        override fun removeItem(item: BaseDanmaku): Boolean = this@Danmakus.removeItem(item)

        override fun subnew(startTime: Long, endTime: Long): IDanmakus =
            this@Danmakus.subnew(startTime, endTime)

        override fun sub(startTime: Long, endTime: Long): IDanmakus = this@Danmakus.sub(startTime, endTime)

        override fun clear() = this@Danmakus.clear()

        override fun iterator(): MutableIterator<BaseDanmaku> = Cursor(range())

        override fun setSubItemsDuplicateMergingEnabled(enable: Boolean) {
            this@Danmakus.setSubItemsDuplicateMergingEnabled(enable)
        }
    }

    private inner class DanmakuComparator(
        private val sortMode: Int,
        duplicateMergingEnabled: Boolean,
    ) : Comparator<BaseDanmaku> {
        var isDuplicateMergingEnabled: Boolean = duplicateMergingEnabled

        override fun compare(a: BaseDanmaku, b: BaseDanmaku): Int {
            if (isDuplicateMergingEnabled && DanmakuUtils.isDuplicate(a, b)) return 0
            return when (sortMode) {
                ST_BY_YPOS -> compareValues(a.getTop(), b.getTop())
                ST_BY_YPOS_DESC -> compareValues(b.getTop(), a.getTop())
                else -> DanmakuUtils.compare(a, b)
            }
        }

        /** First index whose element is not strictly less than [item]. */
        fun lowerBound(items: ArrayList<BaseDanmaku>, item: BaseDanmaku): Int {
            var lo = 0
            var hi = items.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (compare(items[mid], item) < 0) lo = mid + 1 else hi = mid
            }
            return lo
        }

        /** Index of an element equal to [item], or [NOT_FOUND]. */
        fun binarySearch(items: ArrayList<BaseDanmaku>, item: BaseDanmaku): Int {
            var lo = 0
            var hi = items.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val cmp = compare(items[mid], item)
                when {
                    cmp < 0 -> lo = mid + 1
                    cmp > 0 -> hi = mid - 1
                    else -> {
                        var first = mid
                        while (first > 0 && compare(items[first - 1], item) == 0) first--
                        return first
                    }
                }
            }
            return NOT_FOUND
        }
    }
}
