package com.example.aiclusterbenchmark

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

class LanActivity:Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private val nsd get()=getSystemService(NSD_SERVICE) as NsdManager
    private var server:LanServer?=null
    private var client:LanClient?=null
    private var registration:NsdManager.RegistrationListener?=null
    private var discovery:NsdManager.DiscoveryListener?=null
    private val found=LinkedHashMap<String,NsdServiceInfo>()
    private var report:JSONObject?=null
    private var busy=false
    private var active=false
    private var generation=0
    private lateinit var host:Button
    private lateinit var search:Button
    private lateinit var start:Button
    private lateinit var stop:Button
    private lateinit var save:Button
    private lateinit var peers:LinearLayout
    private lateinit var endpoint:EditText
    private lateinit var code:EditText
    private lateinit var hostInfo:TextView
    private lateinit var output:TextView
    private lateinit var progress:ProgressBar
    private val serviceType="_aicluster._tcp."
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=Ui.page(this,"Телефоны\nв одной сети","Проверь связь перед объединением вычислений. Подключи оба телефона к одному Wi-Fi.")
        root.addView(Ui.button(this,"‹  Все тесты").apply { setOnClickListener { finish() } })
        Ui.gap(this,root)
        val first=Ui.card(this,root,"1. На первом телефоне","Включи приём и оставь этот экран открытым. Передай адрес и код второму телефону.")
        host=Ui.button(this,"Включить приём",true); first.addView(host)
        Ui.gap(this,first)
        hostInfo=Ui.text(this,"Приём выключен",15f,Ui.muted).apply { setTextIsSelectable(true) }; first.addView(hostInfo)
        Ui.gap(this,root)
        val second=Ui.card(this,root,"2. На втором телефоне","Найди первый телефон или введи его адрес и код. Передаются только тестовые данные — по 16 МиБ в каждую сторону.")
        search=Ui.button(this,"Найти телефоны"); second.addView(search)
        peers=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; second.addView(peers)
        Ui.gap(this,second)
        endpoint=EditText(this).apply { hint="Адрес: 192.168.1.10:12345"; setTextColor(Ui.ink); setHintTextColor(Ui.muted); textSize=15f; isSingleLine=true; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        second.addView(endpoint)
        code=EditText(this).apply { hint="Код из 6 цифр"; setTextColor(Ui.ink); setHintTextColor(Ui.muted); inputType=InputType.TYPE_CLASS_NUMBER; isSingleLine=true; filters=arrayOf(android.text.InputFilter.LengthFilter(6)) }
        second.addView(code); Ui.gap(this,second)
        start=Ui.button(this,"Проверить соединение",true); second.addView(start)
        Ui.gap(this,second,8)
        stop=Ui.button(this,"Остановить связь").apply { isEnabled=false }; second.addView(stop)
        Ui.gap(this,root)
        val result=Ui.card(this,root,"Результат сети")
        progress=ProgressBar(this).apply { visibility=View.GONE }; result.addView(progress,LinearLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32)))
        output=Ui.text(this,"Здесь появятся задержка и скорость передачи. Это проверка связи, а не распределённый запуск нейросети.",15f,Ui.muted); result.addView(output)
        Ui.gap(this,result)
        save=Ui.button(this,"Сохранить результат · JSON").apply { isEnabled=false }; result.addView(save)
        report=state?.getString("report")?.let(::JSONObject); report?.let { showReport(it) }; refresh()
        host.setOnClickListener { startHost() }; search.setOnClickListener { startSearch() }
        start.setOnClickListener { runTest() }; stop.setOnClickListener { stopAll(); output.text="Связь остановлена" }
        save.setOnClickListener { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="application/json"; putExtra(Intent.EXTRA_TITLE,"lan-benchmark-${System.currentTimeMillis()}.json")
        },1) }
    }
    override fun onStart() { super.onStart(); active=true }
    private fun ui(epoch:Int=generation,action:()->Unit) { runOnUiThread { if(active && epoch==generation) action() } }
    private fun refresh() {
        host.isEnabled=server==null && !busy
        search.isEnabled=server==null && !busy && discovery==null
        start.isEnabled=server==null && !busy
        stop.isEnabled=server!=null || busy || discovery!=null
        endpoint.isEnabled=server==null && !busy; code.isEnabled=server==null && !busy
        save.isEnabled=report!=null && !busy
        progress.visibility=if(busy) View.VISIBLE else View.GONE
        if(server!=null || busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun wifiAddress():InetAddress {
        val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.forEach { network ->
            if(cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true) {
                cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }?.let { return it.address }
            }
        }
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }.forEach { network ->
            network.inetAddresses.toList().firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.let { return it }
        }
        error("Не найден локальный Wi-Fi адрес. Подключите телефон к Wi-Fi или включите точку доступа.")
    }
    private fun startHost() {
        stopAll(); busy=true; refresh(); val epoch=generation
        worker.execute {
            try {
                val address=wifiAddress(); val pin=String.format(Locale.US,"%06d",SecureRandom().nextInt(1000000))
                val receiver=LanServer(address,pin,"${Build.MANUFACTURER} ${Build.MODEL}") { message -> ui(epoch) { output.text=message } }
                runOnUiThread {
                    if(!active || epoch!=generation) { receiver.close(); return@runOnUiThread }
                    server=receiver; receiver.start(); busy=false
                    hostInfo.text="Адрес: ${address.hostAddress}:${receiver.port}\nКод подключения: $pin"
                    output.text="Ожидаю второй телефон. Код действует до остановки приёма."
                    register(receiver.port,epoch); refresh()
                }
            } catch(e:Exception) { ui(epoch) { busy=false; output.text="Не удалось включить приём: ${e.message}"; refresh() } }
        }
    }
    private fun register(port:Int,epoch:Int) {
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo) {}
            override fun onRegistrationFailed(info:NsdServiceInfo,error:Int) { ui(epoch) { output.text="Автопоиск недоступен ($error). Подключитесь по адресу выше." } }
            override fun onServiceUnregistered(info:NsdServiceInfo) {}
            override fun onUnregistrationFailed(info:NsdServiceInfo,error:Int) {}
        }
        registration=listener
        try { nsd.registerService(NsdServiceInfo().apply { serviceName="AICluster-${Build.MODEL.take(30)}"; serviceType=this@LanActivity.serviceType; setPort(port) },NsdManager.PROTOCOL_DNS_SD,listener) }
        catch(e:Exception) { output.text="Приём работает. Автопоиск недоступен — используйте адрес выше." }
    }
    private fun startSearch() {
        val epoch=generation; found.clear(); peers.removeAllViews()
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String) { ui(epoch) { output.text="Ищу телефоны в сети… Выберите найденный телефон. Если список пуст, введите адрес вручную." } }
            override fun onDiscoveryStopped(type:String) {}
            override fun onStartDiscoveryFailed(type:String,error:Int) { ui(epoch) { stopDiscovery(); output.text="Поиск недоступен ($error). Введите адрес вручную."; refresh() } }
            override fun onStopDiscoveryFailed(type:String,error:Int) {}
            override fun onServiceFound(info:NsdServiceInfo) { ui(epoch) { if(discovery!=null && found.size<32) { found[info.serviceName]=info; renderPeers() } } }
            override fun onServiceLost(info:NsdServiceInfo) { ui(epoch) { found.remove(info.serviceName); renderPeers() } }
        }
        discovery=listener; refresh()
        try { nsd.discoverServices(serviceType,NsdManager.PROTOCOL_DNS_SD,listener) }
        catch(e:Exception) { discovery=null; output.text="Ошибка поиска: ${e.message}"; refresh() }
    }
    private fun renderPeers() {
        peers.removeAllViews()
        found.values.forEach { info -> peers.addView(Ui.button(this,info.serviceName.take(60)).apply {
            setOnClickListener {
                val epoch=generation
                nsd.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(service:NsdServiceInfo,error:Int) { ui(epoch) { output.text="Адрес не получен ($error). Попробуйте ещё раз или введите вручную." } }
                    override fun onServiceResolved(service:NsdServiceInfo) { ui(epoch) {
                        val address=service.host
                        if(address is Inet4Address && address.isSiteLocalAddress) { endpoint.setText("${address.hostAddress}:${service.port}"); output.text="Адрес выбран. Введите код с первого телефона." }
                        else output.text="Не получен локальный IPv4-адрес. Введите адрес с первого телефона вручную."
                    } }
                })
            }
        }) }
    }
    private fun stopDiscovery() { discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discovery=null }
    private fun runTest() {
        val parts=endpoint.text.toString().trim().split(':'); val pin=code.text.toString()
        if(parts.size!=2 || !parts[0].matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) || parts[1].toIntOrNull() !in 1..65535 || !pin.matches(Regex("[0-9]{6}"))) {
            output.text="Введите адрес вида 192.168.1.10:12345 и код из 6 цифр."; return
        }
        stopDiscovery(); busy=true; report=null; refresh(); val epoch=generation
        val connection=LanClient(); client=connection; output.text="Подключаюсь…"
        worker.execute {
            try {
                val result=connection.test(InetAddress.getByName(parts[0]),parts[1].toInt(),pin) { message -> ui(epoch) { output.text=message } }
                val json=JSONObject().apply {
                    put("benchmark_version","0.3"); put("benchmark_type","lan"); put("status","completed")
                    put("device","${Build.MANUFACTURER} ${Build.MODEL}"); put("peer",result.peer)
                    put("peer_address",parts[0]); put("timestamp_ms",System.currentTimeMillis()); put("protocol",LanWire.MAGIC)
                    put("bytes_each_direction",LanWire.BYTES); put("ping_samples",8); put("latency_median_ms",result.latencyMs)
                    put("download_mbps",result.downloadMbps); put("upload_mbps",result.uploadMbps)
                    put("download_crc32",result.downloadCrc); put("upload_crc32",result.uploadCrc)
                    put("timing_note","TCP application throughput with buffering and CRC32. Download includes request; upload includes receiver acknowledgment. RTT includes app scheduling. Not radio link speed.")
                }
                ui(epoch) { report=json; showReport(json) }
            } catch(e:Exception) { ui(epoch) { output.text="Тест не завершён: ${e.message}\nПроверьте код и Wi-Fi. Гостевая сеть или VPN могут мешать соединению." } }
            finally { connection.close(); ui(epoch) { client=null; busy=false; refresh() } }
        }
    }
    private fun showReport(r:JSONObject) {
        output.text=String.format(Locale.US,"Соединение проверено\n\nВторой телефон: %s\nЗадержка: %.1f мс\nПриём: %.2f Мбит/с\nОтправка: %.2f Мбит/с\n\nПо 16 МиБ в каждую сторону. Контрольные суммы проверены. Это скорость приложения по TCP, а не скорость Wi-Fi модуля.",r.getString("peer"),r.getDouble("latency_median_ms"),r.getDouble("download_mbps"),r.getDouble("upload_mbps"))
    }
    private fun stopAll() {
        generation++; stopDiscovery(); registration?.let { runCatching { nsd.unregisterService(it) } }; registration=null
        server?.close(); server=null; client?.close(); client=null; busy=false
        if(::hostInfo.isInitialized) { hostInfo.text="Приём выключен"; peers.removeAllViews(); found.clear(); refresh() }
    }
    @Deprecated("Platform compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=1 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return; val json=report?.toString(2) ?: return
        try {
            val stream=contentResolver.openOutputStream(uri,"wt") ?: error("Файл недоступен")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }; Toast.makeText(this,"JSON сохранён",Toast.LENGTH_LONG).show()
        } catch(e:Exception) { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() }
    }
    override fun onSaveInstanceState(out:Bundle) { out.putString("report",report?.toString()); super.onSaveInstanceState(out) }
    override fun onStop() { active=false; stopAll(); super.onStop() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
