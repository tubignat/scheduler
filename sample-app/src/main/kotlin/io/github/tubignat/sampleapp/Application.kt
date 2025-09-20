package io.github.tubignat.sampleapp

import io.github.tubignat.scheduler.SQLitePersistence
import io.github.tubignat.scheduler.newAWSLambdaScheduler
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class SampleAppApplication {
    @Bean
    fun scheduler() = newAWSLambdaScheduler(SQLitePersistence("jdbc:sqlite:data.sqlite"))
}

fun main(args: Array<String>) {
    runApplication<SampleAppApplication>(*args)
}
