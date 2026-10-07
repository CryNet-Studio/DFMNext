package io.github.ynotbili.dfmnext

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the container APIs the render and cache paths now depend on: the live
 * [Danmakus.sub] window, the cursor-based iterator, and the bulk removal helpers
 * that replaced one-`removeItem`-per-victim loops.
 */
class DanmakusWindowTest {

    private fun d(time: Long) = createDanmaku(type = BaseDanmaku.TYPE_SCROLL_RL, time = time, durationMs = 5000)

    private fun filled(vararg times: Long): Danmakus {
        val c = Danmakus()
        times.forEach { c.addItem(d(it)) }
        return c
    }

    @Test
    fun `window resolves bounds inclusively`() {
        val c = filled(100, 200, 300, 400, 500)
        val w = c.sub(200, 400)
        assertEquals(200, w.first()!!.time)
        assertEquals(400, w.last()!!.time)
        assertEquals(3, w.size())
        assertEquals(listOf(200L, 300L, 400L), w.iterator().asSequence().map { it.time }.toList())
    }

    @Test
    fun `window is empty when no item falls inside`() {
        val c = filled(100, 500)
        val w = c.sub(200, 400)
        assertTrue(w.isEmpty())
        assertEquals(0, w.size())
        assertNull(w.first())
        assertNull(w.last())
        assertFalse(w.iterator().hasNext())
    }

    @Test
    fun `window reflects items added after it was taken`() {
        val c = filled(100, 300)
        val w = c.sub(0, 500)
        assertEquals(2, w.size())
        c.addItem(d(200))
        assertEquals(3, w.size())
        assertEquals(listOf(100L, 200L, 300L), w.iterator().asSequence().map { it.time }.toList())
    }

    @Test
    fun `window ignores items outside its range added later`() {
        val c = filled(100, 300)
        val w = c.sub(0, 500)
        c.addItem(d(900))
        assertEquals(2, w.size())
        assertEquals(listOf(100L, 300L), w.iterator().asSequence().map { it.time }.toList())
        assertEquals(3, c.size())
    }

    @Test
    fun `window contains only resolves inside its range`() {
        val c = filled(100, 300)
        val inside = d(250).also { c.addItem(it) }
        val outside = d(900).also { c.addItem(it) }
        assertTrue(c.sub(200, 300).contains(inside))
        assertFalse(c.sub(200, 300).contains(outside))
        assertFalse(c.sub(200, 300).contains(d(150).also { c.addItem(it) }))
    }

    @Test
    fun `window delegates mutation to the parent`() {
        val c = filled(100, 300)
        val w = c.sub(0, 500)
        val item = d(200)
        assertTrue(w.addItem(item))
        assertEquals(3, c.size())
        assertTrue(w.removeItem(item))
        assertEquals(2, c.size())
    }

    @Test
    fun `subnew returns a detached copy`() {
        val c = filled(100, 200, 300)
        val copy = c.subnew(150, 350)
        assertEquals(2, copy.size())
        c.addItem(d(250))
        assertEquals(2, copy.size(), "subnew must not track later insertions")
        assertEquals(4, c.size())
    }

    @Test
    fun `setItems sorts in one pass and replaces contents`() {
        val c = filled(900, 1000)
        val items = listOf(d(300), d(100), d(200))
        c.setItems(items)
        assertEquals(3, c.size())
        assertEquals(100, c.first()!!.time)
        assertEquals(300, c.last()!!.time)
        assertEquals(listOf(100L, 200L, 300L), c.iterator().asSequence().map { it.time }.toList())
    }

    @Test
    fun `setItems with null empties the collection`() {
        val c = filled(100, 200)
        c.setItems(null)
        assertTrue(c.isEmpty())
    }

    @Test
    fun `setItems keeps time-ordered input as-is`() {
        val c = Danmakus()
        val items = (0 until 500).map { d(it * 10L) }
        c.setItems(items)
        assertEquals(500, c.size())
        assertEquals(0L, c.first()!!.time)
        assertEquals(4990L, c.last()!!.time)
    }

    @Test
    fun `removeWhere compacts in one pass and keeps order`() {
        val c = Danmakus()
        val items = (0 until 20).map { d(it * 100L) }
        c.setItems(items)
        val removed = c.removeWhere { it.time % 400L == 0L }
        assertEquals(5, removed)
        assertEquals(15, c.size())
        assertEquals(items.map { it.time }.filter { it % 400L != 0L }, c.iterator().asSequence().map { it.time }.toList())
    }

    @Test
    fun `removeWhere on empty collection is a no-op`() {
        val c = Danmakus()
        assertEquals(0, c.removeWhere { true })
    }

    @Test
    fun `removeHead drops the leading count only`() {
        val c = Danmakus()
        c.setItems((0 until 10).map { d(it * 100L) })
        assertEquals(3, c.removeHead(3))
        assertEquals(7, c.size())
        assertEquals(300L, c.first()!!.time)
        assertEquals(0, c.removeHead(0))
        assertEquals(7, c.removeHead(99), "count beyond size must clamp")
        assertTrue(c.isEmpty())
    }

    @Test
    fun `removeHeadWhile stops at the first kept item`() {
        val c = Danmakus()
        // setItems sorts, so the stored order is 100,200,300,400,500.
        c.setItems(listOf(d(100), d(200), d(500), d(300), d(400)))
        val removed = c.removeHeadWhile { it.time < 400 }
        assertEquals(3, removed)
        assertEquals(2, c.size())
        assertEquals(400L, c.first()!!.time)
    }

    @Test
    fun `removeHeadWhile with no match leaves the collection alone`() {
        val c = Danmakus()
        c.setItems(listOf(d(100), d(200)))
        assertEquals(0, c.removeHeadWhile { it.time > 1000 })
        assertEquals(2, c.size())
    }

    @Test
    fun `iterator remove inside a window keeps traversal aligned`() {
        val c = filled(100, 200, 300, 400)
        val w = c.sub(150, 450)
        val it = w.iterator()
        val seen = ArrayList<Long>()
        while (it.hasNext()) {
            val item = it.next()
            seen.add(item.time)
            if (item.time == 300L) it.remove()
        }
        assertEquals(listOf(200L, 300L, 400L), seen)
        assertEquals(3, c.size())
        assertEquals(listOf(100L, 200L, 400L), c.iterator().asSequence().map { it.time }.toList())
    }

    @Test
    fun `iterator remove without next fails`() {
        val c = filled(100)
        val it = c.iterator()
        kotlin.test.assertFailsWith<IllegalStateException> { it.remove() }
    }

    @Test
    fun `cursor stops at the window end even when the list grows`() {
        val c = filled(100, 200)
        val it = c.sub(0, 100).iterator()
        assertEquals(100L, it.next().time)
        c.addItem(d(300))
        assertFalse(it.hasNext())
    }

    @Test
    fun `list mode ignores time ordering for removals`() {
        val c = Danmakus(Danmakus.ST_BY_LIST)
        val a = d(300)
        c.addItem(d(100))
        c.addItem(a)
        c.addItem(d(200))
        assertTrue(c.removeItem(a))
        assertEquals(2, c.size())
        assertFalse(c.contains(a))
    }

    @Test
    fun `duplicate times are all stored`() {
        val c = Danmakus()
        repeat(50) { c.addItem(d(1000)) }
        assertEquals(50, c.size())
        assertEquals(50, c.sub(1000, 1000).size())
    }

    @Test
    fun `duplicate merging rejects an equal text item`() {
        val c = Danmakus(Danmakus.ST_BY_TIME, true)
        assertTrue(c.addItem(createDanmaku(time = 100, text = "same")))
        assertFalse(c.addItem(createDanmaku(time = 200, text = "same")))
        assertEquals(1, c.size())
    }

    @Test
    fun `position sorted mode falls back to identity lookup`() {
        val disp = StubDisplayer()
        val c = Danmakus(Danmakus.ST_BY_YPOS)
        val a = d(100).apply { paintWidth = 50f; paintHeight = 20f; layout(disp, 0f, 100f) }
        val b = d(200).apply { paintWidth = 50f; paintHeight = 20f; layout(disp, 0f, 300f) }
        assertTrue(c.addItem(a))
        assertTrue(c.addItem(b))
        assertEquals(100f, c.first()!!.getTop())
        // Drift the sort key so the array is no longer ordered. Removal keys off
        // object identity, so it must still find the element.
        a.layout(disp, 0f, 900f)
        assertTrue(c.removeItem(a))
        assertEquals(1, c.size())
        assertFalse(c.contains(a))
        assertTrue(c.contains(b))
    }

    @Test
    fun `descending position mode orders by top in reverse`() {
        val disp = StubDisplayer()
        val c = Danmakus(Danmakus.ST_BY_YPOS_DESC)
        val a = d(100).apply { paintWidth = 50f; paintHeight = 20f; layout(disp, 0f, 100f) }
        val b = d(200).apply { paintWidth = 50f; paintHeight = 20f; layout(disp, 0f, 300f) }
        c.addItem(a)
        c.addItem(b)
        assertSame(b, c.first())
        assertSame(a, c.last())
    }
}
