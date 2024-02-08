package com.dokar.quickjs.test

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncFunctionsTest {
    @Test
    fun runAsyncResolved() = runTest {
        quickJs {
            asyncFunction("fetch") { "Hello" }

            assertEquals("Hello", evaluate("await fetch()"))
        }
    }

    @Test
    fun runAsyncRejected() = runTest {
        quickJs {
            asyncFunction("fetch") { error("Unavailable") }

            assertFails {
                evaluate<Any?>("await fetch()")
            }.also {
                assertContains(it.message!!, "Unavailable")
            }
        }
    }

    @Test
    fun runDelayAsPromise() = runTest {
        quickJs {
            asyncFunction("delay") { delay(it[0] as Long) }

            var result: String? = null
            launch {
                result = evaluate<String>("await delay(1000); 'OK'")
            }
            advanceTimeBy(500)
            assertEquals(null, result)
            advanceTimeBy(501)
            assertEquals("OK", result)
        }
    }

    @Test
    fun runMultiplePromises() = runTest {
        quickJs {
            var result: Any? = null

            function("update") { result = it[0] }

            asyncFunction("delay") { delay(it[0] as Long) }

            val evalJob = launch {
                evaluate<String>(
                    """
                    update("Started");
                    await delay(1000);
                    update("Next");
                    await delay(1000);
                    update("Done");
                """.trimIndent()
                )
            }

            advanceTimeBy(10)
            assertEquals("Started", result)
            advanceTimeBy(1001)
            assertEquals("Next", result)
            advanceTimeBy(1001)
            assertEquals("Done", result)

            evalJob.join()
        }
    }

    @Test
    fun runPromiseDotAll() = runTest {
        quickJs {
            asyncFunction("delay") { delay(it[0] as Long) }

            var result: String? = null
            val evalJob = launch {
                result = evaluate<String>(
                    """
                    await Promise.all([delay(1000), delay(2000)]);
                    "OK";
                """.trimIndent()
                )
            }
            advanceTimeBy(500)
            assertEquals(null, result)
            advanceTimeBy(1501)
            assertEquals("OK", result)
            evalJob.join()
        }
    }

    @Test
    fun runPromiseDotAllWithOneRejected() = runTest {
        quickJs {
            var delayedCount = 0
            asyncFunction("delay") {
                delay(it[0] as Long)
                delayedCount++
            }
            asyncFunction("fail") {
                delay(1500)
                error("Fails")
            }

            assertFails {
                evaluate<String>(
                    """
                    await Promise.all([delay(1000), delay(2000), fail()]);
                    "OK";
                """.trimIndent()
                )
            }
            assertEquals(1, delayedCount)
        }
    }

    @Test
    fun compileAndEvalAsync() = runTest {
        quickJs {
            asyncFunction("delay") { delay(it[0] as Long) }

            val bytecode = compile(
                code = """
                    await delay(100);
                    "OK";
                """.trimIndent()
            )
            assertEquals("OK", execute(bytecode))
        }
    }

    @Test
    fun compileAndEvalAsyncModule() = runTest {
        quickJs {
            var result: String? = null
            function("returns") { result = it.first() as String }

            asyncFunction("delay") { delay(it[0] as Long) }

            val bytecode = compile(
                code = """
                    await delay(100);
                    returns("OK");
                """.trimIndent(),
                asModule = true,
            )
            execute<Any?>(bytecode)
            assertEquals("OK", result)
        }
    }

    @Test
    fun cancelParentCoroutine() = runTest {
        var instance: QuickJs? = null
        launch {
            val parentScope = this
            launch {
                delay(500)
                parentScope.cancel()
            }

            quickJs {
                instance = this
                asyncFunction("delay") {
                    delay(it[0] as Long)
                }

                evaluate<String>("delay(1000); 'OK'")

                assertTrue(false)
            }
        }.join()
        assertTrue(instance!!.isClosed)
    }
}