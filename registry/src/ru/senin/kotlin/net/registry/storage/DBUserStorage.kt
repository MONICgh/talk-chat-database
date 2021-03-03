package ru.senin.kotlin.net.registry.storage

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import ru.senin.kotlin.net.Protocol
import ru.senin.kotlin.net.UserAddress
import ru.senin.kotlin.net.UserInfo
import ru.senin.kotlin.net.registry.UserWithoutAddressException
import java.lang.Thread.sleep

class DBUserStorage(
        private val url: String,
        private val driver: String
    ) : UserStorage {

    override fun init() {
        Database.connect(url, driver = driver)
        transaction {
            SchemaUtils.create(Users, Addresses)
        }
    }


    private fun getAddressIdByUsername(username: String): Int? {
        return transaction {
            Users
                    .select { Users.name eq username }
                    .withDistinct()
                    .map { it[Users.addressId] }
                    .first()
        }
    }

    private fun getAddressById(id: Int?): UserAddress {
        if (id == null) {
            throw UserWithoutAddressException()
        }
        return transaction {
            Addresses
                    .select { Addresses.id eq id }
                    .withDistinct()
                    .map { UserAddress(Protocol.valueOf(it[Addresses.protocol]), it[Addresses.host], it[Addresses.port]) }
                    .first()
        }
    }

    override fun getUserList(): List<UserInfo> {
        return transaction {
            Users.selectAll().map {
                    UserInfo(
                            it[Users.name],
                            getAddressById(it[Users.addressId])
                    )
                }
            }
    }

    override fun containsUser(username: String): Boolean {
        return transaction {
            !Users.select { Users.name eq username }.empty()
        }
    }

    private fun insertUser(user: UserInfo) {
        transaction {
            Users.insert { newUser ->
                newUser[name] = user.name
                val newAddress = Addresses.insert {
                    it[protocol] = user.address.protocol.stringValue
                    it[host] = user.address.host
                    it[port] = user.address.port
                }
                newUser[addressId] = newAddress[Addresses.id]
            }
        }
    }

    override fun updateUser(user: UserInfo) {
        if (!containsUser(user.name)) {
            insertUser(user);
            return
        }
        transaction {
            Users.update { newUser ->
                newUser[name] = user.name
                val newAddress = Addresses.update {
                    it[protocol] = user.address.protocol.stringValue
                    it[host] = user.address.host
                    it[port] = user.address.port
                }
                newUser[addressId] = newAddress
            }
        }
    }

    override fun removeUser(name: String) {
        transaction {
            Users.deleteWhere {
                Users.name eq name
            }
        }
    }

    override fun clearStorage() {
        transaction {
            Users.deleteAll()
        }
    }
}