plugins {
}

group = "com.polynomeer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

tasks.withType<Test> {
    useJUnitPlatform()
}

allprojects {
    group = "com.romanticker"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")

    dependencies {
        testImplementation(kotlin("test"))
    }
}

project("app-api").apply {
    apply(plugin = "org.springframework.boot")
    dependencies {
        implementation(project(":shared-common"))
        implementation(project(":shared-config"))
        implementation(project(":domain-price"))
        implementation(project(":domain-ticker"))
        implementation(project(":infra-redis"))
        implementation(project(":infra-timescaledb"))
    }
}

project("app-batch").apply {
    apply(plugin = "org.springframework.boot")
    dependencies {
        implementation(project(":shared-common"))
        implementation(project(":domain-price"))
        implementation(project(":infra-timescaledb"))
    }
}
