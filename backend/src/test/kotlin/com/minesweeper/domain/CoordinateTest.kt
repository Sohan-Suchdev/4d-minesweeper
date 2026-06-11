package com.minesweeper.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Phase 1 acceptance tests for the N-dimensional neighbour algorithm.
 *
 * Headline guarantees verified here:
 *  - A 4D centre cell has exactly 80 neighbours (3^4 − 1).
 *  - Without wrapping, a 4D corner has 15 (2^4 − 1) and a 4D face cell has 53 (2 · 3^3 − 1).
 *  - With wrapping, every cell — corners included — has the full Moore count (here 80).
 *  - A coordinate never appears in its own neighbour set.
 *  - Bound/dimension mismatches fail fast.
 */
class CoordinateTest {

    @Nested
    @DisplayName("Moore-neighbourhood cardinality (3^N − 1)")
    inner class Cardinality {

        @Test
        @DisplayName("1D centre cell has 2 neighbours")
        fun oneD() {
            val centre = Coordinate(intArrayOf(5))
            val neighbours = centre.neighbours(intArrayOf(11), wrap = false)
            assertEquals(2, neighbours.size)
            assertEquals(
                setOf(Coordinate(intArrayOf(4)), Coordinate(intArrayOf(6))),
                neighbours,
            )
        }

        @Test
        @DisplayName("2D centre cell has 8 neighbours (classic Minesweeper)")
        fun twoD() {
            val centre = Coordinate(intArrayOf(2, 2))
            val neighbours = centre.neighbours(intArrayOf(5, 5), wrap = false)
            assertEquals(8, neighbours.size)
        }

        @Test
        @DisplayName("3D centre cell has 26 neighbours")
        fun threeD() {
            val centre = Coordinate(intArrayOf(2, 2, 2))
            val neighbours = centre.neighbours(intArrayOf(5, 5, 5), wrap = false)
            assertEquals(26, neighbours.size)
        }

        @Test
        @DisplayName("4D centre cell has exactly 80 neighbours — the headline invariant")
        fun fourD() {
            val centre = Coordinate(intArrayOf(2, 2, 2, 2))
            val neighbours = centre.neighbours(intArrayOf(5, 5, 5, 5), wrap = false)
            assertEquals(80, neighbours.size)
        }

        @Test
        @DisplayName("offset generator emits 3^N − 1 vectors with no zero vector")
        fun offsetGenerator() {
            for (n in 1..4) {
                val offsets = Coordinate.mooreOffsets(n)
                assertEquals(Math.pow(3.0, n.toDouble()).toInt() - 1, offsets.size)
                assertTrue(offsets.none { off -> off.all { it == 0 } })
            }
        }
    }

    @Nested
    @DisplayName("Edge behaviour without wrapping")
    inner class HardEdges {

        @Test
        @DisplayName("4D corner cell has 2^4 − 1 = 15 neighbours")
        fun corner() {
            val corner = Coordinate(intArrayOf(0, 0, 0, 0))
            val neighbours = corner.neighbours(intArrayOf(5, 5, 5, 5), wrap = false)
            assertEquals(15, neighbours.size)
            // Every neighbour must live inside the legal half-space spanned from a corner.
            assertTrue(neighbours.all { it.coords.all { c -> c in 0..1 } })
        }

        @Test
        @DisplayName("4D face cell (one axis at the boundary) has 2 · 3^3 − 1 = 53 neighbours")
        fun face() {
            val face = Coordinate(intArrayOf(0, 2, 2, 2))
            val neighbours = face.neighbours(intArrayOf(5, 5, 5, 5), wrap = false)
            assertEquals(53, neighbours.size)
            // The boundary axis must only contribute coordinates 0 or 1.
            assertTrue(neighbours.all { it.coords[0] in 0..1 })
        }
    }

    @Nested
    @DisplayName("Spherical / wrapping behaviour")
    inner class Wrapping {

        @Test
        @DisplayName("4D corner with wrap recovers the full 80-neighbour count")
        fun cornerWraps() {
            val corner = Coordinate(intArrayOf(0, 0, 0, 0))
            val neighbours = corner.neighbours(intArrayOf(5, 5, 5, 5), wrap = true)
            assertEquals(80, neighbours.size)
        }

        @Test
        @DisplayName("the (-1,-1,-1,-1) offset from origin wraps to the opposite hyper-corner")
        fun oppositeCorner() {
            val corner = Coordinate(intArrayOf(0, 0, 0, 0))
            val neighbours = corner.neighbours(intArrayOf(5, 5, 5, 5), wrap = true)
            assertTrue(Coordinate(intArrayOf(4, 4, 4, 4)) in neighbours)
        }

        @Test
        @DisplayName("wrapping is asymmetric per axis: bounds {3, 5} maps x = -1 to 2, y = -1 to 4")
        fun perAxisWrap() {
            val origin = Coordinate(intArrayOf(0, 0))
            val neighbours = origin.neighbours(intArrayOf(3, 5), wrap = true)
            assertTrue(Coordinate(intArrayOf(2, 4)) in neighbours)
            assertTrue(Coordinate(intArrayOf(2, 0)) in neighbours)
            assertTrue(Coordinate(intArrayOf(0, 4)) in neighbours)
            assertEquals(8, neighbours.size)
        }

        @Test
        @DisplayName("degenerate axis (size 2) under wrap collapses duplicates, never includes self")
        fun degenerateAxis() {
            // A 2x2 grid with wrap: every offset of ±1 along an axis of size 2 lands on the other
            // cell. The neighbour set must dedupe and must not contain the origin itself.
            val origin = Coordinate(intArrayOf(0, 0))
            val neighbours = origin.neighbours(intArrayOf(2, 2), wrap = true)
            assertFalse(origin in neighbours)
            assertEquals(3, neighbours.size) // {(0,1), (1,0), (1,1)}
        }
    }

    @Nested
    @DisplayName("Invariants and contracts")
    inner class Contracts {

        @Test
        @DisplayName("a coordinate is never its own neighbour")
        fun noSelf() {
            val centre = Coordinate(intArrayOf(2, 2, 2, 2))
            assertFalse(centre in centre.neighbours(intArrayOf(5, 5, 5, 5), wrap = false))
            assertFalse(centre in centre.neighbours(intArrayOf(5, 5, 5, 5), wrap = true))
        }

        @Test
        @DisplayName("bounds with mismatched dimensionality throws")
        fun dimensionMismatch() {
            val cell = Coordinate(intArrayOf(0, 0, 0, 0))
            assertThrows(IllegalArgumentException::class.java) {
                cell.neighbours(intArrayOf(5, 5, 5), wrap = false)
            }
        }

        @Test
        @DisplayName("non-positive bound throws")
        fun nonPositiveBound() {
            val cell = Coordinate(intArrayOf(0, 0))
            assertThrows(IllegalArgumentException::class.java) {
                cell.neighbours(intArrayOf(5, 0), wrap = true)
            }
        }

        @Test
        @DisplayName("equality and hashing use IntArray content, not reference")
        fun valueEquality() {
            val a = Coordinate(intArrayOf(1, 2, 3, 4))
            val b = Coordinate(intArrayOf(1, 2, 3, 4))
            val c = Coordinate(intArrayOf(1, 2, 3, 5))
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
            assertNotEquals(a, c)
            // Set semantics — required because neighbours() returns a Set.
            assertEquals(1, setOf(a, b).size)
        }
    }
}
