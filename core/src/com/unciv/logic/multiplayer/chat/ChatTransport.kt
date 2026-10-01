package com.unciv.logic.multiplayer.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.close
import kotlinx.coroutines.isActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

interface ChatTransport {
    suspend fun connect(url: String, authentication: String, userAgent: String): ChatConnection
}

interface ChatConnection {
    val isActive: Boolean
    suspend fun send(message: Message)
    suspend fun receive(): Response
    suspend fun close()
}

/** The existing v1 wire format, shared with the native iOS transport. */
object ChatProtocol {
    @OptIn(ExperimentalSerializationApi::class)
    internal val json = Json {
        classDiscriminator = "type"
        classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)
    fun decode(message: String): Response = json.decodeFromString(Response.serializer(), message)
}

/** Preserves the existing Ktor implementation on Android and desktop. */
class KtorChatTransport : ChatTransport {
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 30.seconds
            contentConverter = KotlinxWebsocketSerializationConverter(ChatProtocol.json)
        }
    }

    override suspend fun connect(url: String, authentication: String, userAgent: String): ChatConnection {
        val session = client.webSocketSession {
            url(url)
            header(HttpHeaders.UserAgent, userAgent)
            header(HttpHeaders.Authorization, authentication)
        }
        return object : ChatConnection {
            override val isActive get() = session.isActive
            override suspend fun send(message: Message) = session.sendSerialized(message)
            override suspend fun receive(): Response = session.receiveDeserialized()
            override suspend fun close() { session.close() }
        }
    }
}
