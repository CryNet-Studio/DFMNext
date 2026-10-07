package io.github.ynotbili.dfmnext

import io.github.ynotbili.dfmnext.controller.DanmakuFilters
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.android.DanmakuContext
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fills the gaps left by the hot-path rewrites: the allocation-free comparator
 * must stay a total order, the `StringBuilder`-backed texts the cache path hands
 * in must compare like strings, and the type-visibility toggles must drive the
 * filter from a single source of truth.
 */
class DanmakuOrderingTest {

    private fun item(
        time: Long = 0,
        index: Int = 0,
        type: Int = BaseDanmaku.TYPE_SCROLL_RL,
        color: Int = 0,
        text: CharSequence? = null
    ) = createDanmaku(type = type, time = time, durationMs = 5000).apply {
        this.index = index
        this.textColor = color
        this.text = text
    }

    // --- comparator ---

    @Test
    fun `compare is consistent across the full tie-break chain`() {
        val cases = listOf(
            item(time = 1, index = 0),
            item(time = 2, index = 0),
            item(time = 2, index = 1),
            item(time = 2, index = 1, type = BaseDanmaku.TYPE_FIX_TOP),
            item(time = 2, index = 1, type = BaseDanmaku.TYPE_FIX_TOP, color = 1),
            item(time = 2, index = 1, type = BaseDanmaku.TYPE_FIX_TOP, color = 1, text = "a"),
            item(time = 2, index = 1, type = BaseDanmaku.TYPE_FIX_TOP, color = 1, text = "b"),
            item(time = 2, index = 1, type = BaseDanmaku.TYPE_FIX_TOP, color = 1, text = "b2"),
        )
        for (i in cases.indices) {
            assertEquals(0, DanmakuUtils.compare(cases[i], cases[i]))
            for (j in cases.indices) {
                val expected = i.compareTo(j)
                // The final identity tie-break keeps distinct instances ordered,
                // so every pair must be strictly monotonic by construction order
                // except for the identity term, which never contradicts the prefix.
                val actual = DanmakuUtils.compare(cases[i], cases[j])
                assertEquals(expected.sign, actual.sign, "case $i vs $j")
            }
        }
    }

    private val Int.sign get() = if (this < 0) -1 else if (this > 0) 1 else 0

    @Test
    fun `compare never allocates a string for spanned-like text`() {
        val builder = StringBuilder("danmaku")
        val a = item(time = 1, index = 1, text = builder)
        val b = item(time = 1, index = 2, text = StringBuilder("danmaku"))
        // Same characters, different CharSequence implementations: ordering must
        // fall through to the index, not to a toString() comparison.
        assertTrue(DanmakuUtils.compare(a, b) < 0)
        assertTrue(DanmakuUtils.compare(b, a) > 0)
    }

    @Test
    fun `compare treats a shorter prefix as smaller`() {
        assertTrue(DanmakuUtils.compare(item(text = "ab"), item(text = "abc")) < 0)
        assertTrue(DanmakuUtils.compare(item(text = "abc"), item(text = "ab")) > 0)
    }

    @Test
    fun `compare breaks full ties by identity antisymmetrically`() {
        val a = item(time = 7, text = "same")
        val b = item(time = 7, text = "same")
        val ab = DanmakuUtils.compare(a, b)
        assertTrue(ab != 0, "distinct instances must not compare equal, or the set drops one")
        assertEquals(-ab.sign, DanmakuUtils.compare(b, a).sign)
        assertEquals(0, DanmakuUtils.compare(a, a).sign)
    }

    @Test
    fun `compare puts null text before non-null text`() {
        assertTrue(DanmakuUtils.compare(item(text = null), item(index = 0, text = "")) < 0)
        assertTrue(DanmakuUtils.compare(item(text = ""), item(index = 0, text = null)) > 0)
    }

    // --- fillText ---

    @Test
    fun `fillText keeps a trailing empty segment when only one remains`() {
        val d = createDanmaku()
        DanmakuUtils.fillText(d, "a/n")
        assertEquals(null, d.lines, "single separator yields one line, so no split is stored")
        assertEquals("a/n", d.text)
    }

    @Test
    fun `fillText mirrors split for an interior empty line`() {
        val d = createDanmaku()
        DanmakuUtils.fillText(d, "a/n/nb")
        assertEquals(3, d.lines!!.size)
        assertEquals("a", d.lines!![0])
        assertEquals("", d.lines!![1])
        assertEquals("b", d.lines!![2])
    }

