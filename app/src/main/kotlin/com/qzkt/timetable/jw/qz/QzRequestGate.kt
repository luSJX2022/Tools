package com.qzkt.timetable.jw.qz

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/**
 * 教务网关的请求去重（single-flight）+ 极短缓存。
 *
 * 和 [QzRateLimiter] 分工：
 * - 限速器解决「不同请求太密」；
 * - 这里解决「同一个请求被并发发多次」——比如连点查询、进出页面时重复触发、
 *   后台同步和手动刷新撞在一起。这些请求内容完全一样，发两次没有任何意义，
 *   只会让学校更快判定我们高频。
 *
 * 只对 GET 生效（POST 可能有副作用）；键包含 Cookie，避免把别人的会话结果串给当前用户。
 */
internal object QzRequestGate {

    /** 同一个响应在这段时间内复用，挡住「连点 + 页面来回切」这类连续重复。 */
    const val CACHE_TTL_MS: Long = 3_000L

    /** 缓存条数上限，避免长时间挂着把内存撑大。 */
    private const val MAX_CACHE_ENTRIES = 8

    /** 一次正在飞行的请求。 */
    private class Flight {
        @Volatile
        var finished: Boolean = false

        @Volatile
        var result: String? = null

        @Volatile
        var error: Throwable? = null

        val monitor = Object()
    }

    private class Cached(val body: String, val at: Long)

    private val flights = ConcurrentHashMap<String, Flight>()
    private val cache = object : LinkedHashMap<String, Cached>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /**
     * 调用方要求「这次别用缓存」的标记头。
     *
     * 成绩/考试里有**故意重试**的逻辑（学校服务器偶尔返回空页，第二遍往往就好了），
     * 那种重试必须真的再打一次网络；而「连点查询」这类重复应该被缓存挡掉。
     * 两者只能靠调用方显式区分，所以加了这个小标记。
     */
    const val NO_CACHE_HEADER = "X-Qz-No-Cache"

    /** 只有 GET 才去重（读操作幂等）。 */
    fun keyOf(request: Request): String? {
        if (request.method != "GET") return null
        return request.url.toString() + "|" + request.header("Cookie").orEmpty()
    }

    /** 这次请求是否允许复用缓存。 */
    fun cacheable(request: Request): Boolean =
        request.header(NO_CACHE_HEADER) == null

    /** 缓存是否还可以用。抽成纯函数便于测试。 */
    fun cacheFresh(now: Long, cachedAt: Long, ttl: Long = CACHE_TTL_MS): Boolean =
        cachedAt > 0 && now - cachedAt in 0..ttl

    /** 测试用：清空状态。 */
    fun resetForTest() {
        flights.clear()
        synchronized(cache) { cache.clear() }
    }

    fun interceptor(): Interceptor = object : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val key = keyOf(request)
                ?: return chain.proceed(request)

            // 1) 极短缓存：刚查过的同样的页面直接复用（调用方标记 no-cache 时跳过）
            if (cacheable(request)) {
                synchronized(cache) {
                    cache[key]?.let { hit ->
                        if (cacheFresh(System.currentTimeMillis(), hit.at)) {
                            return rebuild(request, hit.body)
                        }
                        cache.remove(key)
                    }
                }
            }

            // 2) 已经有同样的请求在飞：等它的结果，不再发第二次
            val own = Flight()
            val running = flights.putIfAbsent(key, own)
            if (running != null) {
                synchronized(running.monitor) {
                    var guard = 0
                    while (!running.finished && guard < 300) {
                        runCatching { running.monitor.wait(100) }
                        guard++
                    }
                }
                val body = running.result
                val failure = running.error
                if (body != null) return rebuild(request, body)
                if (failure != null) throw failure
                // 等超时了（极少见）：退回自己发一次，别把请求卡死
                return chain.proceed(request)
            }

            try {
                val response = chain.proceed(request)
                val body = runCatching {
                    response.peekBody(Long.MAX_VALUE).bytes().let { QzHttp.decodeBody(it, response.header("Content-Type")) }
                }.getOrNull()

                if (body != null && response.isSuccessful) {
                    if (cacheable(request)) {
                        synchronized(cache) { cache[key] = Cached(body, System.currentTimeMillis()) }
                    }
                    synchronized(own.monitor) {
                        own.result = body
                        own.finished = true
                        own.monitor.notifyAll()
                    }
                } else {
                    synchronized(own.monitor) {
                        own.finished = true
                        own.monitor.notifyAll()
                    }
                }
                return response
            } catch (e: Throwable) {
                synchronized(own.monitor) {
                    own.error = e
                    own.finished = true
                    own.monitor.notifyAll()
                }
                throw e
            } finally {
                flights.remove(key, own)
            }
        }
    }

    /** 用缓存的正文拼一个等价响应（调用方都只读 body）。 */
    private fun rebuild(request: Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("text/html; charset=utf-8".toMediaTypeOrNull()))
        .build()
}
