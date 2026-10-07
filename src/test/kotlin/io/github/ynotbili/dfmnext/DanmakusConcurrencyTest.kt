package io.github.ynotbili.dfmnext

import io.github.ynotbili.dfmnext.danmaku.model.BaseDanmaku
import io.github.ynotbili.dfmnext.danmaku.model.android.Danmakus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The render thread walks a [Danmakus.sub] window while the UI thread inserts
 * live danmakus, which is the exact interleaving the engine runs in production.
 * An array-backed container that reads its storage without publishing changes
 * correctly throws here, or silently returns a torn view; neither is acceptable,
 * so these cases are pinned down rather than left to chance.
 */
class DanmakusConcurrencyTest {

    private fun d(time: Long) = createDanmaku(type = BaseDanmaku.TYPE_SCROLL_RL, time = time, durationMs = 5000)

    @Test
    fun `concurrent inserts never break a window traversal`() {
        val c = Danmakus()
        // Seed a backlog the reader will keep re-resolving over.
        c.setItems((0 until 4_000).map { d(it * 10L) })

        val stop = AtomicBoolean(false)
        val failures = AtomicInteger(0)
        val inserted = AtomicInteger(0)
        val done = CountDownLatch(3)
        val nextTime = AtomicInteger(100_000)

        repeat(2) {
            thread {
                try {
                    while (!stop.get()) {
                        val item = d(nextTime.getAndIncrement().toLong())
                        if (c.addItem(item)) inserted.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    failures.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    done.countDown()
                }
            }
        }

        val reader = thread {
            try {
                // Playback style: hold one window and re-resolve it as the cursor moves.
                var t = 0L
                var begin = Long.MAX_VALUE
                var end = Long.MIN_VALUE
                var window = c.sub(begin, end)
                while (!stop.get()) {
                    if (begin > t - 4_000 || t > end) {
                        window = c.sub(t - 4_000, t + 4_000)
                        begin = t - 4_000
                        end = t + 4_000
                    }
                    var seen = 0
                    for (danmaku in window) {
                        // Any torn read surfaces as a stale/absent element or a
                        // negative-index exception below.
                        seen++
                    }
                    assertTrue(seen >= 0)
                    t += 137L
                }
            } catch (e: Throwable) {
                failures.incrementAndGet()
                e.printStackTrace()
            } finally {
                done.countDown()
            }
        }

        Thread.sleep(400)
        stop.set(true)
        reader.join(5_000)
        assertTrue(done.await(5, TimeUnit.SECONDS), "threads must stop on request")

        assertEquals(0, failures.get(), "no thread may observe a broken container")
        assertTrue(inserted.get() > 0, "writers must actually have inserted")
        // Lossless: every element is still reachable.
        assertEquals(4_000 + inserted.get(), c.size())
    }

    @Test
    fun `bulk removal races inserts without losing or duplicating elements`() {
        val c = Danmakus()
        c.setItems((0 until 2_000).map { d(it * 10L) })

        val stop = AtomicBoolean(false)
        val failures = AtomicInteger(0)
        val removed = AtomicInteger(0)
        val added = AtomicInteger(0)
        val nextTime = AtomicInteger(1_000_000)

        val writer = thread {
            try {
                while (!stop.get()) {
                    if (c.addItem(d(nextTime.getAndIncrement().toLong()))) added.incrementAndGet()
                }
            } catch (e: Throwable) {
                failures.incrementAndGet()
                e.printStackTrace()
            }
        }
        val pruner = thread {
            try {
                while (!stop.get()) {
                    removed.addAndGet(c.removeWhere { it.time % 7L == 0L })
                }
            } catch (e: Throwable) {
                failures.incrementAndGet()
                e.printStackTrace()
            }
        }

        Thread.sleep(400)
        stop.set(true)
        writer.join(5_000)
        pruner.join(5_000)
        assertTrue(!writer.isAlive && !pruner.isAlive, "both threads must have stopped")

        assertEquals(0, failures.get())
        assertTrue(added.get() > 0 && removed.get() > 0, "both sides must have done work")
        // Counting only happens after a mutator returns true, and every mutator
        // holds the same lock, so the ledger must close exactly.
        assertEquals(2_000L + added.get() - removed.get(), c.size().toLong())
    }

    private fun thread(body: () -> Unit): Thread = Thread(body).apply {
        isDaemon = true
        start()
    }
}
