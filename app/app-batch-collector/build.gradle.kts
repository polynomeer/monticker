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
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
