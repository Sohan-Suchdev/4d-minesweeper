package com.minesweeper.dto

import com.minesweeper.domain.Board
import com.minesweeper.domain.Cell
import java.util.UUID

data class NewGameRequest(
    val dimensions: Int,
    val size: Int,
    val totalMines: Int,
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

// Sole sanitisation gate: isMine and adjacentMines are only ever populated on revealed cells.
fun Cell.toSanitisedDTO(): CellDTO = CellDTO(
    coordinate = coordinate.coords.toList(),
    isRevealed = isRevealed,
    isFlagged = isFlagged,
    isMine = if (isRevealed) isMine else null,
    adjacentMines = if (isRevealed && !isMine) adjacentMines else null,
)

fun Board.toDTO(id: UUID): BoardDTO {
    val cellList = allCells().map { it.toSanitisedDTO() }
    val state = when {
        allCells().any { it.isMine && it.isRevealed } -> GameState.LOST
        isWon() -> GameState.WON
        else -> GameState.IN_PROGRESS
    }
    return BoardDTO(
        id = id,
        dimensions = dimensions.toList(),
        wrap = wrap,
        totalMines = totalMines,
        state = state,
        cells = cellList,
    )
}
