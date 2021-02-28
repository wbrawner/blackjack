package com.wbrawner.blackjack

import com.google.gson.GsonBuilder
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.http.cio.websocket.timeout
import io.ktor.http.content.resource
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

private val games = ConcurrentHashMap.newKeySet<Game>()
private val codePattern = Regex("[A-Z0-9]{4}")
val gson = GsonBuilder()
    .registerTypeAdapter(Action::class.java, ActionTypeAdapter())
    .create()

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    install(Compression) {
        gzip {
            priority = 1.0
        }
        deflate {
            priority = 10.0
            minimumSize(1024) // condition
        }
    }

    install(AutoHeadResponse)

    install(DefaultHeaders) {
        header("X-Engine", "Ktor") // will send this header with each response
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(ContentNegotiation) {
        gson {
        }
    }

    routing {
        static {
            resource("/", "static/index.html")
            resource("*", "static/index.html")
            resource("/join", "static/join.html")
            resource("/host", "static/host.html")
            resource("/game", "static/game.html")
            resources("static")
        }

        route("api") {
            post("new-game") {
                val newGame = gson.fromJson(call.receiveText(), NewGameRequest::class.java).run {
                    copy(name = this.name.sanitize())
                }
                if (newGame.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "No player name provided")
                    return@post
                }
                val game = Game(owner = newGame.name)
                games.add(game)
                val owner = game.getPlayerByName(newGame.name)!!
                call.respond(NewGameResponse(game.id, owner.secret))
            }

            post("join-game") {
                val joinRequest = gson.fromJson(call.receiveText(), JoinGameRequest::class.java).run {
                    copy(name = this.name.sanitize())
                }
                if (joinRequest.code.isBlank() || !codePattern.matches(joinRequest.code)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid code")
                    return@post
                }
                val game = games.firstOrNull { it.id == joinRequest.code }
                if (game == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid code")
                    return@post
                }
                val player = Player(joinRequest.name)
                if (joinRequest.name.isBlank() || !game.addPlayer(player)) {
                    call.respond(HttpStatusCode.BadRequest, "Name already taken")
                    return@post
                }
                game.addPlayer(player)
                call.respond(player.secret)
            }
        }

        webSocket("/games/{id}/{playerId}") {
            val game = games.firstOrNull { it.id == call.parameters["id"] }
            if (game == null) {
                send(Error("No game found for id ${call.parameters["id"]}").toFrame())
                return@webSocket
            }
            val player = call.parameters["playerId"]?.let { game.getPlayerBySecret(it) }
            if (player == null) {
                send(Error("No player found for id ${call.parameters["playerId"]}").toFrame())
                return@webSocket
            }
            player.webSocketSession.set(this)
            game.markOnline(player)
            val gameStateJob = launch {
                game.state.collect { state ->
                    send(state.toFrame())
                }
            }

            while (true) {
                try {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        game.postAction(frame.readAction())
                    }
                } catch (e: ClosedReceiveChannelException) {
                    gameStateJob.cancel()
                    player.webSocketSession.set(null)
                    game.markOnline(player)
                    break
                }
            }
        }
    }
}

