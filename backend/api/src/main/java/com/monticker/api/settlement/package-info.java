@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common", "auth::api", "wallet::api", "subscription::pg"}
)
package com.monticker.api.settlement;
