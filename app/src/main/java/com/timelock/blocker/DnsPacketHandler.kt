package com.timelock.blocker

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class DnsPacketHandler(
    private val blockedRepo: BlockedWebsitesRepository,
    private val upstreamDnsIp: String = "1.1.1.1"
) {

    data class ParsedDnsQuery(
        val transactionId: Short,
        val domain: String,
        val qType: Int,
        val qClass: Int,
        val rawDnsPayload: ByteArray
    )

    fun parseQuery(dnsPayload: ByteArray): ParsedDnsQuery? {
        if (dnsPayload.size < 12) return null
        val buffer = ByteBuffer.wrap(dnsPayload)

        val txId = buffer.short
        val flags = buffer.short
        val qdCount = buffer.short
        buffer.short
        buffer.short
        buffer.short

        val isQuery = (flags.toInt() and 0x8000) == 0
        if (!isQuery || qdCount <= 0) return null

        val domainBuilder = StringBuilder()
        while (buffer.hasRemaining()) {
            val length = buffer.get().toInt() and 0xFF
            if (length == 0) break

            if ((length and 0xC0) == 0xC0) {
                buffer.get()
                break
            }

            if (buffer.remaining() < length) return null

            val labelBytes = ByteArray(length)
            buffer.get(labelBytes)
            if (domainBuilder.isNotEmpty()) {
                domainBuilder.append(".")
            }
            domainBuilder.append(String(labelBytes, Charsets.US_ASCII))
        }

        if (!buffer.hasRemaining() || buffer.remaining() < 4) return null
        val qType = buffer.short.toInt() and 0xFFFF
        val qClass = buffer.short.toInt() and 0xFFFF

        return ParsedDnsQuery(
            transactionId = txId,
            domain = domainBuilder.toString(),
            qType = qType,
            qClass = qClass,
            rawDnsPayload = dnsPayload
        )
    }

    fun buildSinkholeDnsResponse(parsed: ParsedDnsQuery): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        dos.writeShort(parsed.transactionId.toInt())
        dos.writeShort(0x8580) // QR=1, AA=1, RD=1, RA=1, RCODE=0000
        dos.writeShort(1)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0)

        val questionBytes = parsed.rawDnsPayload.copyOfRange(12, parsed.rawDnsPayload.size)
        dos.write(questionBytes)

        dos.writeShort(0xC00C.toInt())
        dos.writeShort(1)
        dos.writeShort(1)
        dos.writeInt(300)
        dos.writeShort(4)
        dos.write(byteArrayOf(0, 0, 0, 0))

        dos.flush()
        return bos.toByteArray()
    }

    fun forwardUpstream(protectedSocket: DatagramSocket, rawDnsQuery: ByteArray): ByteArray? {
        return try {
            val upstreamAddress = InetAddress.getByName(upstreamDnsIp)
            val sendPacket = DatagramPacket(
                rawDnsQuery,
                rawDnsQuery.size,
                upstreamAddress,
                53
            )
            protectedSocket.send(sendPacket)

            val receiveBuffer = ByteArray(2048)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            protectedSocket.soTimeout = 3000
            protectedSocket.receive(receivePacket)

            receiveBuffer.copyOf(receivePacket.length)
        } catch (e: Exception) {
            null
        }
    }
}