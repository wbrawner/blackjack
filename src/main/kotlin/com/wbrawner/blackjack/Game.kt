package com.wbrawner.blackjack

import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private const val CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

class Game(val owner: String) {
    val id: String = CHARACTERS.random(4)
    private val lock = Mutex()
    private val _state: MutableSharedFlow<GameState> =
        MutableSharedFlow<GameState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(GameState(id, owner, listOf(PlayerState(owner)), owner))
        }
    val state = _state.asSharedFlow()
    private val players: MutableList<Player> = mutableListOf(Player(owner))
    private val lastState: GameState
        get() = _state.replayCache.first()
    private val deck = deck()

    suspend fun getPlayerByName(name: String): Player? = lock.withReentrantLock {
        players.firstOrNull { it.name == name }
    }

    suspend fun getPlayerBySecret(secret: String): Player? = lock.withReentrantLock {
        players.firstOrNull { it.secret == secret }
    }

    suspend fun addPlayer(player: Player) = lock.withReentrantLock {
        if (players.any { it.name == player.name }) {
            false
        } else {
            updateState { state ->
                val updatedPlayers = state.players.toMutableList().apply {
                    add(PlayerState(player.name))
                }
                state.copy(players = updatedPlayers)
            }
            players.add(player)
        }
    }

    suspend fun markOnline(player: Player) = lock.withReentrantLock {
        updateState { state ->
            val playerState = state.players.firstOrNull { it.name == player.name }
                ?.copy(online = player.webSocketSession.get() != null)
                ?: return@updateState state
            val currentPlayer = state.players.indexOfFirst { it.name == player.name }
            val updatedPlayers = state.players.toMutableList().apply {
                set(currentPlayer, playerState)
            }
            state.copy(players = updatedPlayers)
        }
    }

    suspend fun removePlayer(player: Player) = lock.withReentrantLock {
        players.removeIf { it.name == player.name }
        updateState { state ->
            state.copy(players = state.players.filterNot { it.name == player.name })
        }
    }

    private fun updateState(mutation: (state: GameState) -> GameState) {
        val updatedState = lastState.run { mutation(this) }
        _state.tryEmit(updatedState)
    }

    private suspend fun startGame(action: StartGame) = lock.withReentrantLock {
        if (action.player != getPlayerByName(owner)!!.secret) return@withReentrantLock
        if (players.size == 1) return@withReentrantLock
        if (lastState.started) return@withReentrantLock
        updateState { state -> state.copy(started = true, currentPlayer = state.players[1].name) }
        players.forEach { player ->
            repeat(2) {
                player.hand.add(deck.pop())
            }
            player.webSocketSession.get()?.send(player.toFrame())
        }
    }

    private suspend fun handlePlayerTurn(action: PlayerAction) = lock.withReentrantLock {
        if (!lastState.started) return@withReentrantLock
        val player = getPlayerByName(lastState.currentPlayer) ?: return@withReentrantLock
        if (action.player != player.secret) return@withReentrantLock
        updateState { state ->
            val playerState = state.players.firstOrNull { it.name == player.name }
                ?.copy(lastAction = action.type)
                ?: return@updateState state
            val currentPlayer = state.players.indexOfFirst { it.name == player.name }
            val updatedPlayers = state.players.toMutableList().apply {
                set(currentPlayer, playerState)
            }
            val nextPlayer = state.players[(currentPlayer + 1) % updatedPlayers.size].name
            state.copy(players = updatedPlayers, currentPlayer = nextPlayer)
        }
    }

    suspend fun postAction(action: Action) {
        when (action) {
            is StartGame -> startGame(action)
            is PlayerAction -> handlePlayerTurn(action)
            else -> throw IllegalArgumentException("Invalid action: ${action.type}")
        }
    }
}

data class GameState(
    val id: String,
    val host: String,
    val players: List<PlayerState>,
    val currentPlayer: String,
    val started: Boolean = false
) {
    val type = "game"
}

data class PlayerState(
    val name: String,
    val lastAction: String? = null,
    val online: Boolean = false
)

data class Player(
    val name: String,
    val secret: String = CHARACTERS.random(32),
    @Transient var webSocketSession: AtomicReference<WebSocketSession> = AtomicReference(),
    val hand: MutableList<Card> = mutableListOf()
) {
    val type = "player"
}

fun Any.toFrame(): Frame = Frame.Text(gson.toJson(this))

data class Card(
    val suit: Suit,
    val value: Value
) {
    val asset: String
        get() = "${value.name.toLowerCase()}_of_${suit.name.toLowerCase()}.png"

    enum class Suit { CLUBS, DIAMONDS, HEARTS, SPADES }
    enum class Value(val amount: Int) {
        ACE(1),
        TWO(2),
        THREE(3),
        FOUR(4),
        FIVE(5),
        SIX(6),
        SEVEN(7),
        EIGHT(8),
        NINE(9),
        TEN(10),
        JACK(10),
        QUEEN(10),
        KING(10)
    }
}

fun deck(shuffle: Boolean = true): Deque<Card> {
    val deck = ArrayList<Card>(52)
    Card.Suit.values().forEach { suit ->
        Card.Value.values().forEach { value ->
            deck.add(Card(suit, value))
        }
    }
    if (shuffle) deck.shuffle()
    return ArrayDeque(deck)
}

data class NewGameRequest(val name: String)

data class NewGameResponse(val gameId: String, val playerSecret: String)

data class JoinGameRequest(val code: String, val name: String)

fun CharSequence.random(length: Int): String {
    val string = StringBuilder()
    repeat(length) {
        string.append(random())
    }
    return string.toString()
}

fun String.sanitize(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

//source: https://github.com/Kotlin/kotlinx.coroutines/issues/1686#issuecomment-777357672
suspend fun <T> Mutex.withReentrantLock(block: suspend () -> T): T {
    val key = ReentrantMutexContextKey(this)
    // call block directly when this mutex is already locked in the context
    if (coroutineContext[key] != null) return block()
    // otherwise add it to the context and lock the mutex
    return withContext(ReentrantMutexContextElement(key)) {
        withLock { block() }
    }
}

class ReentrantMutexContextElement(
    override val key: ReentrantMutexContextKey
) : CoroutineContext.Element

data class ReentrantMutexContextKey(
    val mutex: Mutex
) : CoroutineContext.Key<ReentrantMutexContextElement>
