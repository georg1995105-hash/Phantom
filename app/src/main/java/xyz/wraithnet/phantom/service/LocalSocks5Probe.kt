package xyz.wraithnet.phantom.service

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.EOFException

/**
 * Tests the actual local SOCKS5 negotiation, not merely a successful TCP SYN.
 * Does not contact a proxy server or send any VPN credentials.
 */
internal object LocalSocks5Probe {
    data class Result(val ready: Boolean, val reason: String)

    fun check(port: Int): Result {
        if (port !in 1024..65535) return Result(false, "неверный локальный порт")
        var stage = "TCP"
        return try {
            Socket().use { socket ->
                socket.soTimeout = 1200
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                stage = "SOCKS5"
                val out = socket.getOutputStream()
                out.write(byteArrayOf(5, 1, 0))
                out.flush()
                val input = socket.getInputStream()
                val version = input.read()
                val method = input.read()
                check(version == 5 && method == 0) {
                    if (version < 0 || method < 0) "SOCKS5 закрыл соединение"
                    else "SOCKS5 отклонил согласование"
                }
            }
            Result(true, "")
        } catch (e: Exception) {
            val type = when (e) {
                is ConnectException -> "соединение отклонено"
                is SocketTimeoutException -> "тайм-аут ответа"
                is NoRouteToHostException -> "нет маршрута к 127.0.0.1"
                is SecurityException -> "запрещено Android"
                is EOFException -> "соединение закрыто"
                is SocketException -> "сокет закрыт или сброшен"
                else -> "не удалось согласовать SOCKS5 (${e.javaClass.simpleName})"
            }
            Result(false, "$stage: $type")
        }
    }
}
