rootProject.name = "romanticker"

include(
    "app-api-price",
    "app-api-ticker",
    "app-batch-collector",

    "domain-price",
    "domain-ticker",

    "infra-redis",
    "infra-timescaledb",
    "infra-external",

    "shared-common",
    "shared-config",
)

plugins {
    kotlin("jvm") version "1.9.10" apply false
    id("org.springframework.boot") version "3.2.4" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
}
