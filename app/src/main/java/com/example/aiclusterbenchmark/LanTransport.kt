package com.example.aiclusterbenchmark

import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.*
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Fixed-size synthetic traffic only. No file access or remote execution. */
internal object LanWire {
    const val BYTES=16*1024*1024
    const val MAGIC="AICLAN1"
    val block=ByteArray(64*1024) { ((it*31+17) and 255).toByte() }
    fun proof(code:String,nonce:ByteArray):ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII),"HmacSHA256")); doFinal(nonce)
    }
    fun local(address:InetAddress)=address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress
    fun send(out:DataOutputStream) {
        val crc=CRC32(); out.writeInt(BYTES)
        repeat(BYTES/block.size) { out.write(block); crc.update(block) }
        out.writeLong(crc.value); out.flush()
    }
    fun receive(input:DataInputStream):Long {
        check(input.readInt()==BYTES) { "Неверный размер тестовых данных" }
        val crc=CRC32(); val buffer=ByteArray(block.size)
        repeat(BYTES/buffer.size) { input.readFully(buffer); crc.update(buffer) }
        check(input.readLong()==crc.value) { "Ошибка контрольной суммы" }
        return crc.value
    }
}

internal data class LanResult(val peer:String,val latencyMs:Double,val downloadMbps:Double,val uploadMbps:Double,val downloadCrc:Long,val uploadCrc:Long)

internal class LanServer(address:InetAddress,val code:String,private val name:String,private val status:(String)->Unit):Closeable {
    private val listener=ServerSocket(0,1,address)
    val port get()=listener.localPort
    private val worker=Executors.newSingleThreadExecutor()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    @Volatile private var active:Socket?=null
    @Volatile private var closed=false
    fun start() { worker.execute {
        var failures=0
        try {
            while(!closed) {
                val socket=listener.accept(); active=socket
                if(closed) { socket.close(); break }
                val expiry=timer.schedule({ runCatching { socket.close() } },60,TimeUnit.SECONDS)
                try { socket.use {
                    check(LanWire.local(it.inetAddress)) { "Подключение вне локальной сети" }
                    it.soTimeout=5000; it.tcpNoDelay=true
                    val input=DataInputStream(BufferedInputStream(it.getInputStream()))
                    val output=DataOutputStream(BufferedOutputStream(it.getOutputStream()))
                    check(input.readUTF()==LanWire.MAGIC) { "Несовместимый протокол" }
                    val nonce=ByteArray(16).also(SecureRandom()::nextBytes)
                    output.write(nonce); output.flush()
                    val proof=ByteArray(32); input.readFully(proof)
                    if(!MessageDigest.isEqual(proof,LanWire.proof(code,nonce))) {
                        failures++; output.writeBoolean(false); output.flush()
                        if(failures>=5) { status("Пять неверных кодов. Остановите и заново включите приём."); close() }
                        return@use
                    }
                    output.writeBoolean(true); output.writeUTF(name.take(80)); output.flush()
                    status("Телефон подключён. Выполняется тест передачи…")
                    repeat(8) { check(input.readByte().toInt()==1); output.writeByte(1); output.flush() }
                    check(input.readByte().toInt()==2); LanWire.send(output)
                    check(input.readByte().toInt()==3); LanWire.receive(input)
                    output.writeBoolean(true); output.flush()
                    status("Тест завершён. Результат сохраните на втором телефоне.")
                } } catch(e:Exception) { if(!closed) status("Соединение прервано: ${e.message}. Ожидаю повторное подключение.") }
                finally { expiry.cancel(false); active=null }
            }
        } catch(e:Exception) { if(!closed) status("Приём остановлен: ${e.message}") }
    } }
    override fun close() {
        closed=true; runCatching { listener.close() }; runCatching { active?.close() }
        worker.shutdownNow(); timer.shutdownNow()
    }
}

internal class LanClient:Closeable {
    private val socket=Socket()
    private val timer=Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed=false
    fun test(address:InetAddress,port:Int,code:String,progress:(String)->Unit):LanResult {
        check(!closed) { "Тест отменён" }
        require(LanWire.local(address)) { "Нужен локальный IP-адрес" }
        require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "Проверьте порт и шестизначный код" }
        val expiry=timer.schedule({ close() },60,TimeUnit.SECONDS)
        try {
            socket.connect(InetSocketAddress(address,port),5000); socket.soTimeout=5000; socket.tcpNoDelay=true
            val input=DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output=DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            output.writeUTF(LanWire.MAGIC); output.flush()
            val nonce=ByteArray(16); input.readFully(nonce)
            output.write(LanWire.proof(code,nonce)); output.flush()
            check(input.readBoolean()) { "Неверный код подключения" }
            val peer=input.readUTF().take(80)
            progress("Измеряю задержку…")
            val pings=List(8) {
                val t=System.nanoTime(); output.writeByte(1); output.flush(); check(input.readByte().toInt()==1)
                (System.nanoTime()-t)/1e6
            }.sorted()
            progress("Приём 16 МиБ со второго телефона…")
            var t=System.nanoTime(); output.writeByte(2); output.flush()
            val downCrc=LanWire.receive(input); val down=(System.nanoTime()-t)/1e9
            progress("Отправка 16 МиБ на второй телефон…")
            t=System.nanoTime(); output.writeByte(3); LanWire.send(output)
            check(input.readBoolean()) { "Второй телефон не подтвердил приём" }
            val up=(System.nanoTime()-t)/1e9
            val crc=CRC32().apply { repeat(LanWire.BYTES/LanWire.block.size) { update(LanWire.block) } }.value
            return LanResult(peer,(pings[3]+pings[4])/2,LanWire.BYTES*8/down/1e6,LanWire.BYTES*8/up/1e6,downCrc,crc)
        } finally { expiry.cancel(false); close() }
    }
    override fun close() { closed=true; runCatching { socket.close() }; timer.shutdownNow() }
}
