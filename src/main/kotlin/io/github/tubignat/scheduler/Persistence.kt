package io.github.tubignat.scheduler

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import java.io.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.*

data class SchedulerJob(
    val id: String,
    val cron: Cron,
    val payload: Serializable,
    val status: Status,
    val maxExecutions: Int?,
    val skipped: SkippedExecutions,
    val createdAt: Long
)

interface Persistence {
    fun createJob(cron: String, payload: Serializable, maxExecutions: Int?, skipped: SkippedExecutions): SchedulerJob
    fun getJobs(filterByStatus: List<Status>): List<SchedulerJob>
    fun updateStatus(jobId: String, status: Status)
    fun getJobStatus(jobId: String): Status?
    fun listJobs(limit: Int): List<Pair<String, Status>>

    fun countExecutions(jobId: String): Int
    fun executionLog(jobId: String, limit: Int): List<Execution>
    fun addExecution(jobId: String, execution: Execution)
}

class SQLitePersistence(dbUrl: String) : Persistence {
    private val conn: Connection = DriverManager.getConnection(dbUrl)
    private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS jobs (
                  id VARCHAR PRIMARY KEY,
                  cron VARCHAR NOT NULL,
                  payload BLOB NOT NULL,
                  status VARCHAR NOT NULL,
                  max_executions INTEGER,
                  skipped VARCHAR NOT NULL,
                  created_at INTEGER NOT NULL
                );
                """.trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);")
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS executions (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  job_id VARCHAR NOT NULL,
                  ts INTEGER NOT NULL,
                  result BLOB,
                  error TEXT,
                  FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE
                );
                """.trimIndent()
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_exec_job_id ON executions(job_id);")
        }
    }

    override fun createJob(
        cron: String,
        payload: Serializable,
        maxExecutions: Int?,
        skipped: SkippedExecutions
    ): SchedulerJob {
        val id = UUID.randomUUID().toString()
        val cronObj = cronParser.parse(cron).validate()
        val status = Status.CREATED
        val payloadBytes = serialize(payload)
        val createdAt = System.currentTimeMillis()
        conn.prepareStatement("INSERT INTO jobs(id, cron, payload, status, max_executions, skipped, created_at) VALUES(?,?,?,?,?,?,?)")
            .use { ps ->
                ps.setString(1, id)
                ps.setString(2, cron)
                ps.setBytes(3, payloadBytes)
                ps.setString(4, status.name)
                if (maxExecutions != null) ps.setInt(5, maxExecutions) else ps.setNull(5, java.sql.Types.INTEGER)
                ps.setString(6, skipped.name)
                ps.setLong(7, createdAt)
                ps.executeUpdate()
            }
        return SchedulerJob(id, cronObj, payload, status, maxExecutions, skipped, createdAt)
    }

    override fun getJobs(filterByStatus: List<Status>): List<SchedulerJob> {
        if (filterByStatus.isEmpty()) return emptyList()
        val placeholders = filterByStatus.joinToString(",") { "?" }
        val sql = "SELECT id, cron, payload, status, max_executions, skipped, created_at FROM jobs WHERE status IN ($placeholders)"
        conn.prepareStatement(sql).use { ps ->
            filterByStatus.forEachIndexed { index, status -> ps.setString(index + 1, status.name) }
            ps.executeQuery().use { rs ->
                val res = mutableListOf<SchedulerJob>()
                while (rs.next()) {
                    val id = rs.getString(1)
                    val cronStr = rs.getString(2)
                    val payloadBytes = rs.getBytes(3)
                    val statusStr = rs.getString(4)
                    val maxExec = rs.getInt(5)
                    val skippedStr = rs.getString(6)
                    val createdAt = rs.getLong(7)
                    val maxExecVal = if (rs.wasNull()) null else maxExec

                    val cronObj = cronParser.parse(cronStr).validate()
                    val payload = deserialize(payloadBytes)
                        ?: throw IllegalStateException("Job payload is null for id=$id")
                    val status = Status.valueOf(statusStr)
                    val skipped = SkippedExecutions.valueOf(skippedStr)

                    res += SchedulerJob(id, cronObj, payload, status, maxExecVal, skipped, createdAt)
                }
                return res
            }
        }
    }

    override fun updateStatus(jobId: String, status: Status) {
        conn.prepareStatement("UPDATE jobs SET status=? WHERE id=?").use { ps ->
            ps.setString(1, status.name)
            ps.setString(2, jobId)
            ps.executeUpdate()
        }
    }

    override fun getJobStatus(jobId: String): Status? {
        conn.prepareStatement("SELECT status FROM jobs WHERE id=?").use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) Status.valueOf(rs.getString(1)) else null
            }
        }
    }

    override fun listJobs(limit: Int): List<Pair<String, Status>> {
        conn.prepareStatement("SELECT id, status FROM jobs LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val res = mutableListOf<Pair<String, Status>>()
                while (rs.next()) {
                    res += rs.getString(1) to Status.valueOf(rs.getString(2))
                }
                return res
            }
        }
    }

    override fun countExecutions(jobId: String): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM executions WHERE job_id=?").use { ps ->
            ps.setString(1, jobId)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    override fun executionLog(jobId: String, limit: Int): List<Execution> {
        conn.prepareStatement("SELECT ts, result, error FROM executions WHERE job_id=? ORDER BY ts DESC LIMIT ?")
            .use { ps ->
                ps.setString(1, jobId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val res = mutableListOf<Execution>()
                    while (rs.next()) {
                        val ts = rs.getLong(1)
                        val resultBytes = rs.getBytes(2)
                        val errorTxt = rs.getString(3)
                        val result = deserialize(resultBytes)
                        val error = if (errorTxt != null) RuntimeException(errorTxt) else null
                        res += Execution(ts, result, error)
                    }
                    return res
                }
            }
    }

    override fun addExecution(jobId: String, execution: Execution) {
        conn.prepareStatement("INSERT INTO executions(job_id, ts, result, error) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, jobId)
            ps.setLong(2, execution.timestamp)
            ps.setBytes(3, serialize(execution.result))
            ps.setString(4, execution.error?.toString())
            ps.executeUpdate()
        }
    }

    private fun serialize(obj: Serializable?): ByteArray? {
        if (obj == null) return null
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { oos -> oos.writeObject(obj) }
        return baos.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserialize(bytes: ByteArray?): Serializable? {
        if (bytes == null) return null
        ByteArrayInputStream(bytes).use { bais ->
            ObjectInputStream(bais).use { ois ->
                return ois.readObject() as? Serializable
            }
        }
    }
}
