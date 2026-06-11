package com.minesweeper.repository

import com.minesweeper.domain.Board
import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface GameRepository {
    fun save(id: UUID, board: Board)
    fun findById(id: UUID): Board?
    fun delete(id: UUID): Boolean
}

@Repository
class InMemoryGameRepository : GameRepository {

    private val store: ConcurrentHashMap<UUID, Board> = ConcurrentHashMap()

    override fun save(id: UUID, board: Board) {
        store[id] = board
    }

    override fun findById(id: UUID): Board? = store[id]

    override fun delete(id: UUID): Boolean = store.remove(id) != null
}
