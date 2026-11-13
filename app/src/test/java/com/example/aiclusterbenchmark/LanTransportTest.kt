package com.example.aiclusterbenchmark

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*

class LanTransportTest {
    private val loopback=InetAddress.getByName("127.0.0.1")
    @Test fun authenticatedRoundTrip() {
        LanServer(loopback,"123456","test-peer") {}.use { server ->
            server.start()
            val result=LanClient().use { it.test(loopback,server.port,"123456") {} }
            assertEquals("test-peer",result.peer)
            assertTrue(result.downloadMbps>0 && result.uploadMbps>0 && result.latencyMs>=0)
            assertEquals(result.downloadCrc,result.uploadCrc)
        }
    }
    @Test fun wrongCodeCannotStartTransfer() {
        LanServer(loopback,"123456","test-peer") {}.use { server ->
            server.start()
            val error=runCatching { LanClient().use { it.test(loopback,server.port,"999999") {} } }.exceptionOrNull()
            assertNotNull(error); assertTrue(error!!.message!!.contains("Неверный код"))
            // An incorrect code must not poison a later legitimate connection.
            assertEquals("test-peer",LanClient().use { it.test(loopback,server.port,"123456") {} }.peer)
        }
    }
    @Test fun invalidSizesAndTruncatedPayloadAreRejected() {
        val bytes=ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }.toByteArray()
        assertTrue(runCatching { LanWire.receive(DataInputStream(ByteArrayInputStream(bytes))) }.isFailure)
        val short=ByteArrayOutputStream().also { DataOutputStream(it).writeInt(LanWire.BYTES) }.toByteArray()
        assertTrue(runCatching { LanWire.receive(DataInputStream(ByteArrayInputStream(short))) }.exceptionOrNull() is EOFException)
    }
    @Test fun corruptedChecksumIsRejected() {
        val encoded=ByteArrayOutputStream(); LanWire.send(DataOutputStream(encoded))
        val bytes=encoded.toByteArray(); bytes[20]=(bytes[20].toInt() xor 1).toByte()
        assertTrue(runCatching { LanWire.receive(DataInputStream(ByteArrayInputStream(bytes))) }.exceptionOrNull()!!.message!!.contains("контрольной суммы"))
    }
    @Test fun cancellationClosesBlockedHandshake() {
        val pool=Executors.newSingleThreadExecutor()
        ServerSocket(0,1,loopback).use { listener ->
            val client=LanClient()
            val future=pool.submit<Boolean> { runCatching { client.test(loopback,listener.localPort,"123456") {} }.isFailure }
            listener.accept().use { client.close(); assertTrue(future.get(3,TimeUnit.SECONDS)) }
        }
        pool.shutdownNow()
    }
}
