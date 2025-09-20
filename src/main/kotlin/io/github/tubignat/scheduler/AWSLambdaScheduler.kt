package io.github.tubignat.scheduler

import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest
import software.amazon.awssdk.services.lambda.model.FunctionCode
import software.amazon.awssdk.services.lambda.model.GetFunctionRequest
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.InvokeResponse
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException
import software.amazon.awssdk.services.lambda.model.Runtime
import java.io.Serializable
import java.util.Base64

data class AWSLambdaCreateConfig(
    val roleArn: String,
    val runtime: String,
    val handler: String,
    val zipFileBase64: String? = null,
    val s3Bucket: String? = null,
    val s3Key: String? = null,
    val timeout: Int? = null,
    val memorySize: Int? = null
) : Serializable

data class AWSLambdaPayload(
    val functionName: String,
    val eventJSON: String,
    val createIfNotExists: AWSLambdaCreateConfig? = null
) : Serializable

class AWSLambdaExecutor {
    private val client: LambdaClient = LambdaClient.create()

    fun execute(payload: AWSLambdaPayload): Serializable? {
        if (payload.createIfNotExists != null) {
            ensureFunctionExists(payload.functionName, payload.createIfNotExists)
        }

        val request = InvokeRequest.builder()
            .functionName(payload.functionName)
            .payload(SdkBytes.fromUtf8String(payload.eventJSON))
            .build()

        val response: InvokeResponse = client.invoke(request)

        return response.payload()?.asUtf8String()
    }

    private fun ensureFunctionExists(functionName: String, createConfig: AWSLambdaCreateConfig) {
        val exists = try {
            client.getFunction(GetFunctionRequest.builder().functionName(functionName).build())
            true
        } catch (_: ResourceNotFoundException) {
            false
        }

        if (exists) return

        val codeBuilder = FunctionCode.builder()
        when {
            createConfig.zipFileBase64 != null -> {
                val bytes = Base64.getDecoder().decode(createConfig.zipFileBase64)
                codeBuilder.zipFile(SdkBytes.fromByteArray(bytes))
            }

            createConfig.s3Bucket != null && createConfig.s3Key != null -> {
                codeBuilder.s3Bucket(createConfig.s3Bucket).s3Key(createConfig.s3Key)
            }

            else -> throw IllegalArgumentException("To create a Lambda function, provide either zipFileBase64 or s3Bucket+s3Key in AWSLambdaCreateConfig")
        }

        val builder = CreateFunctionRequest.builder()
            .functionName(functionName)
            .role(createConfig.roleArn)
            .runtime(Runtime.fromValue(createConfig.runtime))
            .handler(createConfig.handler)
            .code(codeBuilder.build())

        createConfig.timeout?.let { builder.timeout(it) }
        createConfig.memorySize?.let { builder.memorySize(it) }

        client.createFunction(builder.build())

        waitForFunctionToBecomeActive(functionName)
    }

    private fun waitForFunctionToBecomeActive(functionName: String) {
        val request = GetFunctionRequest.builder().functionName(functionName).build()
        val maxWaitTimeMs = 60_000L
        var elapsedTimeMs = 0L

        while (true) {
            val response = client.getFunction(request)
            if (response.configuration().state().toString() == "Active") return
            if (elapsedTimeMs >= maxWaitTimeMs) {
                throw IllegalStateException("Timeout waiting for Lambda function '$functionName' to become active")
            }

            Thread.sleep(100)
            elapsedTimeMs += 100
        }
    }
}

fun newAWSLambdaScheduler(persistence: Persistence): Scheduler<AWSLambdaPayload> {
    val executor = AWSLambdaExecutor()
    return SchedulerImpl(persistence) {
        executor.execute(it)
    }
}
