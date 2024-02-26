package com.dokar.quickjs

import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.Binding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.JsFunction
import com.dokar.quickjs.binding.JsObjectHandle
import com.dokar.quickjs.binding.JsProperty
import com.dokar.quickjs.binding.ObjectBinding
import com.dokar.quickjs.util.withLockSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/**
 * Evaluate QuickJS-compiled bytecode.
 *
 * This function provides a [type] parameter, useful when the inline version of
 * [QuickJs.evaluate] is not available.
 *
 * @see [QuickJs.evaluate]
 */
@Throws(QuickJsException::class)
suspend fun <T> QuickJs.evaluate(
    bytecode: ByteArray,
    type: Class<T>
): T {
    return jsAutoCastOrThrow(evaluateInternal(bytecode), type)
}

/**
 * Evaluate JavaScript code.
 *
 * This function provides a [type] parameter, useful when the inline version of
 * [QuickJs.evaluate] is not available.
 *
 * @see [QuickJs.evaluate]
 */
@Throws(QuickJsException::class)
suspend fun <T> QuickJs.evaluate(
    code: String,
    type: Class<T>,
    filename: String = "main.js",
    asModule: Boolean = false
): T {
    return jsAutoCastOrThrow(evaluateInternal(code, filename, asModule), type)
}

