package io.securitycam.level1.detection.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `test/analysis_dispatcher_test.dart`. */
class AnalysisDispatcherTest {

    private fun scope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())

    @Test
    fun processesInputsInOrderWhenFast() = runBlocking {
        val processed = mutableListOf<Int>()
        val dispatcher = AnalysisDispatcher<Int>(scope(), process = { processed.add(it) })
        for (i in 0 until 5) {
            dispatcher.add(i)
            yield()
        }
        dispatcher.dispose()
        assertEquals(listOf(0, 1, 2, 3, 4), processed)
    }

    @Test
    fun latestWinsPendingSlotIsReplacedWhileBusy() = runBlocking {
        val processed = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = AnalysisDispatcher<Int>(
            scope(),
            process = { i ->
                if (i == 0) {
                    processed.add(i)
                    gate.await()
                } else {
                    processed.add(i)
                }
            },
        )
        dispatcher.add(0)
        yield()
        dispatcher.add(1)
        dispatcher.add(2)
        gate.complete(Unit)
        yield()
        dispatcher.dispose()
        assertEquals(listOf(0, 2), processed)
    }

    @Test
    fun burstOfAddsYieldsMaxConcurrency1() = runBlocking {
        var concurrent = 0
        var maxConcurrent = 0
        val dispatcher = AnalysisDispatcher<Int>(
            scope(),
            process = {
                concurrent++
                if (concurrent > maxConcurrent) maxConcurrent = concurrent
                delay(1)
                concurrent--
            },
        )
        for (i in 0 until 50) dispatcher.add(i)
        dispatcher.dispose()
        assertEquals(1, maxConcurrent)
    }

    @Test
    fun throwingProcessIsCaughtOnErrorFiresAndLoopContinues() = runBlocking {
        val processed = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val dispatcher = AnalysisDispatcher<Int>(
            scope(),
            process = {
                if (it == 1) throw IllegalStateException("boom")
                processed.add(it)
            },
            onError = { errors.add(it) },
        )
        dispatcher.add(0)
        yield()
        dispatcher.add(1)
        yield()
        dispatcher.add(2)
        yield()
        dispatcher.dispose()
        assertEquals(listOf("boom"), errors.map { it.message })
        assertEquals(listOf(0, 2), processed)
    }

    @Test
    fun disposeClearsPendingSlotAndStopsTheLoop() = runBlocking {
        val processed = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        val dispatcher = AnalysisDispatcher<Int>(
            scope(),
            process = {
                if (it == 0) {
                    processed.add(it)
                    gate.await()
                } else {
                    processed.add(it)
                }
            },
        )
        dispatcher.add(0)
        yield()
        dispatcher.add(1)
        dispatcher.add(2)
        val disposeJob = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.dispose() }
        gate.complete(Unit)
        disposeJob.join()
        assertEquals(listOf(0), processed)
        dispatcher.add(3)
        dispatcher.dispose()
        assertEquals(listOf(0), processed)
    }
}