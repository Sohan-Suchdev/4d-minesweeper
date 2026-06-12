package com.minesweeper.controller

import com.minesweeper.domain.Board
import com.minesweeper.domain.Coordinate
import com.minesweeper.domain.RevealOutcome
import com.minesweeper.dto.BoardDTO
import com.minesweeper.dto.CoordinatePayload
import com.minesweeper.dto.Difficulty
import com.minesweeper.dto.NewGameRequest
import com.minesweeper.dto.toDTO
import com.minesweeper.repository.GameRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// Same-origin via nginx in docker; the listed origins cover non-docker dev (Spring on 8080 + static server on 3000/5173).
@CrossOrigin(origins = ["http://localhost:3000", "http://localhost:5173"])
@RestController
@RequestMapping("/api/game")
class GameController(private val repository: GameRepository) {

    @PostMapping("/new")
    fun newGame(@RequestBody request: NewGameRequest): ResponseEntity<BoardDTO> {
        val (dims, size, mines) = resolveBoardConfig(request)
        require(dims in 1..4) { "dimensions must be 1..4; got $dims" }
        require(size >= 1) { "size must be ≥ 1; got $size" }
        require(mines >= 0) { "totalMines must be ≥ 0; got $mines" }

        val bounds = IntArray(dims) { size }
        val board = Board(bounds, mines, request.wrap)
        val id = UUID.randomUUID()
        repository.save(id, board)
        return ResponseEntity.ok(board.toDTO(id))
    }

    @PostMapping("/{id}/reveal")
    fun reveal(
        @PathVariable id: UUID,
        @RequestBody payload: CoordinatePayload,
    ): ResponseEntity<BoardDTO> {
        val board = repository.findById(id) ?: return ResponseEntity.notFound().build()
        val outcome = board.reveal(Coordinate(payload.coordinate.toIntArray()))
        if (outcome == RevealOutcome.MINE_DETONATED) {
            // Detonation marks the game lost — expose every remaining mine so the client renders the full layout.
            board.allCells()
                .filter { it.isMine && !it.isRevealed }
                .map { it.coordinate }
                .forEach { board.reveal(it) }
        }
        return ResponseEntity.ok(board.toDTO(id))
    }

    @PostMapping("/{id}/flag")
    fun flag(
        @PathVariable id: UUID,
        @RequestBody payload: CoordinatePayload,
    ): ResponseEntity<BoardDTO> {
        val board = repository.findById(id) ?: return ResponseEntity.notFound().build()
        board.toggleFlag(Coordinate(payload.coordinate.toIntArray()))
        return ResponseEntity.ok(board.toDTO(id))
    }

    private fun resolveBoardConfig(request: NewGameRequest): Triple<Int, Int, Int> {
        val name = request.difficulty ?: return Triple(request.dimensions, request.size, request.totalMines)
        val preset = runCatching { Difficulty.valueOf(name.uppercase()) }
            .getOrElse { throw IllegalArgumentException("unknown difficulty '$name'; expected EASY, MEDIUM, or HARD") }
        return Triple(preset.dimensions, preset.size, preset.totalMines)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "bad request")))
}
