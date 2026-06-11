package com.minesweeper

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MinesweeperApplication

fun main(args: Array<String>) {
    runApplication<MinesweeperApplication>(*args)
}
