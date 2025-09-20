import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "1.9.24"
    idea
    `java-library`
    `maven-publish`
    signing
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
}

val GROUP: String by project
val VERSION_NAME: String by project

group = GROUP
version = VERSION_NAME


dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")

    // AWS SDK v2 BOM to manage versions
    implementation(platform("software.amazon.awssdk:bom:2.25.54"))
    implementation("software.amazon.awssdk:lambda")
    implementation("software.amazon.awssdk:iam")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("tmp/emptyJavadoc"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(javadocJar)

            pom {
                name.set(providers.gradleProperty("POM_NAME").orElse("Scheduler"))
                description.set(
                    providers.gradleProperty("POM_DESCRIPTION")
                        .orElse("A lightweight job scheduling library template in Kotlin")
                )
                url.set(providers.gradleProperty("POM_URL").orElse("https://github.com/tubignat/scheduler"))

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set(providers.gradleProperty("POM_DEVELOPER_ID").orElse("tubignat"))
                        name.set(providers.gradleProperty("POM_DEVELOPER_NAME").orElse("Ignat"))
                        url.set(providers.gradleProperty("POM_DEVELOPER_URL").orElse("https://github.com/tubignat"))
                    }
                }
                scm {
                    connection.set(
                        providers.gradleProperty("POM_SCM_CONNECTION")
                            .orElse("scm:git:git://github.com/tubignat/scheduler.git")
                    )
                    developerConnection.set(
                        providers.gradleProperty("POM_SCM_DEV_CONNECTION")
                            .orElse("scm:git:ssh://github.com:tubignat/scheduler.git")
                    )
                    url.set(providers.gradleProperty("POM_SCM_URL").orElse("https://github.com/tubignat/scheduler"))
                }
            }
        }
    }
    repositories {
        maven {
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (VERSION_NAME.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl

            credentials {
                username = (findProperty("ossrhUsername") as String?) ?: System.getenv("OSSRH_USERNAME")
                password = (findProperty("ossrhPassword") as String?) ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing {
    val isRelease = !VERSION_NAME.endsWith("SNAPSHOT")
    setRequired({
        isRelease && (project.hasProperty("signing.keyId") || System.getenv("SIGNING_KEY") != null)
    })

    val inMemoryKey = System.getenv("SIGNING_KEY")
    val inMemoryPwd = System.getenv("SIGNING_PASSWORD")
    if (inMemoryKey != null && inMemoryPwd != null) {
        useInMemoryPgpKeys(inMemoryKey, inMemoryPwd)
    }

    sign(publishing.publications)
}
