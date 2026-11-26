package com.example

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

fun Application.configureRouting() {
    routing {

        // --- SECURE LOGIN ROUTE ---
        post("/login") {
            try {
                val request = call.receive<LoginRequest>()

                if (request.email == "host@fellowship.com" && request.password == "vault2026") {
                    call.respond(LoginResponse(
                        token = "god_tier_encrypted_token_999",
                        message = "ACCESS GRANTED. WELCOME TO THE VAULT."
                    ))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "Invalid credentials"))
                }
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid request format"))
            }
        }

        // --- VAULT BALANCE ROUTE ---
        get("/balance") {
            call.respond(BalanceResponse(balance = "1,000,000,000,000.00"))
        }

        // --- LIVE DASHBOARD FEED ---
        webSocket("/secure-feed") {
            send(Frame.Text("SYSTEM INITIALIZED. ENCRYPTED FEED ACTIVE."))
            val sectors = listOf("ALPHA", "OMEGA", "ZULU", "NEXUS", "ECHO", "NOVA")

            while (true) {
                delay((1500..4000).random().milliseconds)
                val amount = (10..999).random()
                val decimal = (1..9).random()
                val sector = sectors.random()
                send(Frame.Text("SECURE TRANSFER: $$amount.${decimal}B TO SECTOR $sector [VERIFIED]"))
            }
        }
    }
}