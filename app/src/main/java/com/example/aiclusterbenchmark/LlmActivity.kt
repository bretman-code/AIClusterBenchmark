package com.example.aiclusterbenchmark

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

class LlmActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val monitor = Any()
    private var engine: LlmEngine? = null
    private var handle = 0L
    @Volatile private var stopped = false
    @Volatile private var destroyed = false
    private lateinit var output: TextView
    private lateinit var select: Button
    private lateinit var start: Button
    private lateinit var save: Button
    private lateinit var cancel: Button
    private lateinit var progress: ProgressBar
    private var report: JSONObject? = null
    private val model get() = File(filesDir, "benchmark-model.gguf")
    private val prefs get() = getSharedPreferences("llm-model", MODE_PRIVATE)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root=Ui.page(this,"Нейросеть\nна твоём CPU","Без облака. Реальные токены в секунду, отдельно от синтетических тестов.")
        root.addView(Ui.button(this,"‹  Все тесты").apply { setOnClickListener { finish() } })
        Ui.gap(this,root)
        val modelCard=Ui.card(this,root,"1. Подготовь модель","Для начала — SmolLM2-135M Q4_K_M, около 101 МиБ. Скачай файл и выбери его на телефоне.")
        modelCard.addView(Ui.button(this,"Скачать небольшую модель").apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf?download=true"))) }
        })
        Ui.gap(this,modelCard,8)
        select=Ui.button(this,"Выбрать файл GGUF"); modelCard.addView(select)
        Ui.gap(this,root)
        val testCard=Ui.card(this,root,"2. Измерь скорость","До 32 токенов  ·  2 потока  ·  контекст 256\nДля сравнения телефонов используй одну модель.")
        start=Ui.button(this,"Запустить LLM-тест",true).apply { isEnabled=model.exists() }; testCard.addView(start)
        Ui.gap(this,testCard,8)
        cancel=Ui.button(this,"Остановить тест").apply { isEnabled=false }; testCard.addView(cancel)
        Ui.gap(this,root)
        val resultCard=Ui.card(this,root,"Результат LLM")
        progress=ProgressBar(this).apply { visibility=android.view.View.GONE; contentDescription="Выполняется тест или импорт модели" }
        resultCard.addView(progress,LinearLayout.LayoutParams(Ui.dp(this,36),Ui.dp(this,36)))
        output=Ui.text(this,if(model.exists()) "Модель готова: ${prefs.getString("name","GGUF")}" else "Сначала выбери модель. Затем здесь появится измеренная скорость генерации.",15f,Ui.muted)
        resultCard.addView(output); Ui.gap(this,resultCard,16)
        save=Ui.button(this,"Сохранить результат · JSON").apply { isEnabled=false }; resultCard.addView(save)
        report = state?.getString("report")?.let(::JSONObject)
        report?.let { showReport(it); save.isEnabled=true }
        select.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
            },1)
        }
        start.setOnClickListener { runTest() }
        cancel.setOnClickListener { requestStop(); output.text="Отмена… Ожидаю завершения текущей операции." }
        save.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type="application/json"
                putExtra(Intent.EXTRA_TITLE,"llm-benchmark-${System.currentTimeMillis()}.json")
            },2)
        }
    }
    private fun busy(value: Boolean) {
        progress.visibility=if(value) android.view.View.VISIBLE else android.view.View.GONE
        select.isEnabled=!value; start.isEnabled=!value && model.exists()
        cancel.isEnabled=value; save.isEnabled=!value && report!=null
        if(value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun checkStop() { check(!stopped && !destroyed) { "Операция отменена" } }
    private fun ui(action: () -> Unit) { runOnUiThread { if(!destroyed) action() } }
    private fun requestStop() {
        stopped=true
        synchronized(monitor) { if(handle!=0L) engine?.cancel(handle) }
    }
    private fun importModel(uri: Uri) {
        stopped=false; busy(true); output.text="Копирую модель и проверяю файл…"
        worker.execute {
            val temp=File(filesDir,"model-import.tmp")
            try {
                var name="model.gguf"
                contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) name=it.getString(0) ?: name
                }
                val digest=MessageDigest.getInstance("SHA-256")
                var size=0L
                val input=contentResolver.openInputStream(uri) ?: error("Не удалось открыть модель")
                input.use { source -> temp.outputStream().use { target ->
                    val buffer=ByteArray(128*1024)
                    while(true) {
                        checkStop(); val count=source.read(buffer); if(count<0) break
                        size+=count; check(size<=512L*1024*1024) { "Выберите модель меньше 512 МБ" }
                        digest.update(buffer,0,count); target.write(buffer,0,count)
                    }
                } }
                check(size>=16) { "Файл слишком мал" }
                val magic=temp.inputStream().use { source -> ByteArray(4).also { check(source.read(it)==4) } }
                check(magic.contentEquals(byteArrayOf(71,71,85,70))) { "Это не файл GGUF" }
                checkStop()
                Files.move(temp.toPath(),model.toPath(),StandardCopyOption.REPLACE_EXISTING)
                val sha=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                prefs.edit().putString("name",name).putString("sha256",sha).commit()
                ui { output.text="Модель готова: $name\nРазмер: ${size/1048576} МиБ\nНажмите «Запустить LLM-тест»." }
            } catch(e:Exception) { ui { output.text="Ошибка: ${e.message}" } }
            finally { temp.delete(); ui { busy(false) } }
        }
    }
    private fun runTest() {
        stopped=false; busy(true); report=null
        output.text="Загружаю модель и выполняю реальную генерацию…\nНа слабом телефоне тест может занять несколько минут."
        worker.execute {
            try {
                checkStop()
                val native=LlmEngine()
                val session=synchronized(monitor) {
                    checkStop(); engine=native; native.create().also { handle=it }
                }
                val values=native.run(session,model.absolutePath,2)
                checkStop()
                val result=JSONObject().apply {
                    put("benchmark_version","0.3"); put("benchmark_type","llm")
                    put("device","${Build.MANUFACTURER} ${Build.MODEL}"); put("android",Build.VERSION.RELEASE)
                    put("abi",Build.SUPPORTED_ABIS.joinToString()); put("process_64_bit",android.os.Process.is64Bit())
                    put("timestamp_ms",System.currentTimeMillis()); put("engine","llama.cpp b5046 CPU")
                    put("model_name",prefs.getString("name","GGUF")); put("model_sha256",prefs.getString("sha256",""))
                    put("model_bytes",model.length()); put("context_tokens",256); put("threads",values[5].toInt())
                    put("prompt_id","sky-blue-v1"); put("sampling","greedy"); put("maximum_generated_tokens",32)
                    put("model_load_ms",values[0]); put("prompt_ms",values[1]); put("generation_ms",values[2])
                    put("generated_tokens",values[3].toInt()); put("prompt_tokens",values[4].toInt())
                    put("llm_tokens_per_second",values[3]*1000.0/values[2]); put("llm_status","completed")
                    put("timing_note","Cold run. Generation includes sampling and one decode per generated token; model load and prompt evaluation excluded. Stops at EOS or 32 tokens. No warm-up.")
                }
                ui { report=result; showReport(result) }
            } catch(e:Exception) { ui { output.text="Тест не завершён: ${e.message}" } }
            catch(e:LinkageError) { ui { output.text="Движок недоступен для этой системы: ${e.message}" } }
            finally {
                synchronized(monitor) { if(handle!=0L) { engine?.destroy(handle); handle=0 }; engine=null }
                ui { busy(false) }
            }
        }
    }
    private fun showReport(r:JSONObject) {
        output.text=String.format(Locale.US,"Модель: %s\nРеальная скорость: %.2f токенов/с\nСгенерировано: %d токенов\nЗагрузка модели: %.1f с\nОбработка запроса: %.1f с\nГенерация: %.1f с\n\nДля сравнения нужен тот же файл модели и те же настройки.",r.getString("model_name"),r.getDouble("llm_tokens_per_second"),r.getInt("generated_tokens"),r.getDouble("model_load_ms")/1000,r.getDouble("prompt_ms")/1000,r.getDouble("generation_ms")/1000)
    }
    @Deprecated("Platform compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        if(requestCode==1) importModel(uri)
        if(requestCode==2) {
            val json=report?.toString(2) ?: return
            try {
                val stream=contentResolver.openOutputStream(uri,"wt") ?: error("Файл недоступен")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                Toast.makeText(this,"JSON сохранён",Toast.LENGTH_LONG).show()
            } catch(e:Exception) { Toast.makeText(this,e.message,Toast.LENGTH_LONG).show() }
        }
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putString("report",report?.toString()); super.onSaveInstanceState(outState) }
    override fun onDestroy() { destroyed=true; requestStop(); worker.shutdown(); super.onDestroy() }
}
