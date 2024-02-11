package com.dokar.quickjs.bridge

import com.dokar.quickjs.JSContext
import com.dokar.quickjs.JSValue
import com.dokar.quickjs.JS_EVAL_FLAG_ASYNC
import com.dokar.quickjs.JS_EVAL_FLAG_COMPILE_ONLY
import com.dokar.quickjs.JS_EVAL_TYPE_MODULE
import com.dokar.quickjs.JS_Eval
import com.dokar.quickjs.JS_EvalFunction
import com.dokar.quickjs.JS_FreeValue
import com.dokar.quickjs.JS_GetException
import com.dokar.quickjs.JS_IsNull
import com.dokar.quickjs.JS_READ_OBJ_BYTECODE
import com.dokar.quickjs.JS_ReadObject
import com.dokar.quickjs.JS_TAG_FUNCTION_BYTECODE
import com.dokar.quickjs.JS_TAG_MODULE
import com.dokar.quickjs.JsValueGetNormTag
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.qjsError
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CValues
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues

@OptIn(ExperimentalForeignApi::class)
@Throws(QuickJsException::class)
internal fun CPointer<JSContext>.compile(
    code: String,
    filename: String,
    asModule: Boolean
): ByteArray {
    var evalFlags = JS_EVAL_FLAG_COMPILE_ONLY or JS_EVAL_FLAG_ASYNC
    if (asModule) {
        evalFlags = evalFlags or JS_EVAL_TYPE_MODULE
    }
    val cStr = code.cstr
    val result = JS_Eval(
        ctx = this,
        input = cStr,
        input_len = (cStr.size - 1).toULong(),
        filename = filename.cstr,
        eval_flags = evalFlags,
    )
    return result.use(context = this) {
        checkContextException(this@compile)
        val tag = JsValueGetNormTag(result)
        if (tag != JS_TAG_FUNCTION_BYTECODE && tag != JS_TAG_MODULE) {
            qjsError("Failed to compile code, unsupported result type with tag: $tag")
        }
        toKtValue(context = this@compile)
    } as? ByteArray ?: qjsError("Failed to read bytecode.")
}

@OptIn(ExperimentalForeignApi::class)
@Throws(QuickJsException::class)
internal fun CPointer<JSContext>.evaluate(
    code: String,
    filename: String,
    asModule: Boolean,
): JsPromise {
    val context = this@evaluate
    var evalFlags = JS_EVAL_FLAG_ASYNC
    if (asModule) {
        evalFlags = evalFlags or JS_EVAL_TYPE_MODULE
    }
    val cStr = code.cstr
    val result = JS_Eval(
        ctx = this,
        input = cStr,
        input_len = (cStr.size - 1).toULong(),
        filename = filename.cstr,
        eval_flags = evalFlags,
    )
    return handleEvalResult(context, result)
}

@OptIn(ExperimentalForeignApi::class)
@Throws(QuickJsException::class)
internal fun CPointer<JSContext>.evaluate(
    bytecode: ByteArray
): JsPromise = memScoped {
    val context = this@evaluate

    @Suppress("UNCHECKED_CAST")
    val buffer = bytecode.toCValues() as CValues<UByteVar>
    val jsValue = JS_ReadObject(
        ctx = context,
        buf = buffer,
        buf_len = buffer.size.toULong(),
        flags = JS_READ_OBJ_BYTECODE,
    )
    val result = JS_EvalFunction(
        ctx = context,
        fun_obj = jsValue,
    )
    handleEvalResult(context, result)
}

@OptIn(ExperimentalForeignApi::class)
private fun handleEvalResult(
    context: CPointer<JSContext>,
    result: CValue<JSValue>
): JsPromise {
    try {
        checkContextException(context)
        val ktValue = result.toKtValue(context)
        if (ktValue !is JsPromise) {
             qjsError("Missing async flag to eval")
        }
        return ktValue
    } catch (e: Exception) {
        JS_FreeValue(context, result)
        throw e
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun checkContextException(context: CPointer<JSContext>) {
    JS_GetException(context).use(context) {
        if (JS_IsNull(this) != 1) {
            throw jsErrorToKtError(context, this)
        }
    }
}