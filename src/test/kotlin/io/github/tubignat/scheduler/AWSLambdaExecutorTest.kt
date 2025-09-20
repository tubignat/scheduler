package io.github.tubignat.scheduler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.iam.IamClient
import software.amazon.awssdk.services.iam.model.GetRoleRequest
import software.amazon.awssdk.services.lambda.LambdaClient
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AWSLambdaExecutorTest {
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

    @Test
    fun testInvokeLambdaFunction() {
        val iam = IamClient.builder().region(Region.AWS_GLOBAL).build()
        val role = iam.getRole(GetRoleRequest.builder().roleName("test-role-ifbvhpmm").build()).role().arn()

        val nodeHandler = "exports.handler = async (event) => event;"
        val zipB64 = generateZipBase64(nodeHandler)

        val createCfg = AWSLambdaCreateConfig(role, "nodejs20.x", "index.handler", zipB64)
        val functionName = "scheduler-it-fn-${UUID.randomUUID()}"

        val executor = AWSLambdaExecutor()
        val payload = AWSLambdaPayload(functionName, "{\"hello\":\"world\"}", createCfg)

        try {
            val response = executor.execute(payload)
            assertEquals("{\"hello\":\"world\"}", response)
        } finally {
            LambdaClient.create().deleteFunction { it.functionName(functionName) }
            iam.close()
        }
    }
}
