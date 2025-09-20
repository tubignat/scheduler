plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    id("org.springframework.boot") version "3.3.2"
    id("io.spring.dependency-management") version "1.1.6"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(project(":"))

    implementation(platform("software.amazon.awssdk:bom:2.25.54"))
    implementation("software.amazon.awssdk:lambda")
    implementation("software.amazon.awssdk:iam")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
