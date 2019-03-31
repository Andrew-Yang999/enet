package org.xnatural.enet.server.http.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.xnatural.enet.event.EL;
import org.xnatural.enet.event.EP;
import org.xnatural.enet.server.ServerTpl;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;

/**
 * 用 netty 实现的 http server
 */
public class NettyHttp extends ServerTpl {
    protected EventLoopGroup    boosGroup;
    protected EventLoopGroup workerGroup;


    public NettyHttp() {
        setName("http-netty");
        setPort(8080);
        setHostname("localhost");
    }


    @EL(name = "sys.starting")
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("{} Server is running", getName()); return;
        }
        if (coreExec == null) initExecutor();
        if (coreEp == null) coreEp = new EP(coreExec);
        coreEp.fire(getName() + ".starting");
        attrs.putAll((Map) coreEp.fire("env.ns", "http", getName()));
        createServer();
        coreEp.fire(getName() + ".started");
    }


    /**
     * async 为false是为了保证 此服务最先被关闭.(先断掉新的请求, 再关闭其它服务)
     */
    @EL(name = "sys.stopping", async = false)
    public void stop() {
        log.info("Shutdown '{}' Server. hostname: {}, port: {}", getName(), getHostname(), getPort());
        if (boosGroup != null) boosGroup.shutdownGracefully();
        if (workerGroup != null && workerGroup != boosGroup) workerGroup.shutdownGracefully();
        if (coreExec instanceof ExecutorService) ((ExecutorService) coreExec).shutdown();
    }


    /**
     * 创建服务
     */
    protected void createServer() {
        boolean isLinux = isLinux() && getBoolean("epollEnabled", true);
        boosGroup = isLinux ? new EpollEventLoopGroup(getInteger("threads-boos", 1), coreExec) : new NioEventLoopGroup(getInteger("threads-boos", 1), coreExec);
        workerGroup = getBoolean("shareLoop", true) ? boosGroup :
            (isLinux ? new EpollEventLoopGroup(getInteger("threads-worker", 1)) : new NioEventLoopGroup(getInteger("threads-worker", 1), coreExec));
        ServerBootstrap sb = new ServerBootstrap()
                .group(boosGroup, workerGroup)
                .channel(isLinux ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new IdleStateHandler(2, 0, 0, TimeUnit.MINUTES));
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                                if (!fusing(ctx, msg)) ctx.fireChannelRead(msg);
                            }
                        });
                        ch.pipeline().addLast(new HttpServerKeepAliveHandler());
                        ch.pipeline().addLast(new HttpObjectAggregator(getInteger("maxContentLength", 65536)));
                        ch.pipeline().addLast(new ChunkedWriteHandler());
                        coreEp.fire("http-netty.addHandler", ec -> {
                            if (ec.isNoListener()) {
                                log.error("'{}' server not available handler", getName());
                                stop();
                            }
                        }, ch.pipeline());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, getInteger("backlog", 200))
                .childOption(ChannelOption.SO_KEEPALIVE, true);
        try {
            sb.bind(getHostname(), getPort()).sync();
            log.info("Started {} Server. hostname: {}, port: {}", getName(), getHostname(), getPort());
        } catch (Exception ex) {
            log.error(ex);
        }
    }



    protected int sysLoad = 0;
    /**
     * 监听系统负载
     * @param load
     */
    @EL(name = "sys.load", async = false)
    protected void sysLoad(Integer load) { sysLoad = load * 10; }


    /**
     * 熔断: 是否拒绝处理请求
     * @param ctx
     * @param msg
     * @return
     */
    protected boolean fusing(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof DefaultHttpRequest)) return false;
        if (sysLoad >= 10) { // 当系统负载过高时拒绝处理
            sysLoad--;
            DefaultHttpResponse resp = new DefaultHttpResponse(((DefaultHttpRequest) msg).protocolVersion(), SERVICE_UNAVAILABLE);
            ctx.writeAndFlush(resp); ctx.close();
            return true;
        }
        return false;
    }


    /**
     * 判断系统是否为 linux 系统
     * @return
     */
    protected boolean isLinux() {
        return (System.getProperty("os.name").toLowerCase(Locale.UK).trim().startsWith("linux"));
    }


    public String getHostname() {
        return getStr("hostname", "localhost");
    }


    public NettyHttp setHostname(String hostname) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新主机名");
        attrs.put("hostname", hostname);
        return this;
    }


    public int getPort() {
        return getInteger("port", 8080);
    }


    public NettyHttp setPort(int port) {
        if (running.get()) throw new RuntimeException("服务正在运行.不允许更新端口");
        attrs.put("port", port);
        return this;
    }
}
