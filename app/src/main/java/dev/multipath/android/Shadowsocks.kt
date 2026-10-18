package dev.multipath.android

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypted client for a standard Shadowsocks (AEAD, aes-256-gcm) server, TCP only. */
object Shadowsocks {
    const val SALT_SIZE = 32
    const val TAG_SIZE = 16
    const val MAX_CHUNK = 0x3FFF

    /** Password to 32-byte master key (OpenSSL EVP_BytesToKey with MD5, as Shadowsocks defines). */
    fun masterKey(password: String): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        val pw = password.toByteArray(Charsets.UTF_8)
        var prev = ByteArray(0)
        val out = ByteArrayOutputStream()
        while (out.size() < 32) {
            md.update(prev)
            md.update(pw)
            prev = md.digest()
            out.write(prev)
        }
        return out.toByteArray().copyOf(32)
    }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** HKDF-SHA1 with info "ss-subkey". */
    fun subKey(master: ByteArray, salt: ByteArray): ByteArray {
        val prk = hmac(salt, master)
        val info = "ss-subkey".toByteArray(Charsets.UTF_8)
        val t1 = hmac(prk, info, byteArrayOf(1))
        val t2 = hmac(prk, t1, info, byteArrayOf(2))
        return (t1 + t2).copyOf(32)
    }

    class Aead(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        private val nonce = ByteArray(12)

        private fun next(): ByteArray {
            val current = nonce.clone()
            var i = 0
            while (i < nonce.size) {
                nonce[i] = (nonce[i] + 1).toByte()
                if (nonce[i] != 0.toByte()) break
                i++
            }
            return current
        }

        fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, next()))
            return cipher.doFinal(plain)
        }

        fun open(data: ByteArray): ByteArray {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, next()))
                return cipher.doFinal(data)
            } catch (error: GeneralSecurityException) {
                throw IOException("Server reply could not be decrypted", error)
            }
        }
    }

    class Output(private val out: OutputStream, master: ByteArray) {
        private val aead: Aead

        init {
            val salt = ByteArray(SALT_SIZE)
            SecureRandom().nextBytes(salt)
            out.write(salt)
            aead = Aead(subKey(master, salt))
        }

        @Synchronized
        fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
            var position = offset
            var left = length
            while (left > 0) {
                val n = minOf(left, MAX_CHUNK)
                val header = byteArrayOf((n shr 8).toByte(), n.toByte())
                out.write(aead.seal(header) + aead.seal(data.copyOfRange(position, position + n)))
                position += n
                left -= n
            }
            out.flush()
        }
    }

    class Input(private val input: InputStream, private val master: ByteArray) {
        private var aead: Aead? = null
        private var pending = ByteArray(0)
        private var position = 0

        private fun readBlock(n: Int): ByteArray? {
            val block = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(block, got, n - got)
                if (r < 0) {
                    if (got == 0) return null
                    throw IOException("Connection cut short")
                }
                got += r
            }
            return block
        }

        /** Returns -1 at the end of the stream. */
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            var cipher = aead
            if (cipher == null) {
                val salt = readBlock(SALT_SIZE) ?: return -1
                cipher = Aead(subKey(master, salt))
                aead = cipher
            }
            if (position >= pending.size) {
                val header = cipher.open(readBlock(2 + TAG_SIZE) ?: return -1)
                val n = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                if (n > MAX_CHUNK) throw IOException("Bad data from server")
                pending = cipher.open(readBlock(n + TAG_SIZE) ?: throw IOException("Connection cut short"))
                position = 0
            }
            val count = minOf(length, pending.size - position)
            System.arraycopy(pending, position, buffer, offset, count)
            position += count
            return count
        }

        fun readExact(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = read(out, got, n - got)
                if (r < 0) throw IOException("Connection cut short")
                got += r
            }
            return out
        }
    }

    class Tunnel(val socket: Socket, val input: Input, val output: Output)

    private fun addressHeader(ip: Int, host: String?, port: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (host != null) {
            val bytes = host.toByteArray(Charsets.UTF_8)
            out.write(3)
            out.write(bytes.size)
            out.write(bytes)
        } else {
            out.write(1)
            out.write(byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte()))
        }
        out.write(port shr 8)
        out.write(port and 0xFF)
        return out.toByteArray()
    }

    /** Opens an encrypted connection to the exit server over [network] and asks it to reach the target. */
    @Throws(IOException::class)
    fun open(network: Network, config: ExitServer.Config, targetIp: Int, targetHost: String?, targetPort: Int): Tunnel {
        val socket = Socket()
        try {
            network.bindSocket(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(network.getByName(config.host), config.port), 8_000)
            val output = Output(socket.getOutputStream(), config.master)
            output.write(addressHeader(targetIp, targetHost, targetPort))
            return Tunnel(socket, Input(socket.getInputStream(), config.master), output)
        } catch (error: Exception) {
            try { socket.close() } catch (_: IOException) { }
            throw if (error is IOException) error else IOException(error.message, error)
        }
    }

    /** Asks the exit server for the public address websites will see. */
    @Throws(IOException::class)
    fun lookupExitAddress(network: Network, config: ExitServer.Config): String {
        val tunnel = open(network, config, 0, "api.ipify.org", 80)
        try {
            tunnel.socket.soTimeout = 8_000
            tunnel.output.write("GET / HTTP/1.0\r\nHost: api.ipify.org\r\nUser-Agent: MultiPath\r\n\r\n".toByteArray())
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (body.size() < 4096) {
                val n = tunnel.input.read(buffer, 0, buffer.size)
                if (n < 0) break
                body.write(buffer, 0, n)
            }
            val text = String(body.toByteArray(), Charsets.UTF_8)
            val address = text.substringAfter("\r\n\r\n", "").trim()
            if (address.isEmpty() || address.length > 45) throw IOException("No answer from the server")
            return address
        } finally {
            try { tunnel.socket.close() } catch (_: IOException) { }
        }
    }
}
