@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "common",
        "brokerage::batch", "brokerage::batchApp", "brokerage::batchDomain",
        "analytics::batch",
        "wallet::api",
        "paper::api", "paper::batch", "paper::domain",
        "subscription::api", "subscription::batch", "subscription::domain",
    }
)
package com.monticker.api.batch;
