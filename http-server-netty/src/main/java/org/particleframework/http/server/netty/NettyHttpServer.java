/*
 * Copyright 2017 original authors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.particleframework.http.server.netty;

import com.typesafe.netty.http.HttpStreamsServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import org.particleframework.context.ApplicationContext;
import org.particleframework.context.BeanLocator;
import org.particleframework.context.LifeCycle;
import org.particleframework.context.env.Environment;
import org.particleframework.context.exceptions.ConfigurationException;
import org.particleframework.core.io.socket.SocketUtils;
import org.particleframework.core.naming.Named;
import org.particleframework.core.order.OrderUtil;
import org.particleframework.core.reflect.GenericTypeUtils;
import org.particleframework.core.reflect.ReflectionUtils;
import org.particleframework.http.codec.MediaTypeCodecRegistry;
import org.particleframework.http.server.binding.RequestBinderRegistry;
import org.particleframework.http.server.netty.configuration.NettyHttpServerConfiguration;
import org.particleframework.http.server.netty.decoders.HttpRequestDecoder;
import org.particleframework.inject.qualifiers.Qualifiers;
import org.particleframework.runtime.executor.ExecutorSelector;
import org.particleframework.runtime.executor.IOExecutorServiceConfig;
import org.particleframework.runtime.server.EmbeddedServer;
import org.particleframework.web.router.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

/**
 * Implements the bootstrap and configuration logic for the Netty implementation of {@link EmbeddedServer}
 *
 * @see RoutingInBoundHandler
 * @author Graeme Rocher
 * @since 1.0
 */
@Singleton
public class NettyHttpServer implements EmbeddedServer {
    public static final String HTTP_STREAMS_CODEC = "http-streams-codec";
    public static final String HTTP_CODEC = "http-codec";
    public static final String PARTICLE_HANDLER = "particle-inbound-handler";
    public static final String OUTBOUND_KEY = "-outbound-";
    private static final Logger LOG = LoggerFactory.getLogger(NettyHttpServer.class);

    private final ExecutorService ioExecutor;
    private final ExecutorSelector executorSelector;
    private final ChannelOutboundHandler[] outboundHandlers;
    private final MediaTypeCodecRegistry mediaTypeCodecRegistry;
    private volatile Channel serverChannel;
    private final NettyHttpServerConfiguration serverConfiguration;
    private final Environment environment;
    private final Router router;
    private final RequestBinderRegistry binderRegistry;
    private final BeanLocator beanLocator;
    private final int serverPort;

    @Inject
    public NettyHttpServer(
            NettyHttpServerConfiguration serverConfiguration,
            ApplicationContext applicationContext,
            Router router,
            RequestBinderRegistry binderRegistry,
            MediaTypeCodecRegistry mediaTypeCodecRegistry,
            @javax.inject.Named(IOExecutorServiceConfig.NAME) ExecutorService ioExecutor,
            ExecutorSelector executorSelector,
            ChannelOutboundHandler... outboundHandlers
    ) {
        Optional<File> location = serverConfiguration.getMultipart().getLocation();
        location.ifPresent(dir ->
                DiskFileUpload.baseDirectory = dir.getAbsolutePath()
        );
        this.mediaTypeCodecRegistry = mediaTypeCodecRegistry;
        this.beanLocator = applicationContext;
        this.environment = applicationContext.getEnvironment();
        this.serverConfiguration = serverConfiguration;
        this.router = router;
        this.ioExecutor = ioExecutor;
        int port = serverConfiguration.getPort();
        this.serverPort = port == -1 ? SocketUtils.findAvailableTcpPort() : port;
        this.executorSelector = executorSelector;
        OrderUtil.sort(outboundHandlers);
        this.outboundHandlers = outboundHandlers;
        this.binderRegistry = binderRegistry;
    }

    @Override
    public boolean isRunning() {
        return !SocketUtils.isTcpPortAvailable(serverPort);
    }

    @Override
    public EmbeddedServer start() {
        if(!isRunning()) {

            NioEventLoopGroup workerGroup = createWorkerEventLoopGroup();
            NioEventLoopGroup parentGroup = createParentEventLoopGroup();
            ServerBootstrap serverBootstrap = createServerBootstrap();

            processOptions(serverConfiguration.getOptions(), serverBootstrap::option);
            processOptions(serverConfiguration.getChildOptions(), serverBootstrap::childOption);


            serverBootstrap = serverBootstrap.group(parentGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();

                            RequestBinderRegistry binderRegistry = NettyHttpServer.this.binderRegistry;

                            pipeline.addLast(HTTP_CODEC, new HttpServerCodec());
                            pipeline.addLast(HTTP_STREAMS_CODEC, new HttpStreamsServerHandler());
                            pipeline.addLast(HttpRequestDecoder.ID, new HttpRequestDecoder(environment, serverConfiguration));
                            pipeline.addLast(PARTICLE_HANDLER, new RoutingInBoundHandler(
                                    beanLocator,
                                    router,
                                    mediaTypeCodecRegistry,
                                    serverConfiguration,
                                    binderRegistry,
                                    executorSelector,
                                    ioExecutor
                            ));
                            registerParticleChannelHandlers(pipeline);
                        }
                    });

            Optional<String> host = serverConfiguration.getHost();

            ChannelFuture future;

            if(host.isPresent()) {
                future = serverBootstrap.bind(host.get(), serverPort);
            }
            else {
                future = serverBootstrap.bind(serverPort);
            }

            future.addListener(op -> {
                if (future.isSuccess()) {
                    serverChannel = future.channel();
                } else {
                    Throwable cause = op.cause();
                    if (LOG.isErrorEnabled()) {
                        LOG.error("Error starting Particle server: " + cause.getMessage(), cause);
                    }
                }
            });
        }
        return this;
    }

    @Override
    public EmbeddedServer stop() {
        if (isRunning() && this.serverChannel != null) {
            try {
                serverChannel.close().sync();
                if(beanLocator instanceof LifeCycle) {
                    LifeCycle lifeCycle = (LifeCycle) beanLocator;
                    if(lifeCycle.isRunning()) {
                        lifeCycle.stop();
                    }
                }
            } catch (Throwable e) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("Error stopping Particle server: " + e.getMessage(), e);
                }
            }
        }
        return this;
    }


    @Override
    public int getPort() {
        return serverPort;
    }

    @Override
    public String getHost() {
        return serverConfiguration.getHost().orElse("localhost");
    }

    @Override
    public URL getURL() {
        try {
            return new URL("http://" + getHost() + ':' + getPort());
        } catch (MalformedURLException e) {
            throw new ConfigurationException("Invalid server URL: " + e.getMessage(), e);
        }
    }


    @Override
    public URI getURI() {
        try {
            return new URI("http://" + getHost() + ':' + getPort());
        } catch (URISyntaxException e) {
            throw new ConfigurationException("Invalid server URL: " + e.getMessage(), e);
        }
    }

    protected NioEventLoopGroup createParentEventLoopGroup() {
        return newEventLoopGroup(serverConfiguration.getParent());
    }

    protected NioEventLoopGroup createWorkerEventLoopGroup() {
        return newEventLoopGroup(serverConfiguration.getWorker());
    }

    protected ServerBootstrap createServerBootstrap() {
        return new ServerBootstrap();
    }


    private NioEventLoopGroup newEventLoopGroup(NettyHttpServerConfiguration.EventLoopConfig config) {
        if (config != null) {
            Optional<ExecutorService> executorService = config.getExecutorName().flatMap(name -> beanLocator.findBean(ExecutorService.class, Qualifiers.byName(name)));
            NioEventLoopGroup group = executorService.map(service -> new NioEventLoopGroup(config.getNumOfThreads(), service)).orElseGet(() -> new NioEventLoopGroup(config.getNumOfThreads()));
            config.getIoRatio().ifPresent(group::setIoRatio);
            return group;
        } else {
            return new NioEventLoopGroup();
        }
    }

    private void registerParticleChannelHandlers(ChannelPipeline pipeline) {
        int i = 0;
        for (ChannelHandler outboundHandlerAdapter : outboundHandlers) {
            String name;
            if (outboundHandlerAdapter instanceof Named) {
                name = ((Named) outboundHandlerAdapter).getName();
            } else {
                name = NettyHttpServer.PARTICLE_HANDLER + NettyHttpServer.OUTBOUND_KEY + ++i;
            }
            pipeline.addAfter(NettyHttpServer.HTTP_CODEC, name, outboundHandlerAdapter);
        }
    }

    private void processOptions(Map<ChannelOption, Object> options, BiConsumer<ChannelOption, Object> biConsumer) {
        for (ChannelOption channelOption : options.keySet()) {
            String name = channelOption.name();
            Object value = options.get(channelOption);
            Optional<Field> declaredField = ReflectionUtils.findDeclaredField(ChannelOption.class, name);
            declaredField.ifPresent((field) -> {
                Optional<Class> typeArg = GenericTypeUtils.resolveGenericTypeArgument(field);
                typeArg.ifPresent((arg) -> {
                    Optional converted = environment.convert(value, arg);
                    converted.ifPresent((convertedValue) ->
                            biConsumer.accept(channelOption, convertedValue)
                    );
                });

            });
            if (!declaredField.isPresent()) {
                biConsumer.accept(channelOption, value);
            }
        }
    }


}
