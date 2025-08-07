rootProject.name = "romanticker"

include(
    "app:app-api-price",
    "app:app-batch-collector",

    "domain:domain-price",

    "infra:infra-redis",
    "infra:infra-timescaledb",
    "infra:infra-external",

    "shared"
)

plugins {
    kotlin("jvm") version "1.9.23" apply false
    id("org.springframework.boot") version "3.4.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}
