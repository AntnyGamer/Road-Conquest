package com.roadconquest.app

import com.roadconquest.app.util.allAdjacent
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AdjacentChecksTest {
    @Test fun adjacencyChecksMatchPairChecksForEmptySingleDuplicateAndUnorderedInputs() {
        val random = Random(42)
        val cases = listOf(emptyList(), listOf(1), listOf(1, 1), listOf(1, 2, 3), listOf(3, 2, 1)) +
            List(1000) { List(random.nextInt(100)) { random.nextInt(-100, 100) } }
        val predicates: List<(Int, Int) -> Boolean> = listOf(
            { a, b -> a < b }, { a, b -> a != b }, { a, b -> b - a in 1..12 }
        )
        for (values in cases) for (predicate in predicates) {
            assertEquals(values.zipWithNext().all { (a, b) -> predicate(a, b) }, values.allAdjacent(predicate))
        }
    }

    @Test fun invalidFirstPairDoesNotReadTheRemainingValues() {
        var reads = 0
        val input = Iterable {
            object : Iterator<Int> {
                override fun hasNext() = reads < 100
                override fun next(): Int = if (++reads == 1) 1 else 0
            }
        }
        assertFalse(input.allAdjacent { a, b -> a < b })
        assertEquals(2, reads)
    }
}
