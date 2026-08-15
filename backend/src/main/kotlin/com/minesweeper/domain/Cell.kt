package com.minesweeper.domain

enum class FlagState {
    UNFLAGGED,
    FLAGGED,
    QUESTION,
}

data class Cell(
    val coordinate: Coordinate,
    val isMine: Boolean = false,
    val isRevealed: Boolean = false,
    val flagState: FlagState = FlagState.UNFLAGGED,
    val adjacentMines: Int = 0,
) {
    val isFlagged: Boolean get() = flagState == FlagState.FLAGGED
    val isQuestion: Boolean get() = flagState == FlagState.QUESTION
}
