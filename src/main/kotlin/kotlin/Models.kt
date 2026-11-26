package com.example

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val message: String)

@Serializable
data class BalanceResponse(val balance: String)