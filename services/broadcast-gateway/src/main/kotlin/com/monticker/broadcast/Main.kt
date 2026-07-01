package com.monticker.broadcast

import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

private val log = LoggerFactory.getLogger("com.monticker.broadcast.Main")

fun main() {
    val port = System.getenv("BROADCAST_PORT")?.toIntOrNull() ?: 9090
    val brokers = System.getenv("KAFKA_BROKERS") ?: "localhost:9092"

    val server = BroadcastServer(port)
    val bridge = KafkaBridge(brokers, server)

    thread(name = "kafka-bridge", isDaemon = true) {
        bridge.run()
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down broadcast gateway")
        bridge.stop()
        server.shutdown()
    })

    server.start()  // blocks
}
