package sttp.tapir.server.netty.internal

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.{Channel, ChannelFuture, ChannelHandler, ChannelInitializer, ChannelOption, EventLoopGroup}
import sttp.tapir.server.netty.NettyConfig

import java.net.{InetSocketAddress, SocketAddress}

object NettyBootstrap {

  def apply[F[_]](
      nettyConfig: NettyConfig,
      handler: => NettyServerHandler[F],
      eventLoopGroup: EventLoopGroup,
      overrideSocketAddress: Option[SocketAddress]
  ): ChannelFuture = {
    val httpBootstrap = new ServerBootstrap()

    val connectionCounterOpt = nettyConfig.maxConnections.map(max => new NettyConnectionCounter(max))
    httpBootstrap
      .group(eventLoopGroup)
      .channel(nettyConfig.eventLoopConfig.serverChannel)
      .childHandler(new ChannelInitializer[Channel] {
        override def initChannel(ch: Channel): Unit = {
          val nettyConfigBuilder = nettyConfig.initPipeline(nettyConfig)
          nettyConfigBuilder(ch.pipeline(), handler)
          ch.pipeline().addLast(new UnhandledExceptionHandler)

          connectionCounterOpt.map(counter => {
            ch.pipeline().addFirst(counter)
          })
        }
      })
      .option[java.lang.Integer](ChannelOption.SO_BACKLOG, nettyConfig.socketBacklog) // https://github.com/netty/netty/issues/1692
      .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, nettyConfig.socketKeepAlive) // https://github.com/netty/netty/issues/1692
      .childOption[java.lang.Boolean](ChannelOption.SO_REUSEADDR, nettyConfig.socketConfig.reuseAddress)
      .childOption[java.lang.Boolean](ChannelOption.TCP_NODELAY, nettyConfig.socketConfig.tcpNoDelay)

    nettyConfig.socketConfig.receiveBuffer.foreach(i => httpBootstrap.childOption[java.lang.Integer](ChannelOption.SO_RCVBUF, i))
    nettyConfig.socketConfig.sendBuffer.foreach(i => httpBootstrap.childOption[java.lang.Integer](ChannelOption.SO_SNDBUF, i))
    nettyConfig.socketConfig.typeOfService.foreach(i => httpBootstrap.childOption[java.lang.Integer](ChannelOption.IP_TOS, i))
    nettyConfig.lingerTimeout.foreach(i => httpBootstrap.childOption[java.lang.Integer](ChannelOption.SO_LINGER, i.toSeconds.toInt))
    nettyConfig.connectionTimeout.foreach(i =>
      httpBootstrap.childOption[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, i.toMillis.toInt)
    )

    httpBootstrap.bind(overrideSocketAddress.getOrElse(new InetSocketAddress(nettyConfig.host, nettyConfig.port)))
  }
}
