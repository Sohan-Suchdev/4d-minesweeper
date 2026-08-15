package com.minesweeper.dto

import com.minesweeper.domain.Board
import com.minesweeper.domain.Cell
import java.util.UUID

enum class Difficulty(val dimensions: Int, val size: Int, val totalMines: Int) {
    EASY(4, 3, 10),
    MEDIUM(4, 4, 40),
    HARD(4, 5, 99),
}

data class NewGameRequest(
    val difficulty: String? = null,
    val dimensions: Int = 4,
    val size: Int = 3,
    val totalMines: Int = 10,
    val wrap: Boolean = false,
)

data class CoordinatePayload(val coordinate: List<Int>)

enum class GameState { IN_PROGRESS, WON, LOST }

data class CellDTO(
    val coordinate: List<Int>,
    val isRevealed: Boolean,
    val isFlagged: Boolean,
    val isMine: Boolean?,
    val adjacentMines: Int?,
)

data class BoardDTO(
    val id: UUID,
    val dimensions: List<Int>,
    val wrap: Boolean,
    val totalMines: Int,
    val state: GameState,
    val cells: List<CellDTO>,
)

// Sole sanitisation gate: mine identity is hidden until reveal, except after loss for board review.
fun Cell.toSanitisedDTO(revealMineIdentity: Boolean = false): CellDTO = CellDTO(
    coordinate = coordinate.coords.toList(),
    isRevealed = isRevealed,
    isFlagged = isFlagged,
    isMine = if (isRevealed || (revealMineIdentity && isMine)) isMine else null,
    adjacentMines = if (isRevealed && !isMine) adjacentMines else null,
)

fun Board.toDTO(id: UUID): BoardDTO {
    val state = when {
        allCells().any { it.isMine && it.isRevealed } -> GameState.LOST
        isWon() -> GameState.WON
        else -> GameState.IN_PROGRESS
    }
    val cellList = allCells().map { it.toSanitisedDTO(revealMineIdentity = state == GameState.LOST) }
    return BoardDTO(
        id = id,
        dimensions = dimensions.toList(),
        wrap = wrap,
        totalMines = totalMines,
        state = state,
        cells = cellList,
    )
}
