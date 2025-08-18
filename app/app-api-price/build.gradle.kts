import java.net.Socket

plugins {
    java
    id("org.springframework.boot") version "3.4.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.polynomeer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":domain:domain-price"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":infra"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

fun waitForRedis(host: String, port: Int, timeoutSeconds: Int = 30) {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    while (System.currentTimeMillis() < deadline) {
        try {
            Socket(host, port).use { return }
        } catch (_: Exception) {
            Thread.sleep(500)
        }
    }
    throw RuntimeException("Redis at $host:$port not available after $timeoutSeconds seconds.")
}

tasks.register("waitForRedis") {
    doLast {
        println("⏳ Waiting for Redis to become available...")
        waitForRedis("localhost", 6379)
        println("✅ Redis is ready!")
    }
}

tasks.register<Exec>("composeUp") {
    workingDir = rootDir
    commandLine = listOf("docker", "compose", "up", "-d")
}

tasks.register("bootWithDocker") {
    dependsOn("composeUp", "waitForRedis", "bootRun")
}
