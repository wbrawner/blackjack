package com.wbrawner.blackjack

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import java.lang.reflect.Type
import java.time.Instant

abstract class Action(val type: String) {
    val timestamp: Long? = Instant.now().epochSecond
}

class Error(val message: String) : Action("error")
class StartGame(val player: String) : Action("startGame")

sealed class PlayerAction(
    type: String,
    var player: String
) : Action(type) {
    class Hit(player: String) : PlayerAction("hit", player)
    class Stand(player: String) : PlayerAction("stand", player)
}

fun Action.toFrame(): Frame = Frame.Text(gson.toJson(this))

fun Frame.Text.readAction(): Action = gson.fromJson(this.readText(), Action::class.java)

class ActionTypeAdapter : JsonDeserializer<Action> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): Action {
        val jObj = json?.asJsonObject ?: throw JsonParseException("Invalid action")
        val type = jObj.get("type")?.asString ?: throw JsonParseException("Invalid type for action: null")
        val player = jObj.get("player")?.asString ?: throw JsonParseException("Invalid player")
        return when (type) {
            "startGame" -> StartGame(player)
            "hit" -> PlayerAction.Hit(player)
            "stand" -> PlayerAction.Stand(player)
            else -> throw JsonParseException("Invalid type for action: $type")
        }
    }
}