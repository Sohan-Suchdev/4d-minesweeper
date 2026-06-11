package com.minesweeper.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.minesweeper.domain.Board
import com.minesweeper.dto.CoordinatePayload
import com.minesweeper.dto.NewGameRequest
import com.minesweeper.repository.GameRepository
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(GameController::class)
class GameControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var repository: GameRepository

    @Test
    @DisplayName("POST /api/game/new returns 200 OK with a sanitised board — no isMine or adjacentMines is ever leaked")
    fun newGameProducesSanitisedBoard() {
        val request = NewGameRequest(dimensions = 2, size = 4, totalMines = 3, wrap = false)

        mockMvc.perform(
            post("/api/game/new")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.totalMines").value(3))
            .andExpect(jsonPath("$.cells.length()").value(16))
            // JsonPath filters — the set of cells exposing mine identity or adjacency must be empty.
            .andExpect(jsonPath("$.cells[?(@.isMine != null)]").isEmpty)
            .andExpect(jsonPath("$.cells[?(@.adjacentMines != null)]").isEmpty)
            .andExpect(jsonPath("$.cells[*].isRevealed", everyItem(equalTo(false))))

        verify(repository).save(any<UUID>(), any<Board>())
    }

    @Test
    @DisplayName("POST /api/game/{id}/reveal forwards the click to the board and returns the post-cascade state")
    fun revealReturnsUpdatedBoard() {
        val gameId = UUID.randomUUID()
        // Mine-free fixed board, so the first reveal cascades to a full sweep and the game ends in WON.
        val board = Board.withFixedMines(intArrayOf(3, 3), wrap = false, mines = emptySet())
        whenever(repository.findById(gameId)).thenReturn(board)

        mockMvc.perform(
            post("/api/game/$gameId/reveal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CoordinatePayload(listOf(1, 1)))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value("WON"))
            .andExpect(jsonPath("$.cells.length()").value(9))
            .andExpect(jsonPath("$.cells[*].isRevealed", everyItem(equalTo(true))))
            // Revealed non-mines disclose adjacentMines but never a true isMine — sanitisation still holds.
            .andExpect(jsonPath("$.cells[*].isMine", everyItem(equalTo(false))))

        verify(repository).findById(gameId)
    }

    @Test
    @DisplayName("POST /api/game/{id}/reveal returns 404 Not Found for an unknown game id")
    fun revealOnMissingGameReturns404() {
        val gameId = UUID.randomUUID()
        whenever(repository.findById(gameId)).thenReturn(null)

        mockMvc.perform(
            post("/api/game/$gameId/reveal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(CoordinatePayload(listOf(0, 0)))),
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("POST /api/game/new returns 400 Bad Request when totalMines exceeds grid capacity")
    fun overSubscribedMinesReturnBadRequest() {
        // 2×2 grid = 4 cells; 5 mines is unsatisfiable and trips the Board ctor's require() guard.
        val request = NewGameRequest(dimensions = 2, size = 2, totalMines = 5, wrap = false)

        mockMvc.perform(
            post("/api/game/new")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }

    @Test
    @DisplayName("POST /api/game/new returns 400 Bad Request when dimensions are outside 1..4")
    fun invalidDimensionsReturnBadRequest() {
        val request = NewGameRequest(dimensions = 5, size = 3, totalMines = 1, wrap = false)

        mockMvc.perform(
            post("/api/game/new")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").exists())
    }
}
