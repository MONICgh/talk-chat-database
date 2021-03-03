package ru.senin.kotlin.net.registry

import com.fasterxml.jackson.databind.SerializationFeature
import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import com.uchuhimo.konf.source.yaml
import io.github.rybalkinsd.kohttp.ext.httpGet
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.netty.*
import okhttp3.Request
import org.jetbrains.exposed.sql.*
import org.slf4j.event.Level
import ru.senin.kotlin.net.Protocol
import ru.senin.kotlin.net.UserInfo
import ru.senin.kotlin.net.checkUserName
import ru.senin.kotlin.net.registry.storage.DBUserStorage
import ru.senin.kotlin.net.registry.storage.MemoryUserStorage
import ru.senin.kotlin.net.registry.storage.UserStorage
import java.lang.Thread.sleep
import java.nio.file.Paths
import kotlin.collections.set
import kotlin.concurrent.thread

object Registry {
    lateinit var users : UserStorage
}

object ServerSpec {
    var dbType: String? = null
    var dbUrl: String? = null
    var dbDriver: String? = null
}

fun main(args: Array<String>) {

    thread {
        sleep(60 * 1000)
        val badRequestCounter: HashMap<UserInfo, Int> = hashMapOf()
        while (true) {
            val removeList = mutableListOf<UserInfo>()
            for (user in Registry.users.getUserList()) {
                var response : Boolean? = null
                if (user.address.protocol == Protocol.HTTP) {
                    response = try {
                        "http://${user.address.host}:${user.address.port}/v1/health".httpGet().isSuccessful
                    } catch (e : Throwable) {
                        null
                    }
                }  else if (user.address.protocol == Protocol.WEBSOCKET) {
                    response = try {
                        Request.Builder()
                            .url("ws://$${user.address.host}:${user.address.port}")
                            .build()
                        true
                    } catch (e : Throwable) {
                        null
                    }
                }
                val now = badRequestCounter[user] ?: 0
                if (response == null || response == false) {
                    badRequestCounter[user] = now + 1
                    if (now > 3) {
                        removeList.add(user)
                        badRequestCounter.remove(user)
                    }
                } else {
                    badRequestCounter[user] = 0
                }
                println(badRequestCounter)
            }
            for (user in removeList) {
                Registry.users.removeUser(user.name)
            }
        }
    }
    EngineMain.main(args)
}

@Suppress("UNUSED_PARAMETER")
@JvmOverloads
fun Application.module(testing: Boolean = false) {
    println("Hello")
    ServerSpec.dbType = environment.config.propertyOrNull("ktor.database.dbType")?.getString()
    ServerSpec.dbDriver = environment.config.propertyOrNull("ktor.database.dbDriver")?.getString()
    ServerSpec.dbUrl = environment.config.propertyOrNull("ktor.database.dbUrl")?.getString()

    if (ServerSpec.dbType == "database") {
        if (ServerSpec.dbUrl != null && ServerSpec.dbDriver != null) {
            Registry.users = DBUserStorage(ServerSpec.dbUrl!!, ServerSpec.dbDriver!!)
        }
    } else {
        Registry.users = MemoryUserStorage()
    }

    Registry.users.init()

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { cause ->
            call.respond(HttpStatusCode.BadRequest, cause.message ?: "invalid argument")
        }
        exception<UserAlreadyRegisteredException> { cause ->
            call.respond(HttpStatusCode.Conflict, cause.message ?: "user already registered")
        }
        exception<IllegalUserNameException> { cause ->
            call.respond(HttpStatusCode.BadRequest, cause.message ?: "illegal user name")
        }
    }
    routing {
        get("/v1/health") {
            call.respondText("OK", contentType = ContentType.Text.Plain)
        }

        post("/v1/users") {
            val user = call.receive<UserInfo>()
            val name = user.name
            checkUserName(name) ?: throw IllegalUserNameException()
            if (Registry.users.containsUser(name)) {
                throw UserAlreadyRegisteredException()
            }
            Registry.users.updateUser(user)
            call.respond(mapOf("status" to "ok"))
        }

        get("/v1/users") {
            call.respond(Registry.users.getUserList().map { it.name to it.address }.toMap())
        }

        put("/v1/users/{user}") {
            val user = call.receive<UserInfo>()

            if (!Registry.users.containsUser(user.name)) {
                throw UserNotRegisteredException()
            }
            checkUserName(user.name) ?: throw IllegalUserNameException()
            Registry.users.updateUser(user)
            call.respond(mapOf("status" to "ok"))
        }

        delete("/v1/users/{user}") {
            val name = call.parameters["user"] ?: throw UserNotRegisteredException()

            checkUserName(name) ?: throw IllegalUserNameException()
            Registry.users.removeUser(name)
            call.respond(mapOf("status" to "ok"))
        }
    }
}

class UserAlreadyRegisteredException: RuntimeException("User already registered")
class UserNotRegisteredException: RuntimeException("User not registered")
class IllegalUserNameException: RuntimeException("Illegal user name")
class UserWithoutAddressException: RuntimeException("User address not set in database")