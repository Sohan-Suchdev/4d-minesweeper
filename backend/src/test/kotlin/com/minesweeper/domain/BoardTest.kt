package com.minesweeper.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BoardTest {

    @Nested
    @DisplayName("Safe-start mine deferral")
    inner class SafeStart {

        @Test
        @DisplayName("first click is never a mine — verified across many seeds")
        fun firstClickNeverMine() {
            val firstClick = Coordinate(intArrayOf(2, 2, 2, 2))
            repeat(50) { seed ->
                val board = Board(intArrayOf(4, 4, 4, 4), totalMines = 60, wrap = false, random = Random(seed.toLong()))
                board.reveal(firstClick)
                assertFalse(board.cellAt(firstClick).isMine, "seed=$seed put a mine at the first click")
            }
        }

        @Test
        @DisplayName("first click's neighbours contain no mines, guaranteeing a cascade")
        fun neighboursAreSafe() {
            val firstClick = Coordinate(intArrayOf(2, 2, 2, 2))
            val bounds = intArrayOf(4, 4, 4, 4)
            repeat(20) { seed ->
                val board = Board(bounds, totalMines = 60, wrap = false, random = Random(seed.toLong()))
                board.reveal(firstClick)
                for (n in firstClick.neighbours(bounds, wrap = false)) {
                    assertFalse(board.cellAt(n).isMine, "seed=$seed produced a mine at neighbour $n")
                }
                assertEquals(0, board.cellAt(firstClick).adjacentMines)
            }
        }

        @Test
        @DisplayName("safe zone forces the first reveal to expand at least to the immediate neighbourhood")
        fun cascadeStartsImmediately() {
            val firstClick = Coordinate(intArrayOf(2, 2, 2, 2))
            val bounds = intArrayOf(4, 4, 4, 4)
            val board = Board(bounds, totalMines = 30, wrap = false, random = Random(42))
            board.reveal(firstClick)
            assertTrue(board.cellAt(firstClick).isRevealed)
            for (n in firstClick.neighbours(bounds, wrap = false)) {
                assertTrue(board.cellAt(n).isRevealed, "neighbour $n should be revealed by the starting cascade")
            }
        }

        @Test
        @DisplayName("total mines placed equals the configured count")
        fun mineCountHonoured() {
            val board = Board(intArrayOf(5, 5, 5), totalMines = 20, wrap = false, random = Random(7))
            board.reveal(Coordinate(intArrayOf(2, 2, 2)))
            assertEquals(20, board.allCells().count { it.isMine })
        }

        @Test
        @DisplayName("too many mines for the available cells throws")
        fun overSubscribedMineCountThrows() {
            val board = Board(intArrayOf(3, 3), totalMines = 8, wrap = false)
            // 9 cells total; safe zone for a centre click excludes all 9, leaving zero candidates.
            assertThrows(IllegalArgumentException::class.java) {
                board.reveal(Coordinate(intArrayOf(1, 1)))
            }
        }
    }

    @Nested
    @DisplayName("N-dimensional flood-fill")
    inner class Cascade {

        @Test
        @DisplayName("1D cascade halts at a numbered border adjacent to a mine")
        fun oneDimensionalHalt() {
            val board = Board.withFixedMines(
                dimensions = intArrayOf(10),
                wrap = false,
                mines = setOf(Coordinate(intArrayOf(5))),
            )
            val outcome = board.reveal(Coordinate(intArrayOf(0)))
            assertEquals(RevealOutcome.CASCADE, outcome)
            for (i in 0..4) assertTrue(board.cellAt(Coordinate(intArrayOf(i))).isRevealed, "index $i should be revealed")
            for (i in 5..9) assertFalse(board.cellAt(Coordinate(intArrayOf(i))).isRevealed, "index $i should remain hidden")
            assertEquals(1, board.cellAt(Coordinate(intArrayOf(4))).adjacentMines)
        }

        @Test
        @DisplayName("mine-free board reveals every cell and reports a win")
        fun mineFreeFullSweep() {
            val board = Board.withFixedMines(intArrayOf(3, 3, 3, 3), wrap = false, mines = emptySet())
            val outcome = board.reveal(Coordinate(intArrayOf(0, 0, 0, 0)))
            assertEquals(RevealOutcome.WON, outcome)
            assertTrue(board.allCells().all { it.isRevealed })
        }

        @Test
        @DisplayName("cascade invariant — every revealed 0-mine cell has all its in-bounds neighbours revealed too")
        fun cascadeInvariantHolds() {
            val bounds = intArrayOf(4, 4, 4, 4)
            val board = Board(bounds, totalMines = 20, wrap = false, random = Random(11))
            board.reveal(Coordinate(intArrayOf(2, 2, 2, 2)))

            for (cell in board.allCells()) {
                if (cell.isRevealed && cell.adjacentMines == 0 && !cell.isMine) {
                    for (n in cell.coordinate.neighbours(bounds, wrap = false)) {
                        val neighbour = board.cellAt(n)
                        assertTrue(
                            neighbour.isRevealed || neighbour.isFlagged,
                            "cascade leaked: ${cell.coordinate} is a zero, but neighbour $n is still hidden",
                        )
                    }
                }
            }
        }

        @Test
        @DisplayName("cascade never reveals a mine")
        fun cascadeSkipsMines() {
            val board = Board(intArrayOf(5, 5, 5), totalMines = 8, wrap = false, random = Random(99))
            board.reveal(Coordinate(intArrayOf(2, 2, 2)))
            for (cell in board.allCells()) {
                if (cell.isMine) assertFalse(cell.isRevealed, "mine at ${cell.coordinate} was revealed by the cascade")
            }
        }

        @Test
        @DisplayName("flagged cells are skipped by the cascade")
        fun flaggedCellsSurvive() {
            val board = Board.withFixedMines(intArrayOf(10), wrap = false, mines = emptySet())
            board.toggleFlag(Coordinate(intArrayOf(5)))
            board.reveal(Coordinate(intArrayOf(0)))
            assertFalse(board.cellAt(Coordinate(intArrayOf(5))).isRevealed)
            assertTrue(board.cellAt(Coordinate(intArrayOf(5))).isFlagged)
            // The cascade is dammed at the flag: cells beyond it remain hidden.
            for (i in 6..9) assertFalse(board.cellAt(Coordinate(intArrayOf(i))).isRevealed)
        }

        @Test
        @DisplayName("wrap toggle is honoured by the cascade — a torus has no edges to halt expansion")
        fun cascadeHonoursWrap() {
            val board = Board.withFixedMines(intArrayOf(4, 4), wrap = true, mines = emptySet())
            val outcome = board.reveal(Coordinate(intArrayOf(0, 0)))
            assertEquals(RevealOutcome.WON, outcome)
            assertTrue(board.allCells().all { it.isRevealed })
        }
    }

    @Nested
    @DisplayName("Reveal semantics")
    inner class RevealSemantics {

        @Test
        @DisplayName("revealing a mine returns MINE_DETONATED and reveals only that cell")
        fun mineDetonation() {
            val mine = Coordinate(intArrayOf(0, 0))
            val board = Board.withFixedMines(intArrayOf(3, 3), wrap = false, mines = setOf(mine))
            val outcome = board.reveal(mine)
            assertEquals(RevealOutcome.MINE_DETONATED, outcome)
            assertTrue(board.cellAt(mine).isRevealed)
            assertEquals(1, board.allCells().count { it.isRevealed })
        }

        @Test
        @DisplayName("revealing every non-mine cell yields WON")
        fun winCondition() {
            val mine = Coordinate(intArrayOf(0, 0))
            val board = Board.withFixedMines(intArrayOf(3, 3), wrap = false, mines = setOf(mine))
            val outcome = board.reveal(Coordinate(intArrayOf(2, 2)))
            assertEquals(RevealOutcome.WON, outcome)
        }

        @Test
        @DisplayName("re-revealing an already-revealed cell is a no-op")
        fun reRevealIsNoOp() {
            val board = Board.withFixedMines(intArrayOf(3, 3), wrap = false, mines = emptySet())
            val first = board.reveal(Coordinate(intArrayOf(1, 1)))
            assertNotEquals(RevealOutcome.NO_OP, first)
            val second = board.reveal(Coordinate(intArrayOf(1, 1)))
            assertEquals(RevealOutcome.NO_OP, second)
        }

        @Test
        @DisplayName("a flagged cell cannot be revealed")
        fun flaggedRevealIsNoOp() {
            val board = Board.withFixedMines(intArrayOf(3, 3), wrap = false, mines = emptySet())
            val target = Coordinate(intArrayOf(1, 1))
            board.toggleFlag(target)
            assertEquals(RevealOutcome.NO_OP, board.reveal(target))
            assertFalse(board.cellAt(target).isRevealed)
        }

        @Test
        @DisplayName("toggleFlag is idempotent under double application")
        fun flagToggle() {
            val board = Board.withFixedMines(intArrayOf(3, 3), wrap = false, mines = emptySet())
            val target = Coordinate(intArrayOf(0, 0))
            board.toggleFlag(target)
            assertTrue(board.cellAt(target).isFlagged)
            board.toggleFlag(target)
            assertFalse(board.cellAt(target).isFlagged)
        }

        @Test
        @DisplayName("coordinate outside the board throws")
        fun outOfBoundsThrows() {
            val board = Board(intArrayOf(3, 3), totalMines = 1, wrap = false)
            assertThrows(IllegalArgumentException::class.java) {
                board.reveal(Coordinate(intArrayOf(3, 3)))
            }
        }
    }
}
