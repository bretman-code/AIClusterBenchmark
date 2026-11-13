package com.example.aiclusterbenchmark

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.widget.*
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : Activity() {
 private val executor = Executors.newSingleThreadExecutor()
 private val handler = Handler(Looper.getMainLooper())
 private lateinit var output: TextView
 private lateinit var run: Button
 private var report: JSONObject? = null
 private var pendingExport: String? = null
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,36,28,20); setBackgroundColor(Color.rgb(13,18,28)) }
  val title = TextView(this).apply { text="AI CLUSTER BENCHMARK"; textSize=23f; setTextColor(Color.WHITE) }
  root.addView(title)
  val info = TextView(this).apply { text="${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}\n${Build.SUPPORTED_ABIS.joinToString()}"; textSize=14f; setTextColor(Color.LTGRAY); setPadding(0,15,0,20) }
  root.addView(info)
  run = Button(this).apply { text="ЗАПУСТИТЬ ТЕСТЫ" }
  root.addView(run)
  val save = Button(this).apply { text="СОХРАНИТЬ JSON"; isEnabled=false }
  root.addView(save)
  val scroll = ScrollView(this)
  output = TextView(this).apply { text="CPU: вычислительный тест\nRAM: копирование массивов\nПовторяемость: короткие повторные вычисления\n\nLLM-тест пока не включён."; textSize=15f; setTextColor(Color.WHITE); setPadding(0,18,0,10) }
  scroll.addView(output); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
  setContentView(root)
  run.setOnClickListener { run.isEnabled=false; save.isEnabled=false; output.text="Выполняется тест..."; executor.execute {
   try { val r = benchmark(); handler.post { if (!isDestroyed) { report=r; output.text=format(r); run.isEnabled=true; save.isEnabled=true } } }
   catch(e: Exception) { handler.post { if (!isDestroyed) { output.text="Ошибка: ${e.message}"; run.isEnabled=true } } }
  } }
  report = savedInstanceState?.getString("report")?.let { JSONObject(it) }
  pendingExport = savedInstanceState?.getString("pendingExport")
  report?.let { output.text=format(it); save.isEnabled=true }
  save.setOnClickListener { val r=report ?: return@setOnClickListener
   pendingExport = r.toString(2)
   startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE); type="application/json"
    putExtra(Intent.EXTRA_TITLE,"benchmark-${System.currentTimeMillis()}.json")
   }, 1)
  }
 }
 private fun benchmark(): JSONObject {
  val am=getSystemService(ACTIVITY_SERVICE) as ActivityManager
  val mi=ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
  val r=JSONObject(); r.put("device",Build.MANUFACTURER+" "+Build.MODEL); r.put("android",Build.VERSION.RELEASE); r.put("abi",Build.SUPPORTED_ABIS.joinToString()); r.put("ram_total_mb",mi.totalMem/1048576); r.put("ram_available_mb",mi.availMem/1048576)
  r.put("timestamp_ms",System.currentTimeMillis()); r.put("benchmark_version","0.1"); r.put("api_level",Build.VERSION.SDK_INT)
  // Fixed work, elapsed time; lower is better. Warm-up to reduce JIT distortion.
  cpuWork(150000); val t0=System.nanoTime(); val check=cpuWork(1200000); val cpuMs=(System.nanoTime()-t0)/1e6
  r.put("cpu_ms",cpuMs); r.put("cpu_checksum",check)
  val size=8*1024*1024; val src=ByteArray(size){(it%251).toByte()}; val dst=ByteArray(size)
  System.arraycopy(src,0,dst,0,size)
  val start=System.nanoTime(); repeat(64){ System.arraycopy(src,0,dst,0,size) }; val seconds=(System.nanoTime()-start)/1e9
  r.put("ram_copy_mib_s",64.0*8.0/seconds); r.put("ram_checksum",dst[size-1].toInt())
  var repeatChecksum=0.0
  val times=ArrayList<Double>(); repeat(5){ val s=System.nanoTime(); repeatChecksum+=cpuWork(500000); times.add((System.nanoTime()-s)/1e6) }
  r.put("repeat_checksum",repeatChecksum)
  r.put("thermal_first_ms",times.first()); r.put("thermal_last_ms",times.last()); r.put("thermal_slowdown_ratio",times.last()/times.first()); r.put("thermal_note","Short repeatability probe; not a temperature or sustained throttling measurement")
  r.put("llm_tokens_per_second",JSONObject.NULL); r.put("llm_status","Not implemented in v0.1")
  return r
 }
 private fun cpuWork(n:Int): Double { var sum=0.0; for(i in 1..n) { if (i % 4096 == 0 && Thread.currentThread().isInterrupted) throw InterruptedException(); val x=i.toDouble(); sum+=sqrt(x)*sqrt(x+1.0)/(x+3.0) }; return sum }
 private fun format(r:JSONObject):String = String.format(Locale.US,"Устройство: %s\nAndroid: %s\nRAM всего: %d МБ\nRAM свободно: %d МБ\n\nCPU: %.1f мс (меньше лучше)\nRAM copy: %.1f МиБ/с (больше лучше)\nПовторный CPU тест: %.2fx\n\nЭто предварительный синтетический тест. Реальный LLM-тест будет в следующей версии.\n\nНажмите «Сохранить JSON» для экспорта.",r.getString("device"),r.getString("android"),r.getLong("ram_total_mb"),r.getLong("ram_available_mb"),r.getDouble("cpu_ms"),r.getDouble("ram_copy_mib_s"),r.getDouble("thermal_slowdown_ratio"))
 override fun onSaveInstanceState(outState: Bundle) {
  outState.putString("report",report?.toString()); outState.putString("pendingExport",pendingExport)
  super.onSaveInstanceState(outState)
 }
 @Deprecated("Platform callback for Android 8.1 compatibility")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode!=1) return
  val json=pendingExport; pendingExport=null
  if(resultCode!=RESULT_OK || json==null) return
  try {
   val uri=data?.data ?: error("Не выбран файл")
   val stream=contentResolver.openOutputStream(uri,"wt") ?: error("Не удалось открыть файл")
   stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
   Toast.makeText(this,"JSON сохранён",Toast.LENGTH_LONG).show()
  } catch(e:Exception) { Toast.makeText(this,"Ошибка сохранения: ${e.message}",Toast.LENGTH_LONG).show() }
 }
 override fun onDestroy() { executor.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
