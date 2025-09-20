package io.github.tubignat.sampleapp

import io.github.tubignat.scheduler.AWSLambdaCreateConfig
import io.github.tubignat.scheduler.AWSLambdaPayload
import io.github.tubignat.scheduler.Scheduler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.GetRoleRequest
import java.io.ByteArrayOutputStream
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping
class RunController(private val scheduler: Scheduler<AWSLambdaPayload>) {
    private val mapper = jacksonObjectMapper()

    @PostMapping("/start")
    fun run(@RequestParam payload: String): ResponseEntity<String> {
        val iam = IamClient.builder().region(Region.AWS_GLOBAL).build()
        val role = iam.getRole(GetRoleRequest.builder().roleName("test-role-ifbvhpmm").build()).role().arn()

        val nodeHandler = """
            exports.handler = async (event) => {
                return event;
            }
            """.trimIndent()
        val zipB64 = generateZipBase64(nodeHandler)

        val createCfg = AWSLambdaCreateConfig(role, "nodejs20.x", "index.handler", zipB64)
        val functionName = "new-function-${UUID.randomUUID()}"

        val awsLambdaEvent = mapper.writeValueAsString(AWSLambdaEvent(payload))

        val payload = AWSLambdaPayload(functionName, awsLambdaEvent, createCfg)

        val id = scheduler.create("*/5 * * ? * *", payload, maxExecutions = 5)

        return ResponseEntity.ok(id)
    }

    @GetMapping("/list")
    fun list(): ResponseEntity<String> = ResponseEntity.ok(
        scheduler.list().joinToString("\n") { "${it.first} | ${it.second}" } + "\n"
    )

    @GetMapping("/status")
    fun status(@RequestParam id: String): ResponseEntity<String> {
        val status = scheduler.status(id)
        val executions = scheduler.log(id)

        val result = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object {
            val id = id
            val status = status
            val executions = executions.map {
                object {
                    val timestamp = Instant.ofEpochMilli(it.timestamp).toString()
                    val result = it.result
                }
            }
        })

        return ResponseEntity.ok(result)
    }

    private data class AWSLambdaEvent(val payload: String) : Serializable

    private fun generateZipBase64(content: String): String {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val entry = ZipEntry("index.js")
            zos.putNextEntry(entry)
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            zos.write(bytes, 0, bytes.size)
            zos.closeEntry()
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }
}
