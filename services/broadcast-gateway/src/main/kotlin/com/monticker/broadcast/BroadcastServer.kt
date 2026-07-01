package com.monticker.broadcast

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Netty 기반 시세 브로드캐스트 서버.
 *
 * Spring STOMP를 거치지 않고 클라이언트와 직접 WebSocket 프레임을 주고받는다.
 * EventLoopGroup(리액터 패턴)은 적은 수의 스레드로 수많은 연결의 I/O를 논블로킹
 * 처리하므로, "많은 연결에 같은 데이터를 자주 밀어줘야 하는" 시세 브로드캐스트
 * 워크로드에 적합하다. 자세한 배경은 docs/technical/kafka-tick-pipeline.md 참고.
 *
 * 클라이언트 프로토콜:
 *   연결 후 텍스트 프레임으로 {"action":"subscribe","stockId":2} 전송 → 구독 등록
 *   {"action":"unsubscribe","stockId":2} → 구독 해제
 *   서버는 market.ticks/market.events에서 받은 메시지를 그대로 해당 stockId
 *   구독자들에게 중계한다.
 */
class BroadcastServer(private val port: Int) {
    private val log = LoggerFactory.getLogger(javaClass)

    // stockId -> 구독 중인 채널 집합. "ALL"은 stockId 필터 없이 전체 구독하는 클라이언트.
    private val subscriptions = ConcurrentHashMap<String, MutableSet<Channel>>()

    private lateinit var bossGroup: NioEventLoopGroup
    private lateinit var workerGroup: NioEventLoopGroup

    fun start() {
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(HttpServerCodec())
                        .addLast(HttpObjectAggregator(65536))
                        .addLast(WebSocketServerProtocolHandler("/ws"))
                        .addLast(SubscriptionHandler(subscriptions))
                }
            })

        val channel = bootstrap.bind(port).sync().channel()
        log.info("Broadcast gateway listening on ws://0.0.0.0:{}/ws", port)
        channel.closeFuture().sync()
    }

    fun shutdown() {
        workerGroup.shutdownGracefully()
        bossGroup.shutdownGracefully()
    }

    /** stockId 채널과 "ALL" 채널 양쪽 구독자에게 메시지를 보낸다. */
    fun broadcast(stockId: String, json: String) {
        val frame = { TextWebSocketFrame(json) }
        subscriptions[stockId]?.forEach { it.writeAndFlush(frame()) }
        subscriptions["ALL"]?.forEach { it.writeAndFlush(frame()) }
    }
}

private class SubscriptionHandler(
    private val subscriptions: ConcurrentHashMap<String, MutableSet<Channel>>,
) : SimpleChannelInboundHandler<TextWebSocketFrame>() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

    override fun channelRead0(ctx: ChannelHandlerContext, msg: TextWebSocketFrame) {
        runCatching {
            val node = mapper.readTree(msg.text())
            val action = node["action"]?.asText() ?: return
            val key = node["stockId"]?.asText() ?: "ALL"
            when (action) {
                "subscribe" -> subscriptions.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(ctx.channel())
                "unsubscribe" -> subscriptions[key]?.remove(ctx.channel())
                else -> log.debug("알 수 없는 action: {}", action)
            }
        }.onFailure { log.warn("구독 메시지 처리 실패: {}", it.message) }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        subscriptions.values.forEach { it.remove(ctx.channel()) }
        super.channelInactive(ctx)
    }
}
