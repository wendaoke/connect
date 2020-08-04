package com.arloor.socks5connect.http;

import com.alibaba.fastjson.JSONObject;
import com.arloor.socks5connect.ClientBootStrap;
import com.arloor.socks5connect.ExceptionUtil;
import com.arloor.socks5connect.RelayHandler;
import com.arloor.socks5connect.SocketChannelUtils;
import com.arloor.socks5connect.SocksServerConnectHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.socksx.SocksMessage;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static com.arloor.socks5connect.ClientBootStrap.clazzSocketChannel;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

public class HttpConnectHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static Logger logger = LoggerFactory.getLogger(HttpConnectHandler.class.getSimpleName());


    private int remotePort = 80;
    private String remoteHost;
    private String basicAuth;
    private final Bootstrap b = new Bootstrap();
    private static SslContext sslContext;

    static {
        // 解决algid parse error, not a sequence
        // https://blog.csdn.net/ls0111/article/details/77533768
        java.security.Security.addProvider(
                new org.bouncycastle.jce.provider.BouncyCastleProvider()
        );
        List<String> ciphers = Arrays.asList("ECDHE-RSA-AES128-SHA", "ECDHE-RSA-AES256-SHA", "AES128-SHA", "AES256-SHA", "DES-CBC3-SHA");
        try {
            sslContext = SslContextBuilder.forClient()
                    .protocols("TLSv1.3", "TLSv1.2")
                    .sslProvider(SslProvider.OPENSSL)
                    .clientAuth(ClientAuth.NONE)
//                    .ciphers(ciphers)
                    .build();
        } catch (SSLException e) {
            e.printStackTrace();
        }
    }


    public HttpConnectHandler() {
        super();
        int use = ClientBootStrap.use;
        if (use == -1) {
            Random rand = new Random();
            use = rand.nextInt(ClientBootStrap.servers.size());
        }
        JSONObject serverInfo = ClientBootStrap.servers.getJSONObject(use);
        this.remotePort = serverInfo.getInteger("ProxyPort");
        this.remoteHost = serverInfo.getString("ProxyAddr");
        this.basicAuth = Base64.getEncoder().encodeToString((serverInfo.getString("UserName") + ":" + serverInfo.getString("Password")).getBytes());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
        // SimpleChannelInboundHandler会release，在这里先retain下
        ctx.channel().config().setAutoRead(false);
        buf.retain();
        b.group(ctx.channel().eventLoop())
                .channel(clazzSocketChannel)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new LoggingHandler(LogLevel.DEBUG));
        b.connect(remoteHost, remotePort).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    Channel outboud = future.channel();
                    outboud.pipeline().addLast(sslContext.newHandler(ctx.alloc()));
                    outboud.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
                    outboud.pipeline().addLast(new RelayHandler(ctx.channel()));


                    ctx.channel().pipeline().remove(HttpConnectHandler.this);
                    ctx.channel().pipeline().addLast(new LoggingHandler(LogLevel.INFO));
                    RelayHandler relayToOutbound = new RelayHandler(outboud);
                    ctx.channel().pipeline().addLast();

                    relayToOutbound.channelRead(ctx, Unpooled.wrappedBuffer("".getBytes()));
                    ctx.channel().config().setAutoRead(true);
                } else {
                    // Close the connection if the connection attempt has failed.
                    logger.error("connect to: " + remoteHost + ":" + remotePort + " failed! == " + ExceptionUtil.getMessage(future.cause()));
                    ctx.channel().writeAndFlush(
                            new DefaultHttpResponse(HttpVersion.HTTP_1_1, INTERNAL_SERVER_ERROR)
                    );
                    SocketChannelUtils.closeOnFlush(ctx.channel());
                }
            }
        });
    }


}