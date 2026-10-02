package com.tempobox.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class QueueReorderTest {

    /**
     * Executes a plan against a plain list the way PlayerConnection.applyOrder
     * executes it against the timeline, counting timeline operations.
     */
    private fun simulate(current: List<Long>, plan: QueueReorder.Plan): Pair<List<Long>, Int> {
        val list = current.toMutableList()
        var ops = 0
        plan.anchorToFront?.let { (from, to) ->
            list.add(to, list.removeAt(from)); ops++
        }
        val keepFrom = if (plan.anchored) 1 else 0
        if (list.size > keepFrom) {
            while (list.size > keepFrom) list.removeAt(list.size - 1)
            ops++
        }
        if (plan.tail.isNotEmpty()) {
            list.addAll(plan.tail); ops++
        }
        plan.anchorToTarget?.let { (from, to) ->
            list.add(to, list.removeAt(from)); ops++
        }
        return list to ops
    }

    private fun reorder(current: List<Long>, target: List<Long>, anchor: Long?): Pair<List<Long>, Int> {
        val plan = QueueReorder.plan(current, target, anchor) ?: return current to 0
        return simulate(current, plan)
    }

    // ------------------------------------------------------------------ plan

    @Test
    fun `shuffle-on order with anchor first is reached`() {
        val current = listOf(1L, 2L, 3L, 4L, 5L)
        val target = listOf(3L, 5L, 1L, 4L, 2L) // playing track 3 moved to front
        val (result, ops) = reorder(current, target, anchor = 3L)
        assertThat(result).isEqualTo(target)
        assertThat(ops).isAtMost(QueueReorder.MAX_OPS)
    }

    @Test
    fun `shuffle-off restores anchor to a middle position`() {
        val current = listOf(3L, 5L, 1L, 4L, 2L)
        val target = listOf(1L, 2L, 3L, 4L, 5L)
        val (result, ops) = reorder(current, target, anchor = 3L)
        assertThat(result).isEqualTo(target)
        assertThat(ops).isAtMost(QueueReorder.MAX_OPS)
    }

    @Test
    fun `anchor never leaves the list during any step`() {
        val current = listOf(3L, 5L, 1L, 4L, 2L)
        val plan = QueueReorder.plan(current, listOf(1L, 2L, 3L, 4L, 5L), anchorUid = 3L)!!
        // Step-by-step: after the removal step only the anchor remains.
        assertThat(plan.anchored).isTrue()
        assertThat(plan.tail).doesNotContain(3L)
    }

    @Test
    fun `no-op when already in target order returns null`() {
        assertThat(QueueReorder.plan(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L), anchorUid = 2L)).isNull()
    }

    @Test
    fun `empty queue returns null`() {
        assertThat(QueueReorder.plan(emptyList(), listOf(1L), anchorUid = null)).isNull()
    }

    @Test
    fun `target uids unknown to the queue are ignored`() {
        val (result, _) = reorder(listOf(1L, 2L, 3L), listOf(99L, 3L, 1L, 2L, 42L), anchor = 1L)
        assertThat(result).isEqualTo(listOf(3L, 1L, 2L))
    }

    @Test
    fun `queue uids missing from target are appended in current relative order`() {
        // Old move-based reorder could never drop items; neither may the plan.
        val (result, _) = reorder(listOf(1L, 2L, 3L, 4L, 5L), listOf(4L, 2L), anchor = 4L)
        assertThat(result).isEqualTo(listOf(4L, 2L, 1L, 3L, 5L))
    }

    @Test
    fun `null anchor rebuilds wholesale`() {
        val (result, ops) = reorder(listOf(1L, 2L, 3L), listOf(3L, 1L, 2L), anchor = null)
        assertThat(result).isEqualTo(listOf(3L, 1L, 2L))
        assertThat(ops).isAtMost(QueueReorder.MAX_OPS)
    }

    @Test
    fun `anchor not present in queue rebuilds wholesale`() {
        val (result, _) = reorder(listOf(1L, 2L, 3L), listOf(2L, 3L, 1L), anchor = 77L)
        assertThat(result).isEqualTo(listOf(2L, 3L, 1L))
    }

    @Test
    fun `single item queue needs no moves`() {
        assertThat(QueueReorder.plan(listOf(7L), listOf(7L), anchorUid = 7L)).isNull()
    }

    @Test
    fun `random permutations always land exactly on target within op budget`() {
        val random = Random(42)
        repeat(200) {
            val n = random.nextInt(1, 60)
            val current = (1L..n).toMutableList().apply { shuffle(random) }.toList()
            val target = current.shuffled(random)
            val anchor = current[random.nextInt(n)]
            val (result, ops) = reorder(current, target, anchor)
            assertThat(result).isEqualTo(target)
            assertThat(ops).isAtMost(QueueReorder.MAX_OPS)
        }
    }

    // ------------------------------------------------------------------ descendingRanges

    @Test
    fun `contiguous indices collapse to one range`() {
        assertThat(QueueReorder.descendingRanges(listOf(2, 3, 4))).containsExactly(2..4)
    }

    @Test
    fun `gaps split ranges and order is back-to-front`() {
        assertThat(QueueReorder.descendingRanges(listOf(0, 1, 5, 7, 8)))
            .containsExactly(7..8, 5..5, 0..1)
            .inOrder()
    }

    @Test
    fun `duplicates and unsorted input are tolerated`() {
        assertThat(QueueReorder.descendingRanges(listOf(4, 2, 3, 3, 4))).containsExactly(2..4)
    }

    @Test
    fun `empty input yields no ranges`() {
        assertThat(QueueReorder.descendingRanges(emptyList())).isEmpty()
    }

    @Test
    fun `removing ranges back-to-front never shifts pending indices`() {
        val list = ('a'..'j').toMutableList() // 10 items
        val toRemove = listOf(1, 2, 5, 8, 9)
        QueueReorder.descendingRanges(toRemove).forEach { range ->
            repeat(range.last - range.first + 1) { list.removeAt(range.first) }
        }
        assertThat(list).containsExactly('a', 'd', 'e', 'g', 'h').inOrder()
    }
}
