rootProject.name = "romanticker"

include(
    "app:app-api-price",
    "app:app-api-ticker",
    "app:app-batch-collector",

    "domain:domain-price",
    "domain:domain-ticker",

    "infra:infra-redis",
    "infra:infra-timescaledb",
    "infra:infra-external",

    "shared:shared-common",
    "shared:shared-config",
)

plugins {
    kotlin("jvm") version "1.9.23" apply false
    id("org.springframework.boot") version "3.4.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}