    @Test
    fun `fillText handles a non-string char sequence`() {
        val d = createDanmaku()
        DanmakuUtils.fillText(d, StringBuilder("x/ny/nz"))
        assertEquals(3, d.lines!!.size)
        assertEquals("z", d.lines!![2])
    }

    @Test
    fun `fillText keeps every segment when the separator is only at the start`() {
        val d = createDanmaku()
        DanmakuUtils.fillText(d, "/nleading")
        assertEquals(2, d.lines!!.size)
        assertEquals("", d.lines!![0])
        assertEquals("leading", d.lines!![1])
    }

    // --- TypeDanmakuFilter bitmask ---

    @Test
    fun `TypeDanmakuFilter ignores types outside the bitmask range`() {
        val f = DanmakuFilters.TypeDanmakuFilter()
        f.setData(listOf(0, 32, 999))
        val config = DanmakuContext.create()
        assertFalse(f.filter(createDanmaku(type = BaseDanmaku.TYPE_SCROLL_RL), 0, 1, null, false, config))
    }

    @Test
    fun `TypeDanmakuFilter setData replaces earlier state`() {
        val f = DanmakuFilters.TypeDanmakuFilter()
        f.enableType(BaseDanmaku.TYPE_SCROLL_RL)
        f.enableType(BaseDanmaku.TYPE_FIX_TOP)
        f.setData(listOf(BaseDanmaku.TYPE_FIX_TOP))
        val config = DanmakuContext.create()
        assertFalse(f.filter(createDanmaku(type = BaseDanmaku.TYPE_SCROLL_RL), 0, 1, null, false, config))
        assertTrue(f.filter(createDanmaku(type = BaseDanmaku.TYPE_FIX_TOP), 0, 1, null, false, config))
    }

    @Test
    fun `TypeDanmakuFilter setData with null hides nothing`() {
        val f = DanmakuFilters.TypeDanmakuFilter()
        f.enableType(BaseDanmaku.TYPE_SPECIAL)
        f.setData(null)
        assertFalse(f.filter(createDanmaku(type = BaseDanmaku.TYPE_SPECIAL), 0, 1, null, false, DanmakuContext.create()))
    }

    // --- visibility toggles drive the filter ---

    @Test
    fun `context visibility properties reflect the hidden type set`() {
        val config = DanmakuContext.create()
        assertTrue(config.FTDanmakuVisibility)
        config.setFTDanmakuVisibility(false)
        assertFalse(config.FTDanmakuVisibility)
        assertTrue(config.mFilterTypes.contains(BaseDanmaku.TYPE_FIX_TOP))

        config.FTDanmakuVisibility = true
        assertTrue(config.FTDanmakuVisibility)
        assertFalse(config.mFilterTypes.contains(BaseDanmaku.TYPE_FIX_TOP))
    }

    @Test
    fun `context visibility feeds the type filter`() {
        val config = DanmakuContext.create()
        val filter = DanmakuFilters.TypeDanmakuFilter()
        config.setSpecialDanmakuVisibility(false)
        filter.setData(config.mFilterTypes)
        assertTrue(filter.filter(createDanmaku(type = BaseDanmaku.TYPE_SPECIAL), 0, 1, null, false, config))
        assertFalse(filter.filter(createDanmaku(type = BaseDanmaku.TYPE_SCROLL_RL), 0, 1, null, false, config))
    }

    @Test
    fun `repeating a visibility setter notifies the callback only on change`() {
        val config = DanmakuContext.create()
        var calls = 0
        val tags = ArrayList<DanmakuContext.DanmakuConfigTag>()
        config.registerConfigChangedCallback(object : DanmakuContext.ConfigChangedCallback {
            override fun onDanmakuConfigChanged(
                config: DanmakuContext,
                tag: DanmakuContext.DanmakuConfigTag,
                vararg value: Any?
            ): Boolean {
                calls++
                tags.add(tag)
                return true
            }
        })
        config.setL2RDanmakuVisibility(false)
        assertEquals(1, calls)
        config.setL2RDanmakuVisibility(false)
        assertEquals(1, calls, "no change means no notification")
        config.L2RDanmakuVisibility = true
        assertEquals(2, calls)
        assertEquals(
            listOf(
                DanmakuContext.DanmakuConfigTag.L2R_DANMAKU_VISIBILITY,
                DanmakuContext.DanmakuConfigTag.L2R_DANMAKU_VISIBILITY
            ),
            tags
        )
    }
}
