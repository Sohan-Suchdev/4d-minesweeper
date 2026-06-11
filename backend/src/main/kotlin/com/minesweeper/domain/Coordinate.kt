package com.minesweeper.domain

data class Coordinate(val coords: IntArray) {

    val dimensions: Int get() = coords.size

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
                    // floorMod handles negatives correctly: floorMod(-1, n) == n - 1, giving the torus wrap.
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
