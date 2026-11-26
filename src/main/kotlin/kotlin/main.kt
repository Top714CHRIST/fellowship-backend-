import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.Deflater
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// --- AGORA & CLOUD CONFIGURATION (PASTE YOUR 32-CHAR PRIMARY CERTIFICATE BELOW) ---
val AGORA_APP_ID: String = System.getenv("AGORA_APP_ID") ?: "4754cb0871bb457086d719ddb5aa163c"
val AGORA_APP_CERTIFICATE: String = System.getenv("AGORA_APP_CERTIFICATE") ?: "PASTE_YOUR_32_CHAR_PRIMARY_CERTIFICATE_HERE"
const val FALLBACK_TEMP_TOKEN = "007eJxTYNhbH2AuFPL0vZagsl61elP8hZfLGVuFTKp/a22X+2jycK8Cg4m5qUlykoGFuWFSkompuYGFWYq5oWVKSpJpYqKhmXHyIVHW7IZARgah/mQWRgYIBPF5GYJdnUP8g+IdfQI8HBUYGAC95x74"

fun isAutoTokenMintingEnabled(): Boolean {
    return AGORA_APP_CERTIFICATE.length == 32 &&
            AGORA_APP_CERTIFICATE.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}

// --- PURE-KOTLIN AGORA ACCESSTOKEN2 (VERSION 007) GENERATOR ---
object AgoraTokenGenerator {
    private const val VERSION = "007"
    private const val SERVICE_TYPE_RTC = 1
    private const val PRIV_JOIN_CHANNEL = 1
    private const val PRIV_PUBLISH_AUDIO = 2
    private const val PRIV_PUBLISH_VIDEO = 3
    private const val PRIV_PUBLISH_DATA = 4
    private val secureRandom = SecureRandom()

    private fun packUint16(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    private fun packUint32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun packBytes(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(packUint16(bytes.size))
        out.write(bytes)
        return out.toByteArray()
    }

    private fun packString(str: String): ByteArray = packBytes(str.toByteArray(Charsets.UTF_8))

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun compressZlib(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            out.write(buffer, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    fun buildRtcToken(
        appId: String,
        appCertificate: String,
        channelName: String,
        uid: Int = 0,
        expireSeconds: Int = 86_400
    ): String {
        val issueTs = (System.currentTimeMillis() / 1000L).toInt()
        val salt = secureRandom.nextInt(99_999_999) + 1

        var signing = hmacSha256(packUint32(issueTs), appCertificate.toByteArray(Charsets.UTF_8))
        signing = hmacSha256(packUint32(salt), signing)

        val privileges = TreeMap<Int, Int>().apply {
            put(PRIV_JOIN_CHANNEL, expireSeconds)
            put(PRIV_PUBLISH_AUDIO, expireSeconds)
            put(PRIV_PUBLISH_VIDEO, expireSeconds)
            put(PRIV_PUBLISH_DATA, expireSeconds)
        }
        val serviceOut = ByteArrayOutputStream()
        serviceOut.write(packUint16(SERVICE_TYPE_RTC))
        serviceOut.write(packUint16(privileges.size))
        for ((privKey, privExpire) in privileges) {
            serviceOut.write(packUint16(privKey))
            serviceOut.write(packUint32(privExpire))
        }
        serviceOut.write(packString(channelName))
        serviceOut.write(packString(if (uid == 0) "" else uid.toString()))
        val packedRtcService = serviceOut.toByteArray()

        val infoOut = ByteArrayOutputStream()
        infoOut.write(packString(appId))
        infoOut.write(packUint32(issueTs))
        infoOut.write(packUint32(expireSeconds))
        infoOut.write(packUint32(salt))
        infoOut.write(packUint16(1))
        infoOut.write(packedRtcService)
        val signingInfo = infoOut.toByteArray()

        val signature = hmacSha256(signing, signingInfo)
        val contentOut = ByteArrayOutputStream()
        contentOut.write(packBytes(signature))
        contentOut.write(signingInfo)

        val compressed = compressZlib(contentOut.toByteArray())
        return VERSION + Base64.getEncoder().encodeToString(compressed)
    }
}

// --- MULTI-TENANT MOTHER MAINFRAME STATE & AUTO DISK PERSISTENCE ---
val globalLock = Any()
val diskLock = Any()
val persistenceJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}
val stateBackupFile = File("fellowship_state.json")

@Volatile var globalTariffPerGbUsd: Double = 1_000_000.00
@Volatile var lastUsageDiskSaveMs: Long = 0L

class HostAccount(
    val email: String,
    val companyName: String,
    val password: String,
    @Volatile var liquidityUsd: Double = 1_000_000_000_000.00,
    @Volatile var dataPoolMb: Double = 1024.0,
    @Volatile var activeRoomCode: String? = null,
    val purchaseHistory: CopyOnWriteArrayList<ServerPurchaseLog> = CopyOnWriteArrayList(),
    val dashboardSessions: CopyOnWriteArraySet<DefaultWebSocketServerSession> = CopyOnWriteArraySet()
)

val hostAccounts = ConcurrentHashMap<String, HostAccount>()
val allConnectedDashboards = CopyOnWriteArraySet<DefaultWebSocketServerSession>()
val activeRooms = ConcurrentHashMap<String, SponsoredRoom>()

@Serializable
data class ServerPurchaseLog(
    val timestamp: String,
    val gbAdded: Double,
    val costDeductedUsd: Double,
    val remainingLiquidityUsd: Double
)

@Serializable
data class PersistedHostAccount(
    val email: String,
    val companyName: String,
    val password: String,
    val liquidityUsd: Double,
    val dataPoolMb: Double,
    val purchaseHistory: List<ServerPurchaseLog> = emptyList()
)

@Serializable
data class PersistedMainframeState(
    val globalTariffPerGbUsd: Double,
    val accounts: List<PersistedHostAccount>
)

fun saveMainframeStateToDisk() {
    synchronized(diskLock) {
        try {
            val accountsSnapshot = hostAccounts.values.map { acc ->
                synchronized(acc) {
                    PersistedHostAccount(
                        email = acc.email,
                        companyName = acc.companyName,
                        password = acc.password,
                        liquidityUsd = acc.liquidityUsd,
                        dataPoolMb = acc.dataPoolMb,
                        purchaseHistory = acc.purchaseHistory.take(25)
                    )
                }
            }
            val payload = PersistedMainframeState(
                globalTariffPerGbUsd = globalTariffPerGbUsd,
                accounts = accountsSnapshot
            )
            val serialized = persistenceJson.encodeToString(PersistedMainframeState.serializer(), payload)
            stateBackupFile.writeText(serialized)
        } catch (_: Exception) {
        }
    }
}

fun loadMainframeStateFromDisk() {
    synchronized(diskLock) {
        if (stateBackupFile.exists()) {
            try {
                val raw = stateBackupFile.readText()
                val restored = persistenceJson.decodeFromString(PersistedMainframeState.serializer(), raw)
                globalTariffPerGbUsd = restored.globalTariffPerGbUsd
                restored.accounts.forEach { saved ->
                    hostAccounts[saved.email] = HostAccount(
                        email = saved.email,
                        companyName = saved.companyName,
                        password = saved.password,
                        liquidityUsd = saved.liquidityUsd,
                        dataPoolMb = saved.dataPoolMb,
                        purchaseHistory = CopyOnWriteArrayList(saved.purchaseHistory)
                    )
                }
            } catch (_: Exception) {
            }
        }
        // Ensure default executive account always exists
        hostAccounts.putIfAbsent(
            "host@fellowship.com",
            HostAccount(
                email = "host@fellowship.com",
                companyName = "FELLOWSHIP HQ",
                password = "vault2026"
            )
        )
    }
}

data class RoomParticipant(
    val session: DefaultWebSocketServerSession,
    val role: String // "HOST" or "GUEST"
)

class SponsoredRoom(
    val code: String,
    val hostEmail: String,
    val channelName: String,
    val agoraToken: String,
    @Volatile var isActive: Boolean = true,
    @Volatile var totalConsumedMb: Double = 0.0,
    val participants: CopyOnWriteArraySet<RoomParticipant> = CopyOnWriteArraySet()
)

fun buildHostStateResponse(account: HostAccount): HostStateResponse {
    synchronized(account) {
        return HostStateResponse(
            email = account.email,
            companyName = account.companyName,
            liquidityUsd = account.liquidityUsd,
            tariffPerGbUsd = globalTariffPerGbUsd,
            dataPoolMb = account.dataPoolMb,
            activeRoomCode = account.activeRoomCode,
            purchaseHistory = account.purchaseHistory.take(10)
        )
    }
}

suspend fun broadcastTariffToAllOrganizations(newPrice: Double) {
    synchronized(globalLock) {
        globalTariffPerGbUsd = newPrice
    }
    saveMainframeStateToDisk()
    val formatted = String.format(Locale.US, "%,.2f", newPrice)
    val payload = "TARIFF_UPDATE:$newPrice|MOTHER COMMAND: GLOBAL RATE SET TO $$formatted / GB"
    for (session in allConnectedDashboards) {
        try {
            session.send(Frame.Text(payload))
        } catch (_: Exception) {
            allConnectedDashboards.remove(session)
        }
    }
}

suspend fun pushStateToOrganization(account: HostAccount, note: String) {
    val liq: Double
    val pool: Double
    val roomCode: String
    synchronized(account) {
        liq = account.liquidityUsd
        pool = account.dataPoolMb
        roomCode = account.activeRoomCode ?: ""
    }
    val payload = "STATE_SYNC:$liq|$pool|$roomCode|$note"
    for (session in account.dashboardSessions) {
        try {
            session.send(Frame.Text(payload))
        } catch (_: Exception) {
            account.dashboardSessions.remove(session)
            allConnectedDashboards.remove(session)
        }
    }
}

suspend fun broadcastRoomSync(room: SponsoredRoom) {
    val account = hostAccounts[room.hostEmail] ?: return
    val remainingMb: Double
    val status: String
    synchronized(account) {
        remainingMb = account.dataPoolMb
        status = when {
            !room.isActive -> "ENDED"
            remainingMb <= 0.0 -> "DEPLETED"
            remainingMb <= 100.0 -> "LOW_DATA"
            else -> "ACTIVE"
        }
    }
    val guestCount = room.participants.count { it.role == "GUEST" }
    val payload = "POOL_SYNC:$remainingMb|${room.totalConsumedMb}|$guestCount|$status"
    for (p in room.participants) {
        try {
            p.session.send(Frame.Text(payload))
        } catch (_: Exception) {
            room.participants.remove(p)
        }
    }
}

suspend fun enforceRoomCutoff(room: SponsoredRoom, reason: String) {
    room.isActive = false
    val account = hostAccounts[room.hostEmail]
    if (account != null) {
        synchronized(account) {
            if (account.activeRoomCode == room.code) {
                account.activeRoomCode = null
            }
        }
    }
    saveMainframeStateToDisk()
    for (p in room.participants) {
        try {
            p.session.send(Frame.Text("CUTOFF:$reason"))
        } catch (_: Exception) {
            room.participants.remove(p)
        }
    }
    if (account != null) {
        pushStateToOrganization(account, "ROOM ${room.code} TERMINATED: $reason")
    }
}

suspend fun processReportedDataUsage(room: SponsoredRoom, deltaMb: Double) {
    if (!room.isActive || deltaMb <= 0.0) return
    val account = hostAccounts[room.hostEmail] ?: return

    var depletedNow = false
    var remainingNow = 0.0
    synchronized(account) {
        account.dataPoolMb = (account.dataPoolMb - deltaMb).coerceAtLeast(0.0)
        room.totalConsumedMb += deltaMb
        remainingNow = account.dataPoolMb
        if (remainingNow <= 0.0) {
            depletedNow = true
        }
    }

    if (depletedNow) {
        broadcastRoomSync(room)
        enforceRoomCutoff(room, "HOST_DATA_SHIELD_DEPLETED")
    } else {
        val now = System.currentTimeMillis()
        if (now - lastUsageDiskSaveMs > 10_000L) {
            lastUsageDiskSaveMs = now
            saveMainframeStateToDisk()
        }
        broadcastRoomSync(room)
        pushStateToOrganization(
            account,
            "ROOM ${room.code} LIVE • ${String.format(Locale.US, "%.2f", remainingNow)} MB SHIELD LEFT"
        )
    }
}

fun main() {
    loadMainframeStateFromDisk()

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val jwtSecret = System.getenv("JWT_SECRET") ?: "fellowship-trillion-dollar-secret-key-2026"
    val jwtIssuer = "fellowship-mother-mainframe"
    val jwtAudience = "fellowship-users"

    val jwtVerifier = JWT.require(Algorithm.HMAC256(jwtSecret))
        .withAudience(jwtAudience)
        .withIssuer(jwtIssuer)
        .build()

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            })
        }
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        install(Authentication) {
            jwt("auth-jwt") {
                verifier(jwtVerifier)
                validate { credential ->
                    val email = credential.payload.getClaim("email").asString()
                    if (!email.isNullOrBlank() && hostAccounts.containsKey(email)) {
                        JWTPrincipal(credential.payload)
                    } else null
                }
            }
        }

        routing {
            // HEALTH CHECK FOR CLOUD DEPLOYMENT (RENDER / RAILWAY)
            get("/health") {
                call.respond(HttpStatusCode.OK, MessageResponse("FELLOWSHIP MOTHER MAINFRAME ONLINE"))
            }

            // 1. MULTI-TENANT ORGANIZATION REGISTRATION
            post("/register") {
                val req = call.receive<RegisterRequest>()
                val email = req.email.trim().lowercase(Locale.US)
                val company = req.companyName.trim().uppercase(Locale.US).ifBlank { "ORGANIZATION" }
                val password = req.password.trim()

                if (email.isBlank() || !email.contains("@") || password.length < 4) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("VALID EMAIL & 4+ CHAR PASSPHRASE REQUIRED."))
                    return@post
                }
                if (hostAccounts.containsKey(email)) {
                    call.respond(HttpStatusCode.Conflict, MessageResponse("ORGANIZATION ALREADY REGISTERED."))
                    return@post
                }

                hostAccounts[email] = HostAccount(
                    email = email,
                    companyName = company,
                    password = password,
                    liquidityUsd = 10_000_000_000.00,
                    dataPoolMb = 1024.0
                )
                saveMainframeStateToDisk()
                call.respond(HttpStatusCode.OK, MessageResponse("ORGANIZATION REGISTERED: $company"))
            }

            // 2. MULTI-TENANT HOST AUTHENTICATION
            post("/login") {
                val credentials = call.receive<LoginRequest>()
                val email = credentials.email.trim().lowercase(Locale.US)
                val account = hostAccounts[email]
                if (account != null && account.password == credentials.password) {
                    val token = JWT.create()
                        .withAudience(jwtAudience)
                        .withIssuer(jwtIssuer)
                        .withClaim("email", account.email)
                        .withExpiresAt(Date(System.currentTimeMillis() + 86_400_000L * 30))
                        .sign(Algorithm.HMAC256(jwtSecret))
                    call.respond(HttpStatusCode.OK, LoginResponse(token = token, message = "CLEARANCE GRANTED"))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, MessageResponse("INVALID CREDENTIALS"))
                }
            }

            // 3. ISOLATED PER-ORGANIZATION HOST ENDPOINTS
            authenticate("auth-jwt") {
                fun ApplicationCall.currentHost(): HostAccount? {
                    val email = principal<JWTPrincipal>()?.payload?.getClaim("email")?.asString()
                    return email?.let { hostAccounts[it] }
                }

                get("/balance") {
                    val account = call.currentHost() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    val liveFormatted = synchronized(account) {
                        String.format(Locale.US, "%,.2f", account.liquidityUsd)
                    }
                    call.respond(HttpStatusCode.OK, BalanceResponse(balance = liveFormatted))
                }

                get("/api/host/state") {
                    val account = call.currentHost() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                    call.respond(HttpStatusCode.OK, buildHostStateResponse(account))
                }

                post("/api/host/topup") {
                    val account = call.currentHost() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    var succeeded = false
                    var rateUsed = 0.0
                    synchronized(account) {
                        rateUsed = globalTariffPerGbUsd
                        if (account.liquidityUsd >= rateUsed) {
                            account.liquidityUsd -= rateUsed
                            account.dataPoolMb += 1024.0
                            val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                            account.purchaseHistory.add(
                                0,
                                ServerPurchaseLog(
                                    timestamp = now,
                                    gbAdded = 1.0,
                                    costDeductedUsd = rateUsed,
                                    remainingLiquidityUsd = account.liquidityUsd
                                )
                            )
                            succeeded = true
                        }
                    }

                    if (succeeded) {
                        saveMainframeStateToDisk()
                        val rateStr = String.format(Locale.US, "%,.2f", rateUsed)
                        pushStateToOrganization(account, "POOL TOP-UP: +1.00 GB (-$$rateStr)")
                        account.activeRoomCode?.let { code ->
                            activeRooms[code]?.let { room -> broadcastRoomSync(room) }
                        }
                        call.respond(HttpStatusCode.OK, buildHostStateResponse(account))
                    } else {
                        call.respond(
                            HttpStatusCode.PaymentRequired,
                            MessageResponse("INSUFFICIENT VAULT LIQUIDITY FOR 1 GB TARIFF.")
                        )
                    }
                }

                post("/api/rooms/create") {
                    val account = call.currentHost() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val poolAvailable = synchronized(account) { account.dataPoolMb }
                    if (poolAvailable <= 0.0) {
                        call.respond(
                            HttpStatusCode.PaymentRequired,
                            MessageResponse("HOST DATA SHIELD DEPLETED. ADD +1 GB POOL FIRST.")
                        )
                        return@post
                    }

                    account.activeRoomCode?.let { prevCode ->
                        activeRooms[prevCode]?.let { oldRoom ->
                            if (oldRoom.isActive) enforceRoomCutoff(oldRoom, "HOST_OPENED_NEW_SECTOR")
                        }
                    }

                    var generatedCode: String
                    do {
                        generatedCode = Random.nextInt(100000, 999999).toString()
                    } while (activeRooms.containsKey(generatedCode))

                    val autoMint = isAutoTokenMintingEnabled()
                    val channelName = if (autoMint) "ROOM_$generatedCode" else "SECTOR_ALPHA"
                    val mintedToken = if (autoMint) {
                        AgoraTokenGenerator.buildRtcToken(
                            appId = AGORA_APP_ID,
                            appCertificate = AGORA_APP_CERTIFICATE,
                            channelName = channelName,
                            uid = 0,
                            expireSeconds = 86_400
                        )
                    } else {
                        FALLBACK_TEMP_TOKEN
                    }

                    val room = SponsoredRoom(
                        code = generatedCode,
                        hostEmail = account.email,
                        channelName = channelName,
                        agoraToken = mintedToken
                    )
                    activeRooms[generatedCode] = room
                    synchronized(account) {
                        account.activeRoomCode = generatedCode
                    }

                    pushStateToOrganization(account, "ROOM $generatedCode OPENED • AUTO-TOKEN READY")
                    call.respond(
                        HttpStatusCode.OK,
                        CreateRoomResponse(
                            roomCode = generatedCode,
                            channelName = room.channelName,
                            agoraToken = room.agoraToken,
                            hostDataPoolMb = poolAvailable,
                            tokenMode = if (autoMint) "AUTO_MINTED" else "FALLBACK_TEMP"
                        )
                    )
                }

                post("/api/rooms/close") {
                    val account = call.currentHost() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                    val req = call.receive<CloseRoomRequest>()
                    val room = activeRooms[req.roomCode]
                    if (room != null && room.isActive && room.hostEmail == account.email) {
                        enforceRoomCutoff(room, "HOST_TERMINATED_SESSION")
                    }
                    call.respond(HttpStatusCode.OK, MessageResponse("ROOM CLOSED"))
                }
            }

            // 4. PUBLIC GUEST ROOM JOIN (RECEIVES SERVER-MINTED AGORA TOKEN AUTOMATICALLY)
            post("/api/rooms/join") {
                val req = call.receive<JoinRoomRequest>()
                val room = activeRooms[req.roomCode.trim()]
                val hostAccount = room?.let { hostAccounts[it.hostEmail] }
                val poolAvailable = hostAccount?.let { synchronized(it) { it.dataPoolMb } } ?: 0.0

                when {
                    room == null || !room.isActive || hostAccount == null -> {
                        call.respond(
                            HttpStatusCode.NotFound,
                            JoinRoomResponse(
                                allowed = false,
                                channelName = "",
                                agoraToken = "",
                                hostDataPoolMb = 0.0,
                                hostCompany = "",
                                message = "INVALID OR EXPIRED HOST ACCESS CODE."
                            )
                        )
                    }
                    poolAvailable <= 0.0 -> {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            JoinRoomResponse(
                                allowed = false,
                                channelName = "",
                                agoraToken = "",
                                hostDataPoolMb = 0.0,
                                hostCompany = hostAccount.companyName,
                                message = "HOST DATA SHIELD DEPLETED (0.00 MB)."
                            )
                        )
                    }
                    else -> {
                        call.respond(
                            HttpStatusCode.OK,
                            JoinRoomResponse(
                                allowed = true,
                                channelName = room.channelName,
                                agoraToken = room.agoraToken,
                                hostDataPoolMb = poolAvailable,
                                hostCompany = hostAccount.companyName,
                                message = "SPONSORED BY ${hostAccount.companyName} • FREE ENTRY"
                            )
                        )
                    }
                }
            }

            // 5. PER-ORGANIZATION LIVE WEBSOCKET STREAM (GLOBAL TARIFFS + ISOLATED STATE)
            webSocket("/secure-feed") {
                val rawToken = call.request.queryParameters["token"].orEmpty()
                val email = runCatching {
                    jwtVerifier.verify(rawToken).getClaim("email").asString()
                }.getOrNull()
                val account = email?.let { hostAccounts[it] }

                allConnectedDashboards.add(this)
                account?.dashboardSessions?.add(this)

                try {
                    val initRate = String.format(Locale.US, "%,.2f", globalTariffPerGbUsd)
                    send(Frame.Text("TARIFF_UPDATE:$globalTariffPerGbUsd|MOTHER MAINFRAME ONLINE ($$initRate / GB)"))
                    if (account != null) {
                        pushStateToOrganization(account, "${account.companyName} SYNCED")
                    }

                    while (true) {
                        delay(8.seconds)
                        if (account != null) {
                            val pool = String.format(Locale.US, "%.2f", account.dataPoolMb)
                            val roomNote = account.activeRoomCode?.let { "ROOM $it LIVE" } ?: "STANDBY"
                            send(Frame.Text("${account.companyName} • SHIELD: $pool MB • $roomNote"))
                        } else {
                            send(Frame.Text("MOTHER GLOBAL TARIFF: $$initRate / GB"))
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    allConnectedDashboards.remove(this)
                    account?.dashboardSessions?.remove(this)
                }
            }

            // 6. LIVE ROOM METERING & CUTOFF ENFORCEMENT WEBSOCKET
            webSocket("/ws/room/{code}") {
                val code = call.parameters["code"] ?: ""
                val role = call.request.queryParameters["role"]?.uppercase() ?: "GUEST"
                val room = activeRooms[code]
                val hostAccount = room?.let { hostAccounts[it.hostEmail] }

                if (room == null || !room.isActive || hostAccount == null) {
                    send(Frame.Text("CUTOFF:ROOM_NOT_ACTIVE"))
                    close(CloseReason(CloseReason.Codes.NORMAL, "Room inactive"))
                    return@webSocket
                }

                if (hostAccount.dataPoolMb <= 0.0) {
                    send(Frame.Text("CUTOFF:HOST_DATA_SHIELD_DEPLETED"))
                    close(CloseReason(CloseReason.Codes.NORMAL, "Shield depleted"))
                    return@webSocket
                }

                val participant = RoomParticipant(session = this, role = role)
                room.participants.add(participant)
                broadcastRoomSync(room)

                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            if (text.startsWith("USAGE:")) {
                                val deltaMb = text.removePrefix("USAGE:").toDoubleOrNull() ?: 0.0
                                processReportedDataUsage(room, deltaMb)
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    room.participants.remove(participant)
                    if (role == "HOST" && room.isActive) {
                        enforceRoomCutoff(room, "HOST_LEFT_SECTOR")
                    } else if (room.isActive) {
                        broadcastRoomSync(room)
                    }
                }
            }

            // 7. LIVE TELEMETRY JSON ENDPOINT FOR AUTO-UPDATING COMMAND HOUSE
            get("/api/admin/overview") {
                val orgs = hostAccounts.values.sortedBy { it.companyName }.map { acc ->
                    val roomCode = acc.activeRoomCode
                    val room = roomCode?.let { activeRooms[it] }
                    val guests = room?.participants?.count { it.role == "GUEST" } ?: 0
                    AdminOrgOverview(
                        companyName = acc.companyName,
                        email = acc.email,
                        liquidityFormatted = String.format(Locale.US, "%,.2f", acc.liquidityUsd),
                        dataPoolMbFormatted = String.format(Locale.US, "%.2f", acc.dataPoolMb),
                        activeRoomCode = roomCode ?: "NONE",
                        connectedGuests = guests
                    )
                }
                call.respond(
                    HttpStatusCode.OK,
                    AdminOverviewResponse(
                        autoMintEnabled = isAutoTokenMintingEnabled(),
                        globalTariffPerGbUsd = globalTariffPerGbUsd,
                        connectedDevices = allConnectedDashboards.size,
                        organizations = orgs
                    )
                )
            }

            // 8. PRODUCER MOTHER COMMAND HOUSE (AUTO-UPDATING WEB CONTROL PANEL)
            get("/command-house") {
                val currentPrice = String.format(Locale.US, "%.2f", globalTariffPerGbUsd)
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>FELLOWSHIP • PRODUCER MOTHER COMMAND HOUSE</title>
                        <style>
                            body { background: #04060A; color: #FFFFFF; font-family: Arial, sans-serif; display: flex; justify-content: center; padding: 36px 16px; margin: 0; }
                            .card { background: #0A0F1A; border: 2px solid #00F0FF; border-radius: 24px; padding: 32px; width: 820px; max-width: 100%; box-shadow: 0 0 40px rgba(0, 240, 255, 0.2); }
                            h1 { color: #00F0FF; font-size: 20px; letter-spacing: 4px; margin: 0 0 6px 0; }
                            p { color: #8B9EB7; font-size: 11px; letter-spacing: 2px; margin: 0 0 22px 0; }
                            .banner { background: #04060A; border: 1px solid rgba(255,255,255,0.12); border-radius: 14px; padding: 14px 18px; margin-bottom: 22px; font-size: 12px; display: flex; justify-content: space-between; flex-wrap: wrap; gap: 10px; }
                            label { display: block; font-size: 11px; color: #00F0FF; letter-spacing: 2px; margin-bottom: 8px; }
                            .row { display: flex; gap: 12px; margin-bottom: 18px; }
                            input { flex: 1; padding: 14px; border-radius: 12px; border: 1px solid #00F0FF; background: #04060A; color: #FFFFFF; font-size: 18px; font-weight: bold; }
                            button { padding: 14px 22px; border-radius: 30px; border: none; background: linear-gradient(90deg, #00F0FF, #0038FF); color: #04060A; font-weight: 900; letter-spacing: 1.5px; cursor: pointer; font-size: 12px; }
                            .mini-danger { background: transparent; border: 1px solid #FF3366; color: #FF3366; padding: 8px 12px; font-size: 10px; border-radius: 18px; cursor: pointer; }
                            table { width: 100%; border-collapse: collapse; margin-top: 16px; font-size: 12px; }
                            th, td { text-align: left; padding: 12px 10px; border-bottom: 1px solid rgba(255,255,255,0.08); }
                            th { color: #8B9EB7; font-size: 10px; letter-spacing: 2px; }
                            .status { margin-top: 14px; font-size: 12px; color: #00E676; font-weight: bold; letter-spacing: 1px; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h1>MOTHER COMMAND HOUSE</h1>
                            <p>GLOBAL PRODUCER CONTROL • AUTO-UPDATING LIVE TELEMETRY (4S)</p>
                            <div class="banner">
                                <div id="tokenEngineBadge">AGORA TOKEN ENGINE: LOADING...</div>
                                <div id="countsBadge">ORGANIZATIONS: 0 | LIVE DEVICES: 0</div>
                            </div>
                            <label>GLOBAL PRICE PER 1 GB SPONSORED POOL (USD) — BROADCASTS TO ALL CHILD APPS</label>
                            <div class="row">
                                <input type="number" id="tariffInput" value="$currentPrice" step="100000" />
                                <button onclick="broadcastTariff()">BROADCAST GLOBALLY</button>
                            </div>
                            <div class="status" id="statusText">MAINFRAME ARMED • AUTO-PERSISTENCE ACTIVE</div>
                            <table>
                                <thead>
                                    <tr>
                                        <th>ORGANIZATION</th>
                                        <th>VAULT LIQUIDITY</th>
                                        <th>DATA SHIELD</th>
                                        <th>ACTIVE ROOM</th>
                                        <th>GUESTS</th>
                                        <th>ENFORCEMENT</th>
                                    </tr>
                                </thead>
                                <tbody id="orgTableBody"></tbody>
                            </table>
                        </div>
                        <script>
                            async function refreshOverview() {
                                try {
                                    const res = await fetch('/api/admin/overview');
                                    const data = await res.json();
                                    const badge = data.autoMintEnabled
                                        ? '<span style="color:#00E676;font-weight:bold">AUTO-MINT ACTIVE (007)</span>'
                                        : '<span style="color:#FFB300;font-weight:bold">FALLBACK TEMP TOKEN (ADD APP CERTIFICATE)</span>';
                                    document.getElementById('tokenEngineBadge').innerHTML = 'AGORA TOKEN ENGINE: ' + badge;
                                    document.getElementById('countsBadge').innerHTML =
                                        'ORGANIZATIONS: <b style="color:#00F0FF">' + data.organizations.length +
                                        '</b>  |  LIVE DEVICES: <b style="color:#00E676">' + data.connectedDevices + '</b>';

                                    let rowsHtml = '';
                                    for (const org of data.organizations) {
                                        rowsHtml += '<tr>' +
                                            '<td><b>' + org.companyName + '</b><br/><small style="color:#8B9EB7">' + org.email + '</small></td>' +
                                            '<td>$' + org.liquidityFormatted + '</td>' +
                                            '<td style="color:#00E676;font-weight:bold">' + org.dataPoolMbFormatted + ' MB</td>' +
                                            '<td style="color:#00F0FF;font-weight:bold">' + org.activeRoomCode + '</td>' +
                                            '<td style="color:#FFFFFF;font-weight:bold">' + org.connectedGuests + '</td>' +
                                            '<td><button class="mini-danger" onclick="drainOrg(\'' + org.email + '\')">TEST 0 MB CUTOFF</button></td>' +
                                            '</tr>';
                                    }
                                    document.getElementById('orgTableBody').innerHTML = rowsHtml;
                                } catch (e) {}
                            }

                            async function broadcastTariff() {
                                const val = document.getElementById('tariffInput').value;
                                const res = await fetch('/api/set-tariff', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({ pricePerGb: parseFloat(val) })
                                });
                                const data = await res.json();
                                document.getElementById('statusText').innerText = data.message;
                                refreshOverview();
                            }

                            async function drainOrg(email) {
                                const res = await fetch('/api/test-drain', {
                                    method: 'POST',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({ email: email })
                                });
                                const data = await res.json();
                                document.getElementById('statusText').innerText = data.message;
                                refreshOverview();
                            }

                            refreshOverview();
                            setInterval(refreshOverview, 4000);
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                call.respondText(html, ContentType.Text.Html)
            }

            post("/api/set-tariff") {
                val req = call.receive<TariffRequest>()
                if (req.pricePerGb >= 0.0) {
                    broadcastTariffToAllOrganizations(req.pricePerGb)
                    val formatted = String.format(Locale.US, "%,.2f", req.pricePerGb)
                    call.respond(HttpStatusCode.OK, MessageResponse("GLOBAL TARIFF BROADCASTED: $$formatted / GB TO ${allConnectedDashboards.size} DEVICES"))
                } else {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("INVALID TARIFF"))
                }
            }

            post("/api/test-drain") {
                val req = call.receive<DrainOrgRequest>()
                val account = hostAccounts[req.email.trim().lowercase(Locale.US)]
                if (account == null) {
                    call.respond(HttpStatusCode.NotFound, MessageResponse("ORGANIZATION NOT FOUND"))
                    return@post
                }
                synchronized(account) {
                    account.dataPoolMb = 0.0
                }
                saveMainframeStateToDisk()
                val liveRoom = account.activeRoomCode?.let { activeRooms[it] }
                if (liveRoom != null && liveRoom.isActive) {
                    broadcastRoomSync(liveRoom)
                    enforceRoomCutoff(liveRoom, "HOST_DATA_SHIELD_DEPLETED")
                    call.respond(HttpStatusCode.OK, MessageResponse("${account.companyName} DRAINED TO 0 MB • ROOM ${liveRoom.code} CUT OFF!"))
                } else {
                    pushStateToOrganization(account, "SHIELD DRAINED TO 0.00 MB (TEST)")
                    call.respond(HttpStatusCode.OK, MessageResponse("${account.companyName} SHIELD DRAINED TO 0.00 MB"))
                }
            }
        }
    }.start(wait = true)
}

@Serializable data class MessageResponse(val message: String)
@Serializable data class RegisterRequest(val companyName: String, val email: String, val password: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class LoginResponse(val token: String, val message: String)
@Serializable data class BalanceResponse(val balance: String)
@Serializable data class TariffRequest(val pricePerGb: Double)
@Serializable data class DrainOrgRequest(val email: String)

@Serializable
data class HostStateResponse(
    val email: String,
    val companyName: String,
    val liquidityUsd: Double,
    val tariffPerGbUsd: Double,
    val dataPoolMb: Double,
    val activeRoomCode: String?,
    val purchaseHistory: List<ServerPurchaseLog>
)

@Serializable
data class CreateRoomResponse(
    val roomCode: String,
    val channelName: String,
    val agoraToken: String,
    val hostDataPoolMb: Double,
    val tokenMode: String
)

@Serializable data class JoinRoomRequest(val roomCode: String)

@Serializable
data class JoinRoomResponse(
    val allowed: Boolean,
    val channelName: String,
    val agoraToken: String,
    val hostDataPoolMb: Double,
    val hostCompany: String,
    val message: String
)

@Serializable data class CloseRoomRequest(val roomCode: String)

@Serializable
data class AdminOrgOverview(
    val companyName: String,
    val email: String,
    val liquidityFormatted: String,
    val dataPoolMbFormatted: String,
    val activeRoomCode: String,
    val connectedGuests: Int
)

@Serializable
data class AdminOverviewResponse(
    val autoMintEnabled: Boolean,
    val globalTariffPerGbUsd: Double,
    val connectedDevices: Int,
    val organizations: List<AdminOrgOverview>
)