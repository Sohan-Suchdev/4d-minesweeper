package com.minesweeper.domain

data class Cell(
    val coordinate: Coordinate,
    val isMine: Boolean = false,
    val isRevealed: Boolean = false,
    val isFlagged: Boolean = false,
    val adjacentMines: Int = 0,
)
