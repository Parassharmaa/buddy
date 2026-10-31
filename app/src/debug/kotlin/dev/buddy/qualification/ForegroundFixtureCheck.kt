package dev.buddy.qualification

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.buddy.core.ActionExecutor
import dev.buddy.core.ActionJournal
import dev.buddy.core.ActionRecord
import dev.buddy.core.Approval
import dev.buddy.core.DispatchGate
import dev.buddy.core.Effect
import dev.buddy.core.ExecutionResult
import dev.buddy.core.Observation
import dev.buddy.phone.FixtureWorkspace
import dev.buddy.shell.IBuddyHelper
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class PhysicalFixtureSample(val focused: Boolean, val edits: Int, val containsPrivateMarker: Boolean)
internal data class ForegroundFixtureResult(val concurrentTypingObserved: Boolean)

// An ephemeral engineering spike: fixed DRAFT/NAVIGATE operations in the signed offline fixture.
// Durable agent runs and their approvals continue to use EncryptedStore's journal.
internal object ForegroundFixtureCheck {
    suspend fun run(
        context: Context,
        helper: IBuddyHelper,
        visible: () -> Boolean,
        physical: suspend (String) -> PhysicalFixtureSample,
        stage: suspend (String) -> Unit
    ): ForegroundFixtureResult = withContext(Dispatchers.IO) {
        check(visible())
        withTimeout(15_000) {
            val run = UUID.randomUUID().toString()
            val marker = "Buddy fixture ${run.take(8)}"
            val observations = linkedMapOf<String, Observation>()
            val journal = Journal()
            Drain().use { frames ->
                var workspace: FixtureWorkspace? = null
                try {
                    stage("Opening the private fixture")
                    val opened = FixtureWorkspace.open(
                        context,
                        helper,
                        ActionExecutor(journal, SystemClock::elapsedRealtime),
                        frames.surface,
                        run,
                        recordEvidence = {
                            check(observations.size < 32)
                            observations[it.id] = it
                        }
                    )
                    workspace = opened
                    stage("Waiting for the fixture editor")
                    withTimeout(5_000) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(visible())
                            val observation = opened.observe()
                            if (!observation.protected && !observation.credentialSurface &&
                                observation.nodes.count { it.editable && !it.password } == 1
                            ) {
                                break
                            }
                            delay(50)
                        }
                    }
                    val before = physical(marker)
                    check(before.focused && !before.containsPrivateMarker)
                    val active = currentCoroutineContext()
                    suspend fun gate() = DispatchGate(
                        cancelled = !active.isActive,
                        paused = !visible(),
                        locked = false,
                        supervised = visible(),
                        ownsApp = true,
                        leaseDeadline = Long.MAX_VALUE,
                        authorizationDeadline = Long.MAX_VALUE,
                        takeover = false
                    )
                    repeat(3) { index ->
                        stage("Checking private Unicode edit ${index + 1} of 3")
                        currentCoroutineContext().ensureActive()
                        check(visible())
                        val observation = opened.observe()
                        val text = "$marker · नमस्ते · こんにちは · café · 😀 · $index"
                        val action = opened.prepareDraft(observation, text)
                        check(opened.execute(action, gate = ::gate) is ExecutionResult.Verified)
                        val sample = physical(marker)
                        check(sample.focused && !sample.containsPrivateMarker)
                        delay(750)
                    }
                    val after = physical(marker)
                    check(after.focused && !after.containsPrivateMarker)
                    stage("Checking protected-page suppression")
                    val page = opened.prepareSecurePage(opened.observe())
                    check(opened.execute(page, gate = ::gate) is ExecutionResult.Verified)
                    val protected = opened.observe()
                    check(protected.protected && protected.nodes.isEmpty())
                    ForegroundFixtureResult(after.edits > before.edits)
                } finally {
                    workspace?.close()
                    observations.clear()
                    journal.clear()
                }
            }
        }
    }

    internal class Journal : ActionJournal {
        private val records = linkedMapOf<String, ActionRecord>()
        private val barriers = mutableSetOf<String>()
        override suspend fun unresolved(operationKey: String) = operationKey in barriers
        override suspend fun begin(record: ActionRecord, approval: Approval?) {
            check(approval == null && record.proposal.effect in setOf(Effect.DRAFT, Effect.NAVIGATE))
            check(record.proposal.app == "dev.buddy.fixture" && records.size < 8)
            records[record.proposal.id] = record
        }
        override suspend fun update(record: ActionRecord) {
            check(record.proposal.id in records)
            records[record.proposal.id] = record
        }
        override suspend fun markUncertain(record: ActionRecord) {
            update(record)
            barriers.add(record.proposal.operationKey)
        }
        fun clear() {
            records.clear()
            barriers.clear()
        }
    }

    internal class Drain : Closeable {
        private val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        private val thread = HandlerThread("buddy-engineering-frames").apply { start() }
        private val lock = Any()
        private var closed = false
        val surface get() = reader.surface
        init {
            reader.setOnImageAvailableListener({ source ->
                synchronized(lock) { if (!closed) source.acquireLatestImage()?.close() }
            }, Handler(thread.looper))
        }
        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                reader.setOnImageAvailableListener(null, null)
                reader.close()
            }
            thread.quitSafely()
        }
    }
}
