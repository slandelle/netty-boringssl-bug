package io.gatling.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ForkJoinPool;

final class Server implements AutoCloseable {
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ServerBootstrap bootstrap;

        private Server(int port, SslProvider sslProvider) throws Exception {
            var ioHandlerFactory = NioIoHandler.newFactory();
            bossGroup = new MultiThreadIoEventLoopGroup(1, ioHandlerFactory);
            workerGroup = new MultiThreadIoEventLoopGroup(ioHandlerFactory);

            var cert = new SelfSignedCertificate();
            var sslContext =
                SslContextBuilder
                    .forServer(cert.certificate(), cert.privateKey())
                    .sslProvider(sslProvider)
                    .build();

            bootstrap = new ServerBootstrap()
                .channel(NioServerSocketChannel.class)
                .group(bossGroup, workerGroup)
                .option(ChannelOption.SO_BACKLOG, 15 * 1024)
                .localAddress(new InetSocketAddress(port))
                .childHandler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline()
                            .addLast(
                                sslContext.newHandler(ch.alloc(), ForkJoinPool.commonPool()),
                                new HttpServerCodec(),
                                new HttpObjectAggregator(Integer.MAX_VALUE),
                                AppHandler.INSTANCE);
                    }
                });
        }



    private void start() throws InterruptedException {
        bootstrap.bind().sync();
    }

    @Override
    public void close() throws Exception {
        workerGroup.shutdownGracefully().sync();
        bossGroup.shutdownGracefully().sync();
    }

    @ChannelHandler.Sharable
    private static class AppHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private static final AppHandler INSTANCE = new AppHandler();

        @Override
        public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            var responseBody = ctx.alloc().buffer();
            responseBody.writeCharSequence("Hello", StandardCharsets.UTF_8);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, responseBody);
            response.headers().add(HttpHeaderNames.CONTENT_LENGTH, responseBody.readableBytes());
            ctx.writeAndFlush(response);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Using SslProvider " + SslProvider.valueOf(args[0]));
        var server = new Server(8081, SslProvider.valueOf(args[0]));
        server.start();
        while (true);
    }
}
