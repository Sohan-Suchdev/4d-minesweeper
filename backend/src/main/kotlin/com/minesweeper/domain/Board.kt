package com.minesweeper.domain

import kotlin.random.Random

enum class RevealOutcome { CASCADE, MINE_DETONATED, WON, NO_OP }

class Board(
    dimensions: IntArray,
    val totalMines: Int,
    val wrap: Boolean,
    private val random: Random = Random.Default,
) {
    val dimensions: IntArray = dimensions.copyOf()
    val totalCells: Int = dimensions.fold(1) { acc, d -> acc * d }

    private val cells: MutableMap<Coordinate, Cell>
    var minesInitialised: Boolean = false
        private set

    init {
        require(dimensions.isNotEmpty() && dimensions.all { it >= 1 }) {
            "dimensions must be non-empty and positive; got ${dimensions.toList()}"
        }
        require(totalMines in 0 until totalCells) {
            "totalMines must be in [0, $totalCells); got $totalMines"
        }
        cells = LinkedHashMap(totalCells)
        for (c in enumerateCoordinates()) cells[c] = Cell(c)
    }

    fun cellAt(coordinate: Coordinate): Cell =
        cells[coordinate] ?: throw IllegalArgumentException("$coordinate is outside the board")

    fun allCells(): Collection<Cell> = cells.values

    fun toggleFlag(coordinate: Coordinate) {
        val cell = cellAt(coordinate)
        if (cell.isRevealed) return
        val nextState = when (cell.flagState) {
            FlagState.UNFLAGGED -> FlagState.FLAGGED
            FlagState.FLAGGED -> FlagState.QUESTION
            FlagState.QUESTION -> FlagState.UNFLAGGED
        }
        cells[coordinate] = cell.copy(flagState = nextState)
    }

    fun reveal(coordinate: Coordinate): RevealOutcome {
        cellAt(coordinate)
        val initialSafeZone = if (!minesInitialised) initialiseMines(safeOrigin = coordinate) else emptySet()

        val target = cells[coordinate]!!
        if (target.isRevealed || target.flagState == FlagState.FLAGGED) return RevealOutcome.NO_OP
        if (target.isMine) {
            cells[coordinate] = target.copy(isRevealed = true)
            return RevealOutcome.MINE_DETONATED
        }

        cascadeFrom(coordinate)
        for (safeCoordinate in initialSafeZone) {
            if (safeCoordinate != coordinate) cascadeFrom(safeCoordinate)
        }
        return if (isWon()) RevealOutcome.WON else RevealOutcome.CASCADE
    }

    fun isWon(): Boolean = cells.values.all { it.isMine || it.isRevealed }

    /**
     * Deferred mine placement: the clicked cell plus an irregular random subset of its Moore
     * neighbourhood are excluded, then revealed to create an organic starting island.
     */
    private fun initialiseMines(safeOrigin: Coordinate): Set<Coordinate> {
        val safeZone = initialSafeZone(safeOrigin)
        val candidates = cells.keys - safeZone
        require(totalMines <= candidates.size) {
            "totalMines=$totalMines exceeds ${candidates.size} placeable cells after safe-zone exclusion"
        }
        val chosen = candidates.shuffled(random).take(totalMines).toHashSet()
        installMines(chosen)
        minesInitialised = true
        return safeZone
    }

    private fun initialSafeZone(safeOrigin: Coordinate): Set<Coordinate> {
        val neighbours = safeOrigin.neighbours(dimensions, wrap).toList()
        val safeClusterSize = when {
            neighbours.isEmpty() -> 0
            neighbours.size < MIN_INITIAL_SAFE_CLUSTER_SIZE -> neighbours.size
            else -> random.nextInt(
                MIN_INITIAL_SAFE_CLUSTER_SIZE,
                minOf(MAX_INITIAL_SAFE_CLUSTER_SIZE, neighbours.size) + 1,
            )
        }
        return (neighbours.shuffled(random).take(safeClusterSize) + safeOrigin).toSet()
    }

    private fun installMines(mineCoords: Set<Coordinate>) {
        for (c in mineCoords) cells[c] = cells[c]!!.copy(isMine = true)
        for ((c, cell) in cells.toMap()) {
            if (cell.isMine) continue
            val adjacent = c.neighbours(dimensions, wrap).count { cells[it]!!.isMine }
            cells[c] = cell.copy(adjacentMines = adjacent)
        }
    }

    /**
     * Iterative BFS replaces the textbook recursive flood-fill: a 4D 5^4 = 625 grid can produce a
     * cascade chain too deep for the JVM call stack, so we expand via an explicit queue instead.
     */
    private fun cascadeFrom(origin: Coordinate) {
        val queue = ArrayDeque<Coordinate>().apply { addLast(origin) }
        while (queue.isNotEmpty()) {
            val coord = queue.removeFirst()
            val cell = cells[coord] ?: continue
            if (cell.isRevealed || cell.flagState == FlagState.FLAGGED || cell.isMine) continue
            cells[coord] = cell.copy(isRevealed = true, flagState = FlagState.UNFLAGGED)
            if (cell.adjacentMines == 0) {
                for (n in coord.neighbours(dimensions, wrap)) {
                    val neighbourCell = cells[n] ?: continue
                    if (
                        !neighbourCell.isRevealed &&
                        neighbourCell.flagState != FlagState.FLAGGED &&
                        !neighbourCell.isMine
                    ) {
                        queue.addLast(n)
                    }
                }
            }
        }
    }

    // Mixed-radix increment across the N axes — yields every lattice point in row-major order.
    private fun enumerateCoordinates(): Sequence<Coordinate> = sequence {
        val n = dimensions.size
        val current = IntArray(n)
        while (true) {
            yield(Coordinate(current.copyOf()))
            var axis = n - 1
            while (axis >= 0) {
                current[axis]++
                if (current[axis] < dimensions[axis]) break
                current[axis] = 0
                axis--
            }
            if (axis < 0) return@sequence
        }
    }

    companion object {
        private const val MIN_INITIAL_SAFE_CLUSTER_SIZE = 10
        private const val MAX_INITIAL_SAFE_CLUSTER_SIZE = 40

        /** Test fixture: skip the safe-start randomiser and install a predetermined mine layout. */
        internal fun withFixedMines(
            dimensions: IntArray,
            wrap: Boolean,
            mines: Set<Coordinate>,
        ): Board {
            val board = Board(dimensions, mines.size, wrap)
            board.installMines(mines)
            board.minesInitialised = true
            return board
        }
    }
}
