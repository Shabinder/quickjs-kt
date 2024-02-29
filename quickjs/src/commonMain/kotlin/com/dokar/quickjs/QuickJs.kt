package com.dokar.quickjs

import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.JsObjectHandle
import com.dokar.quickjs.binding.ObjectBinding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * DSL for [QuickJs]. The instance will be closed automatically when the [block] is finished.
 *
 * The job dispatcher of current coroutine context (or throw if not found) will be
 * used to run `async` function bindings, and a JavaScript execution dispatcher will
 * be created from the job dispatcher using [CoroutineDispatcher.limitedParallelism]`(1)`.
 *
 * If the job dispatcher (e.g. [Dispatchers.Unconfined]) does not support
 * [CoroutineDispatcher.limitedParallelism], this function will throw.
 */
@OptIn(ExperimentalStdlibApi::class)
suspend inline fun <T : Any?> quickJs(block: QuickJs.() -> T): T {
    val dispatcher = coroutineContext[CoroutineDispatcher]
        ?: throw UnsupportedOperationException(
            "The current coroutine context does not have a coroutine context. " +
                    "Please pass your dispatcher explicitly using another function."
        )
    return quickJs(dispatcher, block)
}

/**
 * DSL for [QuickJs]. The instance will be closed automatically when the [block] is finished.
 *
 * A job dispatcher is required to run `async` function bindings,
 * and a JavaScript execution dispatcher will be created from the
 * job dispatcher using [CoroutineDispatcher.limitedParallelism]`(1)`.
 *
 * If the job dispatcher (e.g. [Dispatchers.Unconfined]) does not support
 * [CoroutineDispatcher.limitedParallelism], this function will throw.
 *
 * @param jobDispatcher The dispatcher for executing async jobs.
 */
inline fun <T : Any?> quickJs(jobDispatcher: CoroutineDispatcher, block: QuickJs.() -> T): T {
    val quickJs = QuickJs.create(jobDispatcher = jobDispatcher)
    return try {
        quickJs.block()
    } finally {
        quickJs.close()
    }
}

/**
 * The QuickJS runtime.
 */
expect class QuickJs {
    /**
     * Whether the instance has closed.
     */
    var isClosed: Boolean
        private set

    /**
     * The version of QuickJS.
     */
    val version: String

    /**
     * Set memory limit for the js runtime.
     */
    var memoryLimit: Long

    /**
     * Set stack size for the js runtime. Defaults to 512 Kb.
     */
    var maxStackSize: Long

    /**
     * The memory usage of the js runtime.
     */
    val memoryUsage: MemoryUsage

    /**
     * Define a JavaScript object from kotlin object.
     *
     * @param name The name in JavaScript code.
     * @param parent The parent object to attach to. Defaults to 'globalThis'.
     * @param binding The kotlin binding.
     */
    fun defineBinding(
        name: String,
        binding: ObjectBinding,
        parent: JsObjectHandle = JsObjectHandle.globalThis,
    ): JsObjectHandle

    /**
     * Define a JavaScript function from kotlin object. It will be attached to 'globalThis'.
     *
     * @param name The name in JavaScript code.
     * @param binding The kotlin binding.
     */
    fun <R> defineBinding(
        name: String,
        binding: FunctionBinding<R>,
    )

    /**
     * Define a JavaScript async function from kotlin object. It will be attached to 'globalThis'.
     *
     * In JavaScript, the defined function returns a Promise, await can be used to get the result.
     *
     * In Kotlin, it's a suspend function, so another suspend can be called.
     *
     * @param name The name in JavaScript code.
     * @param binding The kotlin binding.
     */
    fun <R> defineBinding(
        name: String,
        binding: AsyncFunctionBinding<R>,
    )

    /**
     * Add a JavaScript module
     *
     * @param name The module name.
     * @param code The JavaScript code.
     *
     * @throws QuickJsException If failed to compile the code.
     */
    @Throws(QuickJsException::class)
    fun addModule(
        name: String,
        code: String,
    )

    /**
     * Add a compiled JavaScript module.
     *
     * @param bytecode The compiled module bytecode.
     */
    fun addModule(bytecode: ByteArray)

    /**
     * Compile javascript code to QuickJS bytecode.
     *
     * ES modules syntax is available when [asModule] is true.
     *
     * @param code The code to compile.
     * @param filename The script filename.
     * @param asModule Whether compile the code as a module.
     * @throws QuickJsException If an error occurred when evaluating code or mapping values.
     */
    @Throws(QuickJsException::class)
    fun compile(code: String, filename: String = "main.js", asModule: Boolean = false): ByteArray

    /**
     * Evaluate QuickJS-compiled bytecode.
     *
     * @param T The result type.
     * @param bytecode The bytecode buffer.
     * @throws QuickJsException If an error occurred when evaluating code or mapping values.
     */
    @Throws(QuickJsException::class, CancellationException::class)
    suspend inline fun <reified T> evaluate(bytecode: ByteArray): T

    /**
     * Evaluate javascript code.
     *
     * ES modules syntax is available when [asModule] is true.
     *
     * @param T The result type.
     * @param code The code to evaluate.
     * @param filename The script filename.
     * @param asModule Whether evaluate the code as a module or evaluate it globally.
     * @throws QuickJsException If an error occurred when evaluating code or mapping values.
     */
    @Throws(QuickJsException::class, CancellationException::class)
    suspend inline fun <reified T> evaluate(
        code: String,
        filename: String = "main.js",
        asModule: Boolean = false,
    ): T

    /**
     * Run GC.
     */
    fun gc()

    /**
     * Free the JavaScript runtime and context.
     */
    fun close()

    /**
     * Start new job to invoke the suspend function.
     */
    internal fun invokeAsyncFunction(
        args: Array<Any?>,
        block: suspend (bindingArgs: Array<Any?>) -> Any?,
    )

    companion object {
        /**
         * Create new QuickJS runtime.
         *
         * A job dispatcher is required to run `async` function bindings,
         * and a JavaScript execution dispatcher will be created from the
         * job dispatcher using [CoroutineDispatcher.limitedParallelism]`(1)`.
         *
         * If the job dispatcher (e.g. [Dispatchers.Unconfined]) does not support
         * [CoroutineDispatcher.limitedParallelism], this function will throw.
         *
         * @param jobDispatcher The dispatcher for executing async jobs.
         * @throws QuickJsException If failed to create a runtime.
         */
        @Throws(QuickJsException::class)
        fun create(jobDispatcher: CoroutineDispatcher): QuickJs
    }
}
