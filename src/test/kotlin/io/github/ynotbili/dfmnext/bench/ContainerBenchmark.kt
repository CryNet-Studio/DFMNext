package io.github.ynotbili.dfmnext.bench

import io.github.ynotbili.dfmnext.TestDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.Duration
import io.github.ynotbili.dfmnext.danmaku.model.IDanmakus
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import io.github.ynotbili.dfmnext.danmaku.util.DanmakuUtils
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Compares the pre-optimisation `TreeSet` container ([LegacyDanmakus], a copy of
 * the old source) with the current array-backed one on the operations the engine
 * actually performs: bulk load, the per-frame time window, and eviction.
 *
 * Timings are the median of 9 runs after 20 warm-ups. Each row also reports bytes
 * allocated per operation, which is the part that matters on a phone: allocation
 * pressure is what turns into GC pauses and dropped frames.
 *
 * Opt-in so the unit suite stays fast:
 * `gradle testDebugUnitTest -Ddfm.bench=true`. Output goes to stdout and to
 * `build/reports/bench/container.txt`.
 *
 * This is a JVM micro-benchmark, not a device profile. It isolates the data
 * structures, which touch no Android APIs; the renderer, cache builder and view
 * paths can only be measured on a device.
 */
class ContainerBenchmark {

    private val report = StringBuilder()

    /** Null when the JVM doesn't expose per-thread allocation counters. */
    private val allocator: (() -> Long)? = runCatching {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        { bean.currentThreadAllocatedBytes }
    }.getOrNull()

    @Test
    fun `old versus new container hot paths`() {
        assumeTrue(System.getProperty("dfm.bench") == "true", "run with -Ddfm.bench=true")

        for (size in intArrayOf(2_000, 10_000)) {
            line("size = $size danmakus")
            parserLoad(size)
            streamLoad(size)
            textHandling(size)
            windowTraversal(size)
            eviction(size)
        }

        val dir = File("build/reports/bench")
        dir.mkdirs()
        File(dir, "container.txt").writeText(report.toString())
        println(report)
    }

    /**
     * The parse path: n items in arrival order handed to one container. The old code
     * inserted into a `TreeSet` element by element; the new one bulk-sorts once.
     */
    private fun parserLoad(size: Int) {
        val items = sample(size)
        val legacy = measure("load / legacy add n=$size", size.toLong()) {
            val c = LegacyDanmakus()
            for (i in items) c.addItem(i)
            consume(c)
        }
        val current = measure("load / current setItems", size.toLong()) {
            val c = Danmakus()
            c.setItems(items)
            consume(c)
        }
        compare("load (parser path)", legacy, current)
    }

    /**
     * The live path: one item at a time, in the order the player delivers them.
     * Real streams are time-ordered with a little jitter (a comment sent while the
     * timer advanced, a re-sync), so [jitteredSample] is the case the engine
     * actually sees; the fully shuffled column is the worst case for an array-backed
     * container and is reported as such rather than hidden.
     */
    private fun streamLoad(size: Int) {
        val ordered = sample(size)
        val jittered = jitteredSample(size)
        val shuffled = ArrayList(sample(size)).shuffled(java.util.Random(11L))
        compare(
            "load (ordered stream)",
            measure("stream / legacy ordered", size.toLong()) { insertEach(LegacyDanmakus(), ordered) },
            measure("stream / current ordered", size.toLong()) { insertEach(Danmakus(), ordered) }
        )
        compare(
            "load (jittered stream)",
            measure("stream / legacy jittered", size.toLong()) { insertEach(LegacyDanmakus(), jittered) },
            measure("stream / current jittered", size.toLong()) { insertEach(Danmakus(), jittered) }
        )
        compare(
            "load (worst case: fully shuffled)",
            measure("stream / legacy shuffled", size.toLong()) { insertEach(LegacyDanmakus(), shuffled) },
            measure("stream / current shuffled", size.toLong()) { insertEach(Danmakus(), shuffled) }
        )
    }

    private fun insertEach(c: IDanmakus, items: List<BaseDanmaku>): Int {
        for (i in items) c.addItem(i)
        return consume(c)
    }

    /**
     * DrawTask's per-frame work: walk the current time window. The engine holds
     * one window object and only re-resolves it when the previous one no longer
     * covers the cursor, so [sweep] reproduces that predicate instead of calling
     * `sub()` once per frame — measuring a copy the real renderer never makes
     * would just flatter whichever container copies more.
     */
    private fun windowTraversal(size: Int) {
        val items = sample(size)
        val frames = 600L
        // DrawTask asks for [t - MAX_DANMAKU_DURATION - 100, t + MAX_DANMAKU_DURATION]
        // and keeps it until the cursor passes the far edge.
        val window = 8_100L
        val step = (items.last().time - window) / (frames - 1)

        line("window rebuilds per sweep: ${rebuilds(frames, window, step)}")
        compare(
            "window per frame",
            measure("window / legacy", frames) { sweep(LegacyDanmakus().apply { for (i in items) addItem(i) }, frames, window, step) },
            measure("window / current", frames) { sweep(Danmakus().apply { setItems(items) }, frames, window, step) }
        )
    }

    /** How often DrawTask's predicate forces a fresh window over the sweep. */
    private fun rebuilds(frames: Long, window: Long, step: Long): Int {
        var t = 0L
        var lastBegin = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        var n = 0
        repeat(frames.toInt()) {
            val beginMills = t - window / 2
            val endMills = t + window / 2
            if (lastBegin > beginMills || t > lastEnd) {
                lastBegin = beginMills
                lastEnd = endMills
                n++
            }
            t += step
        }
        return n
    }

    /** One frame's walk, over a window rebuilt exactly when DrawTask would rebuild it. */
    private fun sweep(c: IDanmakus, frames: Long, window: Long, step: Long): Int {
        var t = 0L
        var seen = 0
        var lastBegin = Long.MAX_VALUE
        var lastEnd = Long.MIN_VALUE
        var danmakus = c.sub(lastBegin, lastEnd)
        repeat(frames.toInt()) {
            val beginMills = t - window / 2
            val endMills = t + window / 2
            if (lastBegin > beginMills || t > lastEnd) {
                danmakus = c.sub(beginMills, endMills)
                lastBegin = beginMills
                lastEnd = endMills
            }
            if (!danmakus.isEmpty()) {
                val it = danmakus.iterator()
                while (it.hasNext()) { it.next(); seen++ }
            }
            t += step
        }
        check(seen > 0)
        return seen
    }

    /**
     * Cache-manager eviction under memory pressure: drop the oldest fifth of a
     * full container. Rows are `load + evict` so every variant carries its own
     * build cost and the numbers stay comparable; [BaseDanmaku] counts as one op.
     */
    private fun eviction(size: Int) {
        val items = sample(size)
        val victims = size / 5
        val limit = items[victims - 1].time

        val ops = (size + victims).toLong()
        val legacy = measure("evict / legacy load+removeItem", ops) { evict(LegacyDanmakus(), items, victims) }
        val perItem = measure("evict / current load+removeItem", ops) { evict(Danmakus(), items, victims) }
        val head = measure("evict / current load+removeHead", ops) {
            val c = Danmakus().apply { setItems(items) }
            check(c.removeHead(victims) == victims)
            c.size()
        }
        val where = measure("evict / current load+removeWhere", ops) {
            val c = Danmakus().apply { setItems(items) }
            check(c.removeWhere { it.time <= limit } == victims)
            c.size()
        }
        compare("evict (per item)", legacy, perItem)
        compare("evict (bulk pass)", legacy, minOfBy(head, where))
    }

    private fun evict(c: IDanmakus, items: List<BaseDanmaku>, victims: Int): Int {
        for (i in items) c.addItem(i)
        for (i in 0 until victims) c.removeItem(items[i])
        return c.size()
    }

    /** Force the container to be read so the JVM can't elide the whole body. */
    private fun consume(c: IDanmakus): Int = c.size() + (if (c.first() != null) 1 else 0)

    /**
     * Per-item text handling. Every parsed danmaku goes through `fillText`, so the
     * old `toString()` + regex-backed `split` cost is paid once per item at load.
     */
    private fun textHandling(size: Int) {
        val plain = "single line danmaku text"
        val multiline = "line one/nline two/nline three"
        compare(
            "text handling",
            measure("fillText / legacy", size.toLong()) {
                val d = TestDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
                var lines = 0
                repeat(size) {
                    LegacyDanmakuUtils.fillText(d, if (it and 1 == 0) plain else multiline)
                    lines += d.lines?.size ?: 0
                    d.lines = null
                }
                lines
            },
            measure("fillText / current", size.toLong()) {
                val d = TestDanmaku(BaseDanmaku.TYPE_SCROLL_RL)
                var lines = 0
                repeat(size) {
                    DanmakuUtils.fillText(d, if (it and 1 == 0) plain else multiline)
                    lines += d.lines?.size ?: 0
                    d.lines = null
                }
                lines
            }
        )
    }

    private fun sample(size: Int): List<BaseDanmaku> = buildSample(size) { it * 10L }

    /**
     * Time-ordered except that every 8th item claims a slot two positions back,
     * which is how a real delivery stream looks.
     */
    private fun jitteredSample(size: Int): List<BaseDanmaku> = buildSample(size) { i ->
        val base = i * 10L
        if (i % 8 == 7) base - 25L else base
    }

    private fun buildSample(size: Int, timeAt: (Int) -> Long): List<BaseDanmaku> {
        val out = ArrayList<BaseDanmaku>(size)
        for (i in 0 until size) {
            out.add(
                TestDanmaku(BaseDanmaku.TYPE_SCROLL_RL).apply {
                    time = timeAt(i)
                    index = i
                    duration = Duration(5000L)
                    text = "danmaku $i"
                    paintWidth = 120f
                    paintHeight = 30f
                }
            )
        }
        return out
    }

    // --- measurement ------------------------------------------------------

    private class Sample(val nanosPerOp: Long, val bytesPerOp: Long)

    private fun measure(label: String, ops: Long, body: () -> Int): Sample {
        var sink = 0
        repeat(20) { sink += body() }
        val times = LongArray(9)
        val bytes = LongArray(9)
        for (r in times.indices) {
            val b0 = allocator?.invoke() ?: 0L
            val t0 = System.nanoTime()
            sink += body()
            times[r] = System.nanoTime() - t0
            bytes[r] = if (allocator == null) 0L else (allocator.invoke() - b0) / ops
        }
        times.sort()
        bytes.sort()
        val medianNanos = times[times.size / 2] / ops
        val medianBytes = bytes[bytes.size / 2]
        report.append(
            "%-30s %9d ns/op  %8d B/op  (%d ops)%s\n".format(
                label, medianNanos, medianBytes, ops, if (sink == Int.MIN_VALUE) " !" else ""
            )
        )
        return Sample(medianNanos, medianBytes)
    }

    private fun compare(name: String, legacy: Sample, current: Sample) {
        val speed = if (current.nanosPerOp == 0L) Double.POSITIVE_INFINITY
        else legacy.nanosPerOp.toDouble() / current.nanosPerOp.toDouble()
        val alloc = if (current.bytesPerOp == 0L) "n/a"
        else "%.1fx less".format((legacy.bytesPerOp + 1).toDouble() / (current.bytesPerOp + 1).toDouble())
        report.append("  -> $name: %.2fx speed, allocation %s\n".format(speed, alloc))
    }

    private fun minOfBy(a: Sample, b: Sample): Sample = if (a.nanosPerOp <= b.nanosPerOp) a else b

    private fun line(text: String) {
        report.append("\n== $text ==\n")
    }
}
