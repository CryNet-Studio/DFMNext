package io.github.ynotbili.dfmnext.bench

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.IDisplayer
import java.util.TreeSet
import java.util.concurrent.CopyOnWriteArrayList

class LegacyDanmakus : IDanmakus {

    companion object {
        const val ST_BY_TIME = 0
        const val ST_BY_YPOS = 1
        const val ST_BY_YPOS_DESC = 2
        const val ST_BY_LIST = 4
    }

    @JvmField var items: MutableCollection<BaseDanmaku>? = null

    private var subItems: LegacyDanmakus? = null
    private var startItem: BaseDanmaku? = null
    private var endItem: BaseDanmaku? = null
    private var endSubItem: BaseDanmaku? = null
    private var startSubItem: BaseDanmaku? = null
    private var mSize: Int = 0
    private var mSortType: Int = ST_BY_TIME
    private var mComparator: DanmakuComparator? = null
    private var mDuplicateMergingEnabled: Boolean = false

    @Volatile private var mSnapshotDirty = true
    private var mCachedSnapshot: MutableList<BaseDanmaku> = mutableListOf()

    constructor() : this(ST_BY_TIME, false)

    constructor(sortType: Int) : this(sortType, false)

    constructor(sortType: Int, duplicateMergingEnabled: Boolean) {
        if (sortType == ST_BY_LIST) {
            items = CopyOnWriteArrayList()
        } else {
            mDuplicateMergingEnabled = duplicateMergingEnabled
            val comparator = DanmakuComparator(sortType, duplicateMergingEnabled)
            items = TreeSet(comparator)
            mComparator = comparator
        }
        mSortType = sortType
        mSize = 0
    }

    constructor(items: MutableCollection<BaseDanmaku>) {
        setItems(items)
    }

    constructor(duplicateMergingEnabled: Boolean) : this(ST_BY_TIME, duplicateMergingEnabled)

    @Synchronized
    fun setItems(newItems: MutableCollection<BaseDanmaku>?) {
        if (newItems == null) {
            items = null
            mSize = 0
            mSnapshotDirty = true
            return
        }
        val targetItems: MutableCollection<BaseDanmaku>
        if (mDuplicateMergingEnabled && mSortType != ST_BY_LIST) {
            items!!.clear()
            items!!.addAll(newItems)
            targetItems = items!!
        } else {
            targetItems = newItems
            items = targetItems
        }
        if (targetItems is List<*>) {
            mSortType = ST_BY_LIST
        }
        mSize = targetItems.size
        mSnapshotDirty = true
    }

    @Synchronized
    override fun iterator(): MutableIterator<BaseDanmaku> {
        val currentItems = items ?: return mutableListOf<BaseDanmaku>().iterator()
        // Use cached snapshot for both modes to avoid repeated ArrayList copies
        if (mSnapshotDirty || mCachedSnapshot.isEmpty()) {
            mCachedSnapshot = ArrayList(currentItems)
            mSnapshotDirty = false
        }
        val snapshotList = mCachedSnapshot
        val snapshotIter = snapshotList.iterator()
        return object : MutableIterator<BaseDanmaku> {
            private var lastItem: BaseDanmaku? = null
            override fun hasNext() = snapshotIter.hasNext()
            override fun next(): BaseDanmaku {
                val item = snapshotIter.next()
                lastItem = item
                return item
            }
            override fun remove() {
                snapshotIter.remove()
                lastItem?.let {
                    currentItems.remove(it)
                    mSize--
                }
            }
        }
    }