actual class QuickJs private constructor(
    private val jobDispatcher: CoroutineDispatcher,
) : Closeable {
    // Native pointers
    private var globals: Long = 0
    private var runtime: Long = 0
    private var context: Long = 0

    private val objectBindings = mutableMapOf<Long, ObjectBinding>()
    private val globalFunctions = mutableMapOf<String, Binding>()

    private val modules = mutableListOf<ByteArray>()

    private var evalException: Throwable? = null

    // Coroutines and async jobs related
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (evalException == null) {
            evalException = throwable
        }
    }
    private val coroutineScope = CoroutineScope(jobDispatcher + exceptionHandler)

    /**
     * Concurrent [evaluate] is not supported.
     */
    private val evalMutex = Mutex()

    private val jobsMutex = Mutex()
    private val asyncJobs = mutableListOf<Job>()

    private val closeLock = Any()

    actual var isClosed: Boolean = false
        private set

    actual val version: String get() = nativeGetVersion()

    actual var memoryLimit: Long = -1L
        set(value) {
            ensureNotClosed()
            field = value
            setMemoryLimit(runtime, value)
        }

    actual var maxStackSize: Long = 256 * 1024L
        set(value) {
            ensureNotClosed()
            field = value
            setMaxStackSize(runtime, value)
        }

    actual val memoryUsage: MemoryUsage
        get() {
            ensureNotClosed()
            return getMemoryUsage(runtime)
        }

    init {
        try {
            runtime = newRuntime()
            context = newContext(runtime)
            globals = initGlobals(runtime)
        } catch (e: QuickJsException) {
            close()
            throw e
        }
    }

    actual fun defineBinding(
        name: String,
        binding: ObjectBinding,
        parent: JsObjectHandle,
    ): JsObjectHandle {
        ensureNotClosed()
        val nativeHandle = defineObject(
            globals = globals,
            context = context,
            parent = parent.nativeHandle,
            name = name,
            properties = binding.properties.toTypedArray(),
            functions = binding.functions.toTypedArray(),
        )
        if (nativeHandle < 0L) {
            throw QuickJsException("Failed to define object '$name'.")
        }
        objectBindings[nativeHandle] = binding
        return JsObjectHandle(nativeHandle)
    }

    actual fun <R> defineBinding(name: String, binding: FunctionBinding<R>) {
        ensureNotClosed()
        globalFunctions[name] = binding
        defineFunction(
            globals = globals,
            context = context,
            name = name,
            isAsync = false,
        )
    }

    actual fun <R> defineBinding(name: String, binding: AsyncFunctionBinding<R>) {
        ensureNotClosed()
        globalFunctions[name] = binding
        defineFunction(
            globals = globals,
            context = context,
            name = name,
            isAsync = true,
        )
    }

    @Throws(QuickJsException::class)
    actual fun addModule(name: String, code: String) {
        ensureNotClosed()
        val bytecode = compile(code = code, filename = name, asModule = true)
        modules.add(bytecode)
    }

    actual fun addModule(bytecode: ByteArray) {
        ensureNotClosed()
        modules.add(bytecode)
    }

    @Throws(QuickJsException::class)
    actual fun compile(code: String, filename: String, asModule: Boolean): ByteArray {
        ensureNotClosed()
        return compile(context, globals, filename, code, asModule)
    }

    @Throws(QuickJsException::class, CancellationException::class)
    actual suspend inline fun <reified T> evaluate(bytecode: ByteArray): T {
        return jsAutoCastOrThrow(evaluateInternal(bytecode), T::class.java)
    }

    @Throws(QuickJsException::class, CancellationException::class)
    actual suspend inline fun <reified T> evaluate(
        code: String,
        filename: String,
        asModule: Boolean
    ): T {
        return jsAutoCastOrThrow(evaluateInternal(code, filename, asModule), T::class.java)
    }

    @PublishedApi
    internal suspend fun evaluateInternal(bytecode: ByteArray): Any? = evalMutex.withLock {
        evalAndAwait {
            evaluateBytecode(context = context, globals = globals, buffer = bytecode)
        }
    }

    @PublishedApi
    internal suspend fun evaluateInternal(
        code: String,
        filename: String,
        asModule: Boolean,
    ): Any? = evalMutex.withLock {
        evalAndAwait {
            evaluate(context, globals, filename, code, asModule)
        }
    }

    private suspend fun evalAndAwait(evalBlock: suspend () -> Any?): Any? {
        ensureNotClosed()
        evalException = null
        loadModules()
        evalBlock()
        awaitAsyncJobs()
        val result = getEvaluateResult(context, globals)
        handleException()
        return result
    }

    actual fun gc() {
        ensureNotClosed()
        gc(runtime)
    }

    actual override fun close() {
        isClosed = true
        jobsMutex.withLockSync {
            asyncJobs.forEach { it.cancel() }
            asyncJobs.clear()
        }
        synchronized(closeLock) {
            objectBindings.clear()
            globalFunctions.clear()
            modules.clear()
            if (globals != 0L) {
                releaseGlobals(context, globals)
                globals = 0
            }
            if (context != 0L) {
                releaseContext(context)
                context = 0
            }
            if (runtime != 0L) {
                releaseRuntime(runtime)
                runtime = 0
            }
        }
    }

    private suspend fun awaitAsyncJobs() {
        /**
         * This is our simple 'event loop'.
         */
        while (true) {
            while (executePendingJob(context)) {
                // Job executed
            }
            val jobs = jobsMutex.withLock { asyncJobs.filter { it.isActive } }
            if (jobs.isEmpty()) {
                // No jobs to run
                break
            }
            jobs.joinAll()
        }
    }

    private fun handleException() {
        val exception = evalException
        if (exception != null) {
            evalException = null
            throw exception
        }
    }

    @Synchronized
    private fun loadModules() {
        if (modules.isEmpty()) {
            return
        }
        val iterator = modules.iterator()
        while (iterator.hasNext()) {
            val module = iterator.next()
            evaluateBytecode(context = context, globals = globals, buffer = module)
            iterator.remove()
        }
    }

    internal actual fun invokeAsyncFunction(
        args: Array<Any?>,
        block: suspend (bindingArgs: Array<Any?>) -> Any?,
    ) {
        ensureNotClosed()
        val (resolveHandle, rejectHandle) = promiseHandlesFromArgs(args)
        val job = coroutineScope.launch {
            try {
                val result = block(args.sliceArray(2..<args.size))
                synchronized(closeLock) {
                    // Call resolve() on JNI side
                    invokeJsFunction(
                        context = context,
                        globals = globals,
                        handle = resolveHandle,
                        args = arrayOf(result)
                    )
                }
            } catch (e: Throwable) {
                synchronized(closeLock) {
                    // Call reject() on JNI side
                    invokeJsFunction(
                        context = context,
                        globals = globals,
                        handle = rejectHandle,
                        args = arrayOf(e)
                    )
                }
            }
            synchronized(closeLock) { executePendingJob(context) }
        }
        job.invokeOnCompletion {
            jobsMutex.withLockSync { asyncJobs.remove(job) }
        }
        jobsMutex.withLockSync { asyncJobs.add(job) }
    }

    private fun promiseHandlesFromArgs(args: Array<Any?>): Pair<Long, Long> {
        require(args.size >= 2) {
            "Invoking async functions requires resolve and reject handles."
        }
        val resolveFunctionHandle = args[0]
        require(resolveFunctionHandle is Long) {
            val type = resolveFunctionHandle?.let { it::class.qualifiedName }
            "Unexpected resolve handle type $type, expected: Long"
        }
        val rejectFunctionHandle = args[1]
        require(rejectFunctionHandle is Long) {
            val type = rejectFunctionHandle?.let { it::class.qualifiedName }
            "Unexpected reject handle type $type, expected: Long"
        }
        return resolveFunctionHandle to rejectFunctionHandle
    }

    /**
     * Called from JNI.
     */
    private fun onCallGetter(
        handle: Long,
        name: String,
    ): Any? {
        ensureNotClosed()
        val binding = objectBindings[handle] ?: throw QuickJsException(
            "JavaScript called getter of '$name' on an unknown binding"
        )
        return binding.getter(name)
    }

    /**
     * Called from JNI.
     */
    private fun onCallSetter(
        handle: Long,
        name: String,
        value: Any?,
    ) {
        ensureNotClosed()
        val binding = objectBindings[handle] ?: throw QuickJsException(
            "JavaScript called setter of '$name' on an unknown binding"
        )
        binding.setter(name, value)
    }

    /**
     * Called from JNI.
     */
    private fun onCallFunction(
        handle: Long,
        name: String,
        args: Array<Any?>,
    ): Any? {
        ensureNotClosed()
        if (handle == JsObjectHandle.globalThis.nativeHandle) {
            val binding = globalFunctions[name] ?: throw QuickJsException(
                "'$name()' does not found in global functions."
            )
            return when (binding) {
                is AsyncFunctionBinding<*> -> invokeAsyncFunction(args) { binding.invoke(it) }
                is FunctionBinding<*> -> binding.invoke(args)
                is ObjectBinding -> qjsError("Object call not be invoked.")
            }
        } else {
            val binding = objectBindings[handle] ?: throw QuickJsException(
                "JavaScript called function '$name' on an unknown binding"
            )
            return binding.invoke(name, args)
        }
    }

    /**
     * Called from JNI.
     */
    private fun setEvalException(exception: Throwable) {
        ensureNotClosed()
        this.evalException = exception
    }

    /**
     * Called from JNI.
     */
    private fun setUnhandledPromiseRejection(reason: Any?) {
        ensureNotClosed()
        if (evalException == null) {
            evalException = reason as? Throwable ?: Error(reason.toString())
        }
        jobsMutex.withLockSync { asyncJobs.forEach { it.cancel() } }
    }

    private fun ensureNotClosed() = check(runtime != 0L) { "Already closed." }

    private external fun newRuntime(): Long

    @Throws(QuickJsException::class)
    private external fun newContext(runtime: Long): Long

    private external fun initGlobals(runtime: Long): Long

    @Throws(QuickJsException::class)
    private external fun releaseGlobals(context: Long, globals: Long)

    @Throws(QuickJsException::class)
    private external fun releaseRuntime(runtime: Long)

    @Throws(QuickJsException::class)
    private external fun releaseContext(context: Long)

    @Throws(QuickJsException::class)
    private external fun defineObject(
        globals: Long,
        context: Long,
        parent: Long,
        name: String,
        properties: Array<JsProperty>,
        functions: Array<JsFunction>,
    ): Long

    @Throws(QuickJsException::class)
    private external fun defineFunction(
        globals: Long,
        context: Long,
        name: String,
        isAsync: Boolean,
    )

    @Throws(QuickJsException::class)
    private external fun gc(runtime: Long)

    @Throws(QuickJsException::class)
    private external fun nativeGetVersion(): String

    @Throws(QuickJsException::class)
    private external fun setMemoryLimit(runtime: Long, byteCount: Long)

    @Throws(QuickJsException::class)
    private external fun setMaxStackSize(runtime: Long, byteCount: Long)

    @Throws(QuickJsException::class)
    private external fun getMemoryUsage(runtime: Long): MemoryUsage

    @Throws(QuickJsException::class)
    private external fun compile(
        context: Long,
        globals: Long,
        filename: String,
        code: String,
        asModule: Boolean
    ): ByteArray

    @Throws(QuickJsException::class)
    private external fun evaluate(
        context: Long,
        globals: Long,
        filename: String,
        code: String,
        asModule: Boolean
    ): Any?

    @Throws(QuickJsException::class)
    private external fun evaluateBytecode(
        context: Long,
        globals: Long,
        buffer: ByteArray,
    ): Any?

    @Throws(QuickJsException::class)
    private external fun invokeJsFunction(
        context: Long,
        globals: Long,
        handle: Long,
        args: Array<Any?>?,
    )

    @Throws(QuickJsException::class)
    private external fun executePendingJob(context: Long): Boolean

    @Throws(QuickJsException::class)
    private external fun getEvaluateResult(context: Long, globals: Long): Any?

    actual companion object {
        init {
            loadNativeLibrary("quickjs")
        }

        @Throws(QuickJsException::class)
        actual fun create(
            jobDispatcher: CoroutineDispatcher,
        ): QuickJs = QuickJs(
            jobDispatcher = jobDispatcher,
        )
    }
}
