package io.github.tubignat.scheduler

import com.cronutils.model.Cron
import com.cronutils.model.time.ExecutionTime
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

enum class SkippedExecutions { IGNORE, EXECUTE_ONE, EXECUTE_ALL }

interface Scheduler<T : Serializable> : AutoCloseable {
    fun create(
        cron: String,
        payload: T,
        maxExecutions: Int? = null,
        skipped: SkippedExecutions = SkippedExecutions.EXECUTE_ONE
    ): String

    fun cancel(id: String)
    fun status(id: String): Status?
    fun list(limit: Int = 10): List<Pair<String, Status>>
    fun log(id: String, limit: Int = 10): List<Execution>
    override fun close()
}

enum class Status { CREATED, SCHEDULED, RUNNING, FINISHED, CANCELLED }

data class Execution(val timestamp: Long, val result: Serializable?, val error: Throwable? = null)

class SchedulerImpl<T : Serializable>(
    private val persistence: Persistence,
    private val execute: (T) -> Serializable?
) : Scheduler<T> {
    private val coroutines = ConcurrentHashMap<String, Job>()
    private val logger = LoggerFactory.getLogger(SchedulerImpl::class.java)
    private var closed = false

    init {
        persistence
            .getJobs(listOf(Status.CREATED, Status.SCHEDULED, Status.RUNNING))
            .forEach { startJob(it) }
    }

    override fun create(cron: String, payload: T, maxExecutions: Int?, skipped: SkippedExecutions): String {
        val job = persistence.createJob(cron, payload, maxExecutions, skipped)
        startJob(job)
        return job.id
    }

    override fun cancel(id: String) {
        persistence.updateStatus(id, Status.CANCELLED)
        coroutines[id]?.cancel()
        coroutines.remove(id)
    }

    override fun status(id: String) = persistence.getJobStatus(id)

    override fun list(limit: Int) = persistence.listJobs(limit)

    override fun log(id: String, limit: Int) = persistence.executionLog(id, limit)

    override fun close() {
        closed = true

        val allJobs = coroutines.values.toList()
        allJobs.forEach { it.cancel() }
        runBlocking { allJobs.joinAll() }
        coroutines.clear()
    }

    private fun startJob(job: SchedulerJob) {
        if (closed) return

        coroutines[job.id] = CoroutineScope(Dispatchers.Default).launch {
            handleSkippedExecutions(job)
            var executions = persistence.countExecutions(job.id)

            while (true) {
                val delay = nextDelay(job.cron)
                if (delay == null) {
                    persistence.updateStatus(job.id, Status.FINISHED)
                    coroutines.remove(job.id)
                    break
                }

                persistence.updateStatus(job.id, Status.SCHEDULED)

                delay(delay)
                runExecution(job)

                executions++
                if (job.maxExecutions != null && executions >= job.maxExecutions) {
                    persistence.updateStatus(job.id, Status.FINISHED)
                    coroutines.remove(job.id)
                    break
                }
            }
        }

        // the scheduler closed after the job was created, need to cancel it
        if (closed) {
            coroutines[job.id]?.cancel()
        }
    }

    private fun runExecution(job: SchedulerJob) {
        try {
            persistence.updateStatus(job.id, Status.RUNNING)
            logger.info("Executing job ${job.id}")

            @Suppress("UNCHECKED_CAST")
            val result = execute(job.payload as T)

            persistence.addExecution(job.id, Execution(Instant.now().toEpochMilli(), result))
        } catch (e: Throwable) {
            persistence.addExecution(job.id, Execution(Instant.now().toEpochMilli(), null, e))
        }
    }

    private fun handleSkippedExecutions(job: SchedulerJob) {
        // if certain executions were to occur while the service was offline, they may have been skipped.
        // i.e., imagine this job schedule:
        //
        // t1 - t2 - t3 - [ t4 - t5 - t6 ] - t7 - t8 - t9
        //                [----deploy----]
        //
        // this function decides whether to execute t4 — t6 upon restart depending on the job configuration.

        if (job.skipped == SkippedExecutions.IGNORE) return

        val lastExecutionTimestamp = persistence.executionLog(job.id, 1).firstOrNull()?.timestamp ?: job.createdAt

        val skippedExecutions = ExecutionTime.forCron(job.cron).countExecutions(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastExecutionTimestamp), ZoneId.systemDefault()),
            ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())
        )

        if (skippedExecutions == 0) return

        val toRetry = if (job.skipped == SkippedExecutions.EXECUTE_ONE) 1 else skippedExecutions
        val toRetryLimitedByMaxExecutions = if (job.maxExecutions == null) toRetry else min(
            toRetry,
            job.maxExecutions - persistence.countExecutions(job.id)
        )

        logger.info("Executing $toRetryLimitedByMaxExecutions skipped executions")

        for (i in 1..toRetryLimitedByMaxExecutions) {
            runExecution(job)
        }
    }

    private fun nextDelay(cron: Cron, from: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Long? {
        val delay = ExecutionTime.forCron(cron).timeToNextExecution(from).orElse(null) ?: return null
        return delay.toMillis()
    }
}
