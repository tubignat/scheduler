package io.github.tubignat.scheduler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Serializable

class SQLitePersistenceTest {

    private fun newPersistence(): Persistence = SQLitePersistence("jdbc:sqlite::memory:")

    private val cronEverySecond = "*/1 * * * * ?"

    @Test
    fun createJobShouldPersistAndExposeFields() {
        val p = newPersistence()
        val job = p.createJob(cronEverySecond, "payload", null, SkippedExecutions.EXECUTE_ONE)

        assertNotNull(job.id)
        assertEquals(Status.CREATED, job.status)
        assertEquals("payload", job.payload)
        assertNotNull(job.cron)
        assertEquals(SkippedExecutions.EXECUTE_ONE, job.skipped)
        assertNull(job.maxExecutions)
        assertTrue(job.createdAt > 0)

        assertEquals(Status.CREATED, p.getJobStatus(job.id))

        val listed = p.listJobs(10)
        assertEquals(1, listed.size)
        assertEquals(job.id, listed.first().first)
        assertEquals(Status.CREATED, listed.first().second)

        val fetched = p.getJobs(listOf(Status.CREATED))
        assertEquals(1, fetched.size)
        assertEquals(job.id, fetched.first().id)
        assertEquals("payload", fetched.first().payload)
        assertEquals(SkippedExecutions.EXECUTE_ONE, fetched.first().skipped)
    }

    @Test
    fun updateStatusShouldBeReflectedInQueries() {
        val p = newPersistence()
        val job = p.createJob(cronEverySecond, "p", 5, SkippedExecutions.IGNORE)

        assertEquals(Status.CREATED, p.getJobStatus(job.id))
        p.updateStatus(job.id, Status.SCHEDULED)
        assertEquals(Status.SCHEDULED, p.getJobStatus(job.id))

        val scheduled = p.getJobs(listOf(Status.SCHEDULED))
        assertTrue(scheduled.any { it.id == job.id })

        p.updateStatus(job.id, Status.CANCELLED)
        assertEquals(Status.CANCELLED, p.getJobStatus(job.id))
    }

    @Test
    fun countExecutionsAndExecutionLogShouldWork() {
        val p = newPersistence()
        val job = p.createJob(cronEverySecond, "p", null, SkippedExecutions.EXECUTE_ONE)

        assertEquals(0, p.countExecutions(job.id))

        val t1 = System.currentTimeMillis()
        p.addExecution(job.id, Execution(t1, "r1" as Serializable))
        val t2 = System.currentTimeMillis()
        p.addExecution(job.id, Execution(t2, null, RuntimeException("boom")))

        assertEquals(2, p.countExecutions(job.id))

        val log = p.executionLog(job.id, 10)
        assertEquals(2, log.size)

        val first = log[0]
        assertEquals(t1, first.timestamp)
        assertEquals("r1", first.result)
        assertNull(first.error)

        val second = log[1]
        assertEquals(t2, second.timestamp)
        assertNull(second.result)
        assertNotNull(second.error)
        assertTrue(second.error!!.message!!.contains("boom"))
    }

    @Test
    fun listJobsShouldRespectLimitAndOrder() {
        val p = newPersistence()
        val j1 = p.createJob(cronEverySecond, "a", null, SkippedExecutions.EXECUTE_ONE)
        val j2 = p.createJob(cronEverySecond, "b", null, SkippedExecutions.EXECUTE_ONE)
        val j3 = p.createJob(cronEverySecond, "c", null, SkippedExecutions.EXECUTE_ONE)

        val listed = p.listJobs(2)
        assertEquals(2, listed.size)
        assertEquals(j3.id, listed[0].first)
        assertEquals(j2.id, listed[1].first)
    }

    @Test
    fun getJobsShouldFilterByMultipleStatuses() {
        val p = newPersistence()
        val j1 = p.createJob(cronEverySecond, "one", null, SkippedExecutions.EXECUTE_ONE)
        val j2 = p.createJob(cronEverySecond, "two", null, SkippedExecutions.EXECUTE_ONE)
        val j3 = p.createJob(cronEverySecond, "three", null, SkippedExecutions.EXECUTE_ONE)

        p.updateStatus(j2.id, Status.RUNNING)
        p.updateStatus(j3.id, Status.CANCELLED)

        val res = p.getJobs(listOf(Status.CREATED, Status.RUNNING))
        val map = res.associateBy { it.id }

        assertTrue(map.containsKey(j1.id))
        assertTrue(map.containsKey(j2.id))
        assertFalse(map.containsKey(j3.id))

        assertEquals("one", map[j1.id]!!.payload)
        assertEquals("two", map[j2.id]!!.payload)
        assertNotNull(map[j1.id]!!.cron)
        assertNotNull(map[j2.id]!!.cron)
    }
}
