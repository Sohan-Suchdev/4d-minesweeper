package com.minesweeper.domain

/**
 * An N-dimensional integer coordinate (N in 1..4 for this game, but the type is dimension-agnostic).
 *
 * The class is the fundamental addressing primitive for the board: a 4D cell is `Coordinate([w, x, y, z])`,
 * a classic 2D cell is `Coordinate([x, y])`, and so on.
 *
 * `IntArray` uses reference equality by default, so we override [equals] and [hashCode] to delegate to
 * `contentEquals` / `contentHashCode`. This makes `Coordinate` safe to use as a `Set` element or
 * `Map` key — a requirement for the neighbour-set return type below and for board storage later.
 */
data class Coordinate(val coords: IntArray) {

    val dimensions: Int get() = coords.size

    /**
     * Returns the **Moore neighbourhood** of this coordinate within a grid of the given [bounds].
     *
     * Mathematically, the Moore neighbourhood in N dimensions is the Cartesian product
     * `{-1, 0, +1}^N` applied as an offset to this coordinate, minus the zero offset (the cell itself).
     * Cardinality is therefore `3^N − 1` — i.e. 2 (1D), 8 (2D), 26 (3D), 80 (4D).
     *
     * @param bounds the size of the grid along each axis; `bounds[i]` must be ≥ 1, and `bounds.size`
     *               must match this coordinate's dimensionality.
     * @param wrap   when `true`, the grid is treated as a hyper-torus and out-of-range offsets are
     *               wrapped via `Math.floorMod(c + dc, size)`. When `false`, out-of-range candidates
     *               are simply discarded (a corner cell in 4D has 15 neighbours, not 80).
     */
    fun neighbours(bounds: IntArray, wrap: Boolean): Set<Coordinate> {
        require(bounds.size == coords.size) {
            "bounds dimension ${bounds.size} does not match coordinate dimension ${coords.size}"
        }
        require(bounds.all { it >= 1 }) { "every bound must be ≥ 1; got ${bounds.toList()}" }

        val offsets = mooreOffsets(coords.size)
        val result = LinkedHashSet<Coordinate>(offsets.size)

        for (offset in offsets) {
            val next = IntArray(coords.size)
            var inBounds = true
            for (i in coords.indices) {
                val raw = coords[i] + offset[i]
                if (wrap) {
                    next[i] = Math.floorMod(raw, bounds[i])
                } else if (raw < 0 || raw >= bounds[i]) {
                    inBounds = false
                    break
                } else {
                    next[i] = raw
                }
            }
            if (inBounds) result.add(Coordinate(next))
        }
        // Under wrapping on degenerately small grids (size < 3 in some axis), distinct offsets can
        // collapse onto the same cell — including the centre itself. Strip self defensively.
        result.remove(this)
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Coordinate) return false
        return coords.contentEquals(other.coords)
    }

    override fun hashCode(): Int = coords.contentHashCode()

    override fun toString(): String = coords.joinToString(prefix = "(", postfix = ")")

    companion object {
        /**
         * Generates every non-zero offset vector in `{-1, 0, +1}^dimensions` — i.e. the set of
         * relative displacements that define the Moore neighbourhood.
         *
         * Implemented as an iterative Cartesian product: start with a single empty vector, and on each
         * of the N passes extend every existing prefix by appending each of `-1, 0, +1`. The final
         * `filter` drops the all-zero vector (which would map to the cell itself).
         *
         * Returned size is `3^N − 1`: 2, 8, 26, 80 for N = 1, 2, 3, 4.
         */
        internal fun mooreOffsets(dimensions: Int): List<IntArray> {
            require(dimensions >= 1) { "dimensions must be ≥ 1; got $dimensions" }
            var acc: List<IntArray> = listOf(IntArray(0))
            repeat(dimensions) {
                acc = acc.flatMap { prefix ->
                    listOf(-1, 0, 1).map { d -> prefix + d }
                }
            }
            return acc.filter { offset -> offset.any { it != 0 } }
        }
    }
}
