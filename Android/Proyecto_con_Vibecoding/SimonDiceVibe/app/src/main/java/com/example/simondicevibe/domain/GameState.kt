package com.example.simondicevibe.domain

enum class Phase { WAITING, WATCHING, YOUR_TURN, CLEARED, OVER }

enum class Banner { WAITING_ESP, WATCH, TURN, NICE, WRONG, TIMEOUT, CLEARED, OVER }

enum class LitSource { ESP_SEQUENCE, PLAYER_OK, PLAYER_WRONG }

/** A pad that is currently lit. [token] is unique so the UI can restart animations and clear it. */
data class Lit(val pad: Pad, val source: LitSource, val token: Int)

data class GameResult(
    val level: Int,
    val rewardSeconds: Int,
    val sequence: List<Pad>,
    val failedStep: Int,
)

data class GameState(
    val phase: Phase = Phase.WAITING,
    val banner: Banner = Banner.WAITING_ESP,
    val level: Int = 0,
    val lives: Int = 3,
    val sequenceLength: Int = 0,
    val completedSteps: Int = 0,
    val watchStep: Int = 0,
    val failedStep: Int = 0,
    val difficulty: String = "",
    val timeoutMs: Int = 0,
    val rewardSeconds: Int = 0,
    val lit: Lit? = null,
    /** Bumps every time the input countdown (re)starts. */
    val turnToken: Int = 0,
    val tick: Int = 0,
    /** True once any optional live telemetry message has been seen. */
    val liveTelemetry: Boolean = false,
    val result: GameResult? = null,
)

/** Pure reducer: (state, ESP32 message) -> new state. No Android, no I/O, easy to test. */
fun GameState.reduce(msg: EspMessage): GameState = when (msg) {
    is EspMessage.Level -> copy(
        phase = Phase.WATCHING,
        banner = Banner.WATCH,
        level = msg.level,
        lives = msg.lives,
        sequenceLength = msg.sequenceLength,
        difficulty = msg.difficulty,
        timeoutMs = msg.timeoutMs,
        completedSteps = 0,
        watchStep = 0,
        failedStep = 0,
        lit = null,
        result = null,
    )

    is EspMessage.LiveStep -> copy(
        phase = Phase.WATCHING,
        banner = Banner.WATCH,
        liveTelemetry = true,
        watchStep = msg.step,
        sequenceLength = msg.total,
        completedSteps = 0,
        tick = tick + 1,
        lit = Lit(msg.pad, LitSource.ESP_SEQUENCE, tick + 1),
    )

    is EspMessage.YourTurn -> copy(
        phase = Phase.YOUR_TURN,
        banner = Banner.TURN,
        liveTelemetry = true,
        timeoutMs = msg.timeoutMs,
        completedSteps = 0,
        watchStep = 0,
        lit = null,
        turnToken = turnToken + 1,
    )

    is EspMessage.Touch -> if (msg.correct) {
        val more = msg.step < msg.total
        copy(
            phase = if (more) Phase.YOUR_TURN else phase,
            banner = Banner.NICE,
            lives = msg.lives,
            sequenceLength = msg.total,
            completedSteps = msg.step,
            tick = tick + 1,
            lit = msg.pad?.let { Lit(it, LitSource.PLAYER_OK, tick + 1) },
            turnToken = if (more) turnToken + 1 else turnToken,
        )
    } else {
        copy(
            phase = Phase.WATCHING,
            banner = Banner.WRONG,
            lives = msg.lives,
            sequenceLength = msg.total,
            completedSteps = msg.step - 1,
            failedStep = msg.step,
            watchStep = 0,
            tick = tick + 1,
            lit = msg.pad?.let { Lit(it, LitSource.PLAYER_WRONG, tick + 1) },
        )
    }

    is EspMessage.Timeout -> copy(
        phase = Phase.WATCHING,
        banner = Banner.TIMEOUT,
        lives = msg.lives,
        sequenceLength = msg.total,
        completedSteps = msg.step - 1,
        failedStep = msg.step,
        watchStep = 0,
        lit = null,
    )

    is EspMessage.LevelCleared -> copy(
        phase = Phase.CLEARED,
        banner = Banner.CLEARED,
        lives = msg.lives,
        rewardSeconds = msg.rewardSeconds,
        lit = null,
    )

    is EspMessage.GameOver -> copy(
        phase = Phase.OVER,
        banner = Banner.OVER,
        lives = 0,
        rewardSeconds = msg.rewardSeconds,
        lit = null,
        result = GameResult(msg.level, msg.rewardSeconds, msg.sequence, failedStep),
    )
}

/** How long the ESP32 spends replaying a sequence (used only when live telemetry is absent). */
fun estimatePlaybackMs(sequenceLength: Int, timeoutMs: Int): Long =
    50L + sequenceLength * (timeoutMs / 4L + timeoutMs / 5L)

/** Hand-off of the finished game from the game screen to the result screen. */
class SessionStore {
    @Volatile
    var lastResult: GameResult? = null
}
