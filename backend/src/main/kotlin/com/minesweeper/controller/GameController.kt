package com.minesweeper.controller

import com.minesweeper.domain.Board
import com.minesweeper.domain.Coordinate
import com.minesweeper.dto.BoardDTO
import com.minesweeper.dto.CoordinatePayload
import com.minesweeper.dto.NewGameRequest
import com.minesweeper.dto.toDTO
import com.minesweeper.repository.GameRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// CORS opened wide for local dev; tightened to the frontend origin in Phase 5 once docker-compose is wired.
@CrossOrigin(origins = ["*"])
@RestController
@RequestMapping("/api/game")
class GameController(private val repository: GameRepository) {

    @PostMapping("/new")
    fun newGame(@RequestBody request: NewGameRequest): ResponseEntity<BoardDTO> {
        require(request.dimensions in 1..4) { "dimensions must be 1..4; got ${request.dimensions}" }
        require(request.size >= 1) { "size must be ≥ 1; got ${request.size}" }
        require(request.totalMines >= 0) { "totalMines must be ≥ 0; got ${request.totalMines}" }

        val bounds = IntArray(request.dimensions) { request.size }
        val board = Board(bounds, request.totalMines, request.wrap)
        val id = UUID.randomUUID()
        repository.save(id, board)
        return ResponseEntity.status(HttpStatus.CREATED).body(board.toDTO(id))
    }

    @PostMapping("/{id}/reveal")
    fun reveal(
        @PathVariable id: UUID,
        @RequestBody payload: CoordinatePayload,
    ): ResponseEntity<BoardDTO> {
        val board = repository.findById(id) ?: return ResponseEntity.notFound().build()
        board.reveal(Coordinate(payload.coordinate.toIntArray()))
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

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(e: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "bad request")))
}