    @Synchronized
    override fun addItem(item: BaseDanmaku): Boolean {
        val currentItems = items ?: return false
        return try {
            if (currentItems.add(item)) {
                mSize++
                mSnapshotDirty = true
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun removeItem(item: BaseDanmaku): Boolean {
        val currentItems = items ?: return false
        if (item.isOutside()) {
            item.setVisibility(false)
        }
        return if (currentItems.remove(item)) {
            mSize--
            mSnapshotDirty = true
            true
        } else {
            false
        }
    }

    private fun subset(startTime: Long, endTime: Long): MutableCollection<BaseDanmaku>? {
        if (mSortType == ST_BY_LIST || items == null || items!!.isEmpty()) return null
        if (startSubItem == null) {
            startSubItem = createItem("start")
        }
        if (endSubItem == null) {
            endSubItem = createItem("end")
        }
        startSubItem!!.time = startTime
        endSubItem!!.time = endTime
        @Suppress("UNCHECKED_CAST")
        return ArrayList((items as TreeSet<BaseDanmaku>).subSet(startSubItem!!, true, endSubItem!!, false))
    }

    override fun subnew(startTime: Long, endTime: Long): IDanmakus {
        val sub = synchronized(this) { subset(startTime, endTime) }
        if (sub.isNullOrEmpty()) return LegacyDanmakus(ArrayList())
        return LegacyDanmakus(ArrayList(sub))
    }

    @Synchronized
    override fun sub(startTime: Long, endTime: Long): IDanmakus {
        val currentItems = items
        if (currentItems == null || currentItems.isEmpty()) return LegacyDanmakus()
        if (subItems == null) {
            subItems = if (mSortType == ST_BY_LIST) {
                LegacyDanmakus(ST_BY_LIST).also { it.setItems(items) }
            } else {
                LegacyDanmakus(mDuplicateMergingEnabled)
            }
        }
        val localSubItems = subItems!!
        if (mSortType == ST_BY_LIST) return localSubItems

        if (startItem == null) startItem = createItem("start")
        if (endItem == null) endItem = createItem("end")

        val localStart = startItem!!
        val localEnd = endItem!!

        val currentSubItems = subItems
        if (currentSubItems != null) {
            val dtime = startTime - localStart.time
            if (dtime >= 0 && endTime <= localEnd.time) return currentSubItems
        }

        localStart.time = startTime
        localEnd.time = endTime
        @Suppress("UNCHECKED_CAST")
        localSubItems.setItems((currentItems as TreeSet<BaseDanmaku>).subSet(localStart, true, localEnd, false))
        return localSubItems
    }

    private class SentinelDanmaku(text: String) : BaseDanmaku() {
        init { this.text = text }
        override fun layout(displayer: IDisplayer, x: Float, y: Float) {}
        override fun getRectAtTime(displayer: IDisplayer, currTime: Long): FloatArray? = null
        override fun getLeft(): Float = 0f
        override fun getTop(): Float = 0f
        override fun getRight(): Float = 0f
        override fun getBottom(): Float = 0f
        override fun getType(): Int = 0
    }

    private fun createItem(text: String): BaseDanmaku = SentinelDanmaku(text)

    @Synchronized
    override fun size(): Int = mSize

    @Synchronized
    override fun clear() {
        items?.let {
            it.clear()
            mSize = 0
            mSnapshotDirty = true
        }
        if (subItems != null) {
            subItems = null
            startItem = createItem("start")
            endItem = createItem("end")
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun first(): BaseDanmaku? {
        val currentItems = items ?: return null
        if (currentItems.isEmpty()) return null
        return when {
            mSortType == ST_BY_LIST -> (currentItems as List<BaseDanmaku>)[0]
            else -> (currentItems as java.util.SortedSet<BaseDanmaku>).first()
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun last(): BaseDanmaku? {
        val currentItems = items ?: return null
        if (currentItems.isEmpty()) return null
        return when {
            mSortType == ST_BY_LIST -> (currentItems as List<BaseDanmaku>)[currentItems.size - 1]
            else -> (currentItems as java.util.SortedSet<BaseDanmaku>).last()
        }
    }

    @Synchronized
    override fun contains(item: BaseDanmaku): Boolean = items?.contains(item) ?: false

    @Synchronized
    override fun isEmpty(): Boolean = items?.isEmpty() ?: true

    private fun setDuplicateMergingEnabled(enable: Boolean) {
        mComparator?.isDuplicateMergingEnabled = enable
        mDuplicateMergingEnabled = enable
    }

    override fun setSubItemsDuplicateMergingEnabled(enable: Boolean) {
        mDuplicateMergingEnabled = enable
        startItem = null
        endItem = null
        if (subItems == null) {
            subItems = LegacyDanmakus(enable)
        }
        subItems!!.setDuplicateMergingEnabled(enable)
    }

    // Comparator
    private inner class DanmakuComparator(
        private val sortMode: Int,
        duplicateMergingEnabled: Boolean
    ) : Comparator<BaseDanmaku> {
        var isDuplicateMergingEnabled: Boolean = duplicateMergingEnabled

        override fun compare(a: BaseDanmaku, b: BaseDanmaku): Int {
            if (isDuplicateMergingEnabled && LegacyDanmakuUtils.isDuplicate(a, b)) return 0
            return when (sortMode) {
                ST_BY_YPOS -> a.getTop().compareTo(b.getTop())
                ST_BY_YPOS_DESC -> b.getTop().compareTo(a.getTop())
                else -> LegacyDanmakuUtils.compare(a, b)
            }
        }
    }
}
