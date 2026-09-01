@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "common",
        "stock::api", "stock::domain",
        "event::api", "event::domain",
        "news::api", "news::domain",
        "marketdata::api", "marketdata::domain",
    }
)
package com.monticker.api.ai;
