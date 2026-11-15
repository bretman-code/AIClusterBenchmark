#import "LLMRunner.h"
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <vector>
#include <cstring>
static std::atomic<bool> stopped{false};
static bool aborted(void *) { return stopped.load(); }
static double nowMs() { return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count(); }
@implementation LLMRunner
+ (void)resetCancellation { stopped.store(false); }
+ (void)cancel { stopped.store(true); }
+ (NSDictionary *)runAtPath:(NSString *)path error:(NSError **)error {
    try {
        auto check=[] { if(stopped.load()) throw std::runtime_error("Тест отменён"); };
        check(); static std::once_flag once; std::call_once(once,[] { llama_backend_init(); });
        auto mp=llama_model_default_params(); mp.n_gpu_layers=0; mp.use_mmap=true;
        mp.progress_callback=[](float,void *) { return !stopped.load(); };
        double t=nowMs();
        std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(path.UTF8String,mp),llama_model_free);
        check(); if(!model) throw std::runtime_error("Не удалось загрузить модель");
        double load=nowMs()-t;
        auto cp=llama_context_default_params(); cp.n_ctx=256; cp.n_batch=128; cp.n_ubatch=32;
        cp.n_threads=2; cp.n_threads_batch=2; cp.abort_callback=aborted;
        std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model.get(),cp),llama_free);
        if(!ctx) throw std::runtime_error("Недостаточно памяти для контекста");
        auto vocab=llama_model_get_vocab(model.get());
        const char * prompt="The following is a short explanation of why the sky is blue. The sky is blue because";
        std::vector<llama_token> tokens(128);
        int n=llama_tokenize(vocab,prompt,strlen(prompt),tokens.data(),128,true,false);
        if(n<=0 || n>128) throw std::runtime_error("Ошибка токенизации");
        check(); t=nowMs();
        if(llama_decode(ctx.get(),llama_batch_get_one(tokens.data(),n))!=0) throw std::runtime_error("Ошибка обработки запроса или отмена");
        llama_synchronize(ctx.get()); double prefill=nowMs()-t;
        std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
        int count=0; t=nowMs();
        for(int i=0;i<32;i++) {
            check(); auto token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
            if(llama_vocab_is_eog(vocab,token)) break;
            if(llama_decode(ctx.get(),llama_batch_get_one(&token,1))!=0) throw std::runtime_error("Ошибка генерации или отмена");
            llama_synchronize(ctx.get()); count++;
        }
        check(); double generation=nowMs()-t;
        if(count==0) throw std::runtime_error("Модель сразу завершила ответ");
        return @{ @"model_load_ms":@(load), @"prompt_ms":@(prefill), @"generation_ms":@(generation),
                  @"generated_tokens":@(count), @"prompt_tokens":@(n), @"llm_tokens_per_second":@(count*1000.0/generation) };
    } catch(const std::exception & e) {
        if(error) *error=[NSError errorWithDomain:@"AICluster" code:1 userInfo:@{NSLocalizedDescriptionKey:[NSString stringWithUTF8String:e.what()]}];
        return nil;
    }
}
@end
