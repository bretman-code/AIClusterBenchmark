package com.example.aiclusterbenchmark

class LlmEngine {
    companion object { init { System.loadLibrary("aicluster") } }
    external fun create(): Long
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
    external fun run(handle: Long, path: String, threads: Int): DoubleArray
}
