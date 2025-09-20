package io.github.tubignat.scheduler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ScheduleTest {

    private fun waitUntil(timeout: Duration = 5.seconds, condition: () -> Boolean): Boolean {
        val deadline = Instant.now().plusSeconds(timeout.inWholeSeconds)
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun newScheduler(calls: MutableList<String>): Scheduler<String> =
        SchedulerImpl(SQLitePersistence("jdbc:sqlite::memory:")) { payload: String ->
            calls.add(payload)
            null
        }

    @Test
    fun schedulerShouldRespectLifetime() {
        val executions = mutableListOf<String>()
        val scheduler: Scheduler<String> = newScheduler(executions)
        try {
            val id = scheduler.create("*/1 * * * * ?", "test", maxExecutions = 3)

            val finished = waitUntil {
                scheduler.status(id) == Status.FINISHED
            }
            assertTrue(finished)
            assertEquals(3, executions.size)
            assertEquals("test", executions[0])
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun cancelShouldCancelJob() {
        val executions = mutableListOf<String>()
        val scheduler: Scheduler<String> = newScheduler(executions)
        try {
            val id = scheduler.create("*/1 * * * * ?", "p")

            waitUntil { scheduler.status(id) == Status.SCHEDULED || scheduler.status(id) == Status.RUNNING }

            scheduler.cancel(id)

            val cancelled = waitUntil { scheduler.status(id) == Status.CANCELLED }
            assertTrue(cancelled)
            val executedUponCancellation = executions.size
            Thread.sleep(3000)
            assertEquals(executedUponCancellation, executions.size)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun listShouldRespectLimit() {
        val executions = mutableListOf<String>()
        val scheduler: Scheduler<String> = newScheduler(executions)
        try {
            val ids = (1..3).map { i ->
                scheduler.create("*/1 * * * * ?", i.toString(), maxExecutions = 1)
            }

            val allAppeared = waitUntil {
                ids.all { id -> scheduler.status(id) != null }
            }
            assertTrue(allAppeared)

            val limited = scheduler.list(limit = 2)
            assertEquals(2, limited.size)
            assertTrue(limited.all { it.first in ids })
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun closeShouldCancelExistingAndPreventNewJobs() {
        val executions = mutableListOf<String>()
        val scheduler: Scheduler<String> = newScheduler(executions)
        try {
            val id1 = scheduler.create("*/1 * * * * ?", "x")

            waitUntil { scheduler.status(id1) == Status.SCHEDULED || scheduler.status(id1) == Status.RUNNING }

            scheduler.close()

            val execsAtClose = executions.size
            Thread.sleep(1500)
            assertEquals(execsAtClose, executions.size)

            val id2 = scheduler.create("*/1 * * * * ?", "y", maxExecutions = 1)

            Thread.sleep(1500)
            assertEquals(execsAtClose, executions.size)
            assertEquals(Status.CREATED, scheduler.status(id2))
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun cronSpecificTimestampShouldExecuteOnce() {
        val executions = mutableListOf<String>()
        val scheduler: Scheduler<String> = newScheduler(executions)
        val target = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault()).plusSeconds(3).withNano(0)
        val cron =
            "${target.second} ${target.minute} ${target.hour} ${target.dayOfMonth} ${target.monthValue} ? ${target.year}"
        try {
            val id = scheduler.create(cron, "x")
            val finished = waitUntil { scheduler.status(id) == Status.FINISHED }
            assertTrue(finished)
            assertEquals(1, executions.size)
        } finally {
            scheduler.close()
        }
    }
}
