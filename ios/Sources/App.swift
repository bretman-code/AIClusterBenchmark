import SwiftUI
import CryptoKit
import UIKit
import Darwin

final class BenchmarkModel: ObservableObject {
    @Published var busy=false
    @Published var status="Модель уже внутри. Можно запускать без интернета."
    @Published var result: Double?
    @Published var reportURL: URL?
    func run() {
        guard !busy else { return }
        busy=true; result=nil; reportURL=nil; status="Проверяю модель и запускаю генерацию…"
        LLMRunner.resetCancellation()
        UIApplication.shared.isIdleTimerDisabled=true
        DispatchQueue.global(qos:.userInitiated).async {
            do {
                guard let url=Bundle.main.url(forResource:"smollm2-135m-q4_k_m",withExtension:"gguf"), let stream=InputStream(url:url) else { throw NSError(domain:"AICluster",code:2,userInfo:[NSLocalizedDescriptionKey:"Встроенная модель отсутствует"]) }
                stream.open(); defer { stream.close() }
                var hash=SHA256(); var buffer=[UInt8](repeating:0,count:131072)
                while true {
                    let count=stream.read(&buffer,maxLength:buffer.count)
                    if count<0 { throw stream.streamError ?? NSError(domain:"AICluster",code:3) }
                    if count==0 { break }; hash.update(data:Data(buffer.prefix(count)))
                }
                let sha=hash.finalize().map { String(format:"%02x",$0) }.joined()
                guard sha=="2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d" else { throw NSError(domain:"AICluster",code:4,userInfo:[NSLocalizedDescriptionKey:"Ошибка проверки модели"]) }
                var json=try LLMRunner.run(path:url.path)
                var system=utsname(); uname(&system)
                let capacity=MemoryLayout.size(ofValue:system.machine)
                let device=withUnsafePointer(to:&system.machine) { pointer in pointer.withMemoryRebound(to:CChar.self,capacity:capacity) { String(cString:$0) } }
                json["device"]=device; json["ios"]=UIDevice.current.systemVersion
                json["benchmark_version"]="ios-0.1"; json["benchmark_type"]="llm"; json["llm_status"]="completed"
                json["timestamp_ms"]=Int64(Date().timeIntervalSince1970*1000)
                json["engine"]="llama.cpp b5046 CPU"; json["model_sha256"]=sha
                json["model_name"]="SmolLM2-135M-Instruct-Q4_K_M.gguf"; json["model_bytes"]=105454432
                json["context_tokens"]=256; json["threads"]=2; json["prompt_id"]="sky-blue-v1"
                json["sampling"]="greedy"; json["maximum_generated_tokens"]=32
                json["timing_note"]="Cold run. Generation includes sampling and one decode per generated token; load and prompt excluded. No warm-up."
                let report=FileManager.default.temporaryDirectory.appendingPathComponent("llm-iphone-\(Int(Date().timeIntervalSince1970)).json")
                try JSONSerialization.data(withJSONObject:json,options:[.prettyPrinted,.sortedKeys]).write(to:report,options:.atomic)
                let speed=(json["llm_tokens_per_second"] as? NSNumber)?.doubleValue ?? 0
                let tokens=(json["generated_tokens"] as? NSNumber)?.intValue ?? 0
                DispatchQueue.main.async { self.result=speed; self.reportURL=report; self.status="Готово · \(tokens) токена\nCPU · 2 потока · контекст 256"; self.finish() }
            } catch {
                DispatchQueue.main.async { self.status=error.localizedDescription; self.finish() }
            }
        }
    }
    private func finish() { busy=false; UIApplication.shared.isIdleTimerDisabled=false }
}

@main struct AIClusterApp: App {
    @StateObject private var model=BenchmarkModel()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            ScrollView {
                VStack(alignment:.leading,spacing:20) {
                    Text("AI CLUSTER / iOS PREVIEW").font(.caption).tracking(3).foregroundColor(.mint)
                    Text("Нейросеть\nна твоём iPhone").font(.largeTitle.bold())
                    Text("Настоящая генерация без облака. Та же модель и настройки, что в Android-тесте.").foregroundColor(.secondary)
                    VStack(alignment:.leading,spacing:14) {
                        Text("Модель уже внутри").font(.title2.bold())
                        Text("SmolLM2-135M · Q4_K_M\nСкачивать отдельно не нужно.").foregroundColor(.secondary)
                        Button(action:model.run) { Text(model.busy ? "Выполняется тест…" : "Запустить LLM-тест").frame(maxWidth:.infinity).padding(.vertical,8) }.buttonStyle(.borderedProminent).tint(.mint).foregroundColor(.black).disabled(model.busy)
                        if model.busy { ProgressView(); Button("Остановить",action:LLMRunner.cancel).buttonStyle(.bordered) }
                    }.padding(20).frame(maxWidth:.infinity,alignment:.leading).background(Color.white.opacity(0.06)).cornerRadius(22)
                    VStack(alignment:.leading,spacing:12) {
                        Text("Результат").font(.title2.bold())
                        if let speed=model.result { Text(String(format:"%.2f",speed)).font(.system(size:48,weight:.bold,design:.rounded)).foregroundColor(.mint); Text("токенов в секунду").foregroundColor(.secondary) }
                        Text(model.status).textSelection(.enabled)
                        if let url=model.reportURL { ShareLink(item:url) { Label("Сохранить JSON",systemImage:"square.and.arrow.up") }.buttonStyle(.bordered) }
                    }.padding(20).frame(maxWidth:.infinity,alignment:.leading).background(Color.white.opacity(0.06)).cornerRadius(22)
                    Text("Первая iOS-версия: только LLM-тест. Соединение с Android по Wi-Fi добавим после проверки запуска.").font(.footnote).foregroundColor(.secondary)
                }.padding(24)
            }.background(Color(red:0.04,green:0.07,blue:0.12)).preferredColorScheme(.dark)
            .onChange(of:phase) { value in if value == .background { LLMRunner.cancel() } }
        }
    }
}
