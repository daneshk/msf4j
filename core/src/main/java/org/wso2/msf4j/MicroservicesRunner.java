/*
 * Copyright (c) 2016, WSO2 Inc. (http://wso2.com) All Rights Reserved.
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
package org.wso2.msf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.ServerConnector;
import org.wso2.carbon.messaging.ServerConnectorProvider;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.carbon.transport.http.netty.common.Constants;
import org.wso2.carbon.transport.http.netty.config.ConfigurationBuilder;
import org.wso2.carbon.transport.http.netty.config.ListenerConfiguration;
import org.wso2.carbon.transport.http.netty.config.TransportProperty;
import org.wso2.carbon.transport.http.netty.config.TransportsConfiguration;
import org.wso2.carbon.transport.http.netty.listener.HTTPServerConnector;
import org.wso2.carbon.transport.http.netty.listener.HTTPServerConnectorProvider;
import org.wso2.carbon.transport.http.netty.listener.ServerConnectorController;
import org.wso2.msf4j.grpc.ServerBuilder;
import org.wso2.msf4j.grpc.exception.GrpcServerException;
import org.wso2.msf4j.interceptor.RequestInterceptor;
import org.wso2.msf4j.interceptor.ResponseInterceptor;
import org.wso2.msf4j.internal.DataHolder;
import org.wso2.msf4j.internal.MSF4JMessageProcessor;
import org.wso2.msf4j.internal.MicroservicesRegistryImpl;
import org.wso2.msf4j.internal.websocket.EndpointsRegistryImpl;
import org.wso2.msf4j.util.RuntimeAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import javax.ws.rs.Path;
import javax.ws.rs.ext.ExceptionMapper;

/**
 * This runner initializes the microservices runtime, deploys the microservices and service interceptors,
 * and starts the relevant transports.
 */
public class MicroservicesRunner {

    private static final Logger log = LoggerFactory.getLogger(MicroservicesRunner.class);
    
    /**
     * Default host used when using microservice runner starts with {@link #MicroservicesRunner(int...)}.
     */
    private static final String DEFAULT_HOST = "0.0.0.0";
    
    /**
     * The environment variable which overrides the {@link #DEFAULT_HOST}.
     */
    private static final String MSF4J_HOST = "msf4j.host";
    protected List<ServerConnector> serverConnectors = new ArrayList<>();
    private long startTime = System.currentTimeMillis();
    private boolean isStarted;
    private MicroservicesRegistryImpl msRegistry = new MicroservicesRegistryImpl();
    private EndpointsRegistryImpl endpointsRegistry = EndpointsRegistryImpl.getInstance();

    /**
     * gRPC Implementation.
     */
    private boolean isGrpcService = false;
    private ServerBuilder grpcBuilder = null;
    private static final String HTTP_SCHEMA_KEY = "http";

    /**
     * Creates a MicroservicesRunner instance which will be used for deploying microservices. Allows specifying
     * ports on which the microservices in this MicroservicesRunner are deployed.
     *
     * @param ports The port on which the microservices are exposed
     */
    public MicroservicesRunner(int... ports) {
        configureTransport(ports);
    }

    /**
     * Default constructor which will take care of initializing Netty transports in the file pointed to by the
     * System property <code>transports.netty.conf</code>.
     * <p>
     * If that System property is not specified, it will start a single Netty transport on port 8080.
     * <p>
     * {@link #MicroservicesRunner(int...)}
     */
    public MicroservicesRunner() {
        configureTransport();
    }

    /**
     * Creates a MicroservicesRunner instance which will be used for deploying microservices as gRPC or REST.
     * Allows specifying ports on which the microservices in this MicroservicesRunner are deployed.
     *
     * @param port The port on which the microservices are exposed
     * @param isGrpcService flag to determine whether service is deployed as gRPC or REST
     */
    public MicroservicesRunner(int port, boolean isGrpcService) {
        this.isGrpcService = isGrpcService;
        if (isGrpcService) {
            grpcBuilder = new ServerBuilder(port);
        } else {
            configureTransport(port);
        }
    }

    /**
     * Creates a MicroservicesRunner instance which will take care of initializing Netty transports in the file
     * pointed to by the System property <code>transports.netty.conf</code>.
     * <p>
     * If that System property is not specified, it will start a single Netty transport on port 8080.
     * <p>
     * @param isGrpcService flag to determine whether service is deployed as gRPC or REST
     */
    public MicroservicesRunner(boolean isGrpcService) {
        this.isGrpcService = isGrpcService;
        int port = ServerBuilder.DEFAULT_PORT;
        if (isGrpcService) {
            TransportsConfiguration transportsConfiguration = ConfigurationBuilder.getInstance().getConfiguration();
            if (transportsConfiguration != null) {
                Optional<ListenerConfiguration> configuration = transportsConfiguration.getListenerConfigurations()
                        .stream().filter(listenerConfiguration -> HTTP_SCHEMA_KEY.equals(listenerConfiguration
                                .getScheme())).findFirst();
                port = configuration.isPresent() ? configuration.get().getPort() : port;
            }
            grpcBuilder = new ServerBuilder(port);
        } else {
            configureTransport();
        }
    }

    /**
     * Deploy a microservice.
     *
     * @param microservice The microservice which is to be deployed
     * @return this MicroservicesRunner object
     */
    public MicroservicesRunner deploy(Object... microservice) {
        checkState();
        if (isGrpcService) {
            grpcBuilder.addService(microservice);
        } else {
            msRegistry.addService(microservice);
        }
        return this;
    }

    /**
     * Deploy a microservice with dynamic path.
     *
     * @param microservice The microservice which is to be deployed
     * @param basePath The context path for the service
     * @return this MicroservicesRunner object
     */
    public MicroservicesRunner deploy(String basePath, Object microservice) {
        if (isGrpcService) {
            throw new UnsupportedOperationException("gRPC services cannot register with the base path. only support " +
                    "in REST microservices");
        }
        Map<String, Object> valuesMap = new HashMap<>();
        valuesMap.put("value", basePath);
        RuntimeAnnotations.putAnnotation(microservice.getClass(), Path.class, valuesMap);
        msRegistry.addService(basePath, microservice);
        return this;
    }

    /**
     * Add WebSocket endpoint to the MicroserviceRunner
     * @param webSocketEndpoint webSocketEndpoint endpoint which is to be added.
     * @return this MicroservicesRunner object.
     */
    public MicroservicesRunner deployWebSocketEndpoint(Object webSocketEndpoint) {
        if (isGrpcService) {
            throw new UnsupportedOperationException("gRPC streaming services is currently not supported");
        }
        endpointsRegistry.addEndpoint(webSocketEndpoint);
        return this;
    }

    /**
     * Register a custom {@link SessionManager}.
     *
     * @param sessionManager The SessionManager instance to be registered.
     * @return this MicroservicesRunner object
     */
    public MicroservicesRunner setSessionManager(SessionManager sessionManager) {
        msRegistry.setSessionManager(sessionManager);
        return this;
    }

    /**
     * Register request interceptors.
     *
     * @param requestInterceptor interceptor instances
     */
    public MicroservicesRunner addGlobalRequestInterceptor(RequestInterceptor... requestInterceptor) {
        if (isGrpcService) {
            throw new UnsupportedOperationException("gRPC service request interceptor is currently not " +
                    "supported");
        }
        msRegistry.addGlobalRequestInterceptor(requestInterceptor);
        return this;
    }

    /**
     * Register response interceptors.
     *
     * @param responseInterceptor interceptor instances
     */
    public MicroservicesRunner addGlobalResponseInterceptor(ResponseInterceptor... responseInterceptor) {
        if (isGrpcService) {
            throw new UnsupportedOperationException("gRPC service response interceptor is currently not " +
                    "supported");
        }
        msRegistry.addGlobalResponseInterceptor(responseInterceptor);
        return this;
    }

    /**
     * Add an interceptor which will get called before &amp; after the deployed microservices are invoked.
     * Multiple interceptors can be added
     *
     * @param interceptor interceptor The interceptor to be added.
     * @return this MicroservicesRunner object
     * @deprecated
     */
    public MicroservicesRunner addInterceptor(Interceptor... interceptor) {
        if (isGrpcService) {
            throw new UnsupportedOperationException("gRPC service interceptor is currently not " +
                    "supported");
        }
        msRegistry.addGlobalRequestInterceptor(interceptor);
        msRegistry.addGlobalResponseInterceptor(interceptor);
        return this;
    }

    /**
     * Add javax.ws.rs.ext.ExceptionMapper objects.
     *
     * @param exceptionMapper The ExceptionMapper to be added
     * @return this MicroservicesRunner object
     */
    public MicroservicesRunner addExceptionMapper(ExceptionMapper... exceptionMapper) {
        if (isGrpcService) {
            throw new UnsupportedOperationException("gRPC service exception mapping is currently not " +
                    "supported");
        }
        checkState();
        msRegistry.addExceptionMapper(exceptionMapper);
        return this;
    }

    /**
     * Method to configure transports.
     *
     * @param ports The port on which the microservices are exposed
     */
    protected void configureTransport(int... ports) {
        Set<TransportProperty> transportProperties = new HashSet<>();
        TransportProperty transportProperty = new TransportProperty();
        int bossGroupSize = Runtime.getRuntime().availableProcessors();
        transportProperty.setName(Constants.SERVER_BOOTSTRAP_BOSS_GROUP_SIZE);
        transportProperty.setValue(bossGroupSize);
        TransportProperty workerGroup = new TransportProperty();
        int workerGroupSize = Runtime.getRuntime().availableProcessors() * 2;
        workerGroup.setName(Constants.SERVER_BOOTSTRAP_WORKER_GROUP_SIZE);
        workerGroup.setValue(workerGroupSize);
        transportProperties.add(transportProperty);
        transportProperties.add(workerGroup);

        TransportsConfiguration transportsConfiguration = new TransportsConfiguration();
        ServerConnectorController serverConnectorController = new ServerConnectorController(transportsConfiguration);
        serverConnectorController.start();
        HTTPServerConnectorProvider httpServerConnectorProvider = new HTTPServerConnectorProvider();
        transportsConfiguration.setTransportProperties(transportProperties);
        Set<ListenerConfiguration> listenerConfigurations = new HashSet<>();
        for (int port : ports) {
            ListenerConfiguration listenerConfiguration = new ListenerConfiguration("netty-" + port,
                    System.getProperty(MSF4J_HOST, DEFAULT_HOST), port);
            DataHolder.getInstance().getMicroservicesRegistries().put(listenerConfiguration.getId(), msRegistry);
            listenerConfigurations.add(listenerConfiguration);
        }
        transportsConfiguration.setListenerConfigurations(listenerConfigurations);
        serverConnectors.addAll(httpServerConnectorProvider.initializeConnectors(transportsConfiguration));
        serverConnectors.forEach(serverConnector -> serverConnector.setMessageProcessor(new MSF4JMessageProcessor()));
    }

    /**
     * Method to configure transports.
     */
    protected void configureTransport() {
        ServiceLoader<ServerConnectorProvider> serverConnectorProviderLoader =
                ServiceLoader.load(ServerConnectorProvider.class);
        serverConnectorProviderLoader.
                forEach(serverConnectorProvider -> {
                    if (serverConnectorProvider instanceof HTTPServerConnectorProvider) {
                        serverConnectors
                                .addAll(serverConnectorProvider.initializeConnectors());
                        serverConnectors.forEach(serverConnector -> {
                            serverConnector
                                    .setMessageProcessor(new MSF4JMessageProcessor());
                            DataHolder.getInstance().getMicroservicesRegistries()
                                    .put(serverConnector.getId(), msRegistry);
                        });
                    }
                });
    }

    private void checkState() {
        if (isStarted) {
            throw new IllegalStateException("Microservices runner already started");
        }
    }

    /**
     * Start this Microservices runner. This will startup all the HTTP transports.
     */
    public void start() {
        if (isGrpcService) {
            try {
                grpcBuilder.register().start();
                log.info("gRPC server server started in " + (System.currentTimeMillis() - startTime) + "ms");
                grpcBuilder.blockUntilShutdown();
            } catch (GrpcServerException | InterruptedException e) {
                throw new RuntimeException("Error while starting the gRPC server.", e);
            }
        } else {
            msRegistry.getSessionManager().init();
            handleServiceLifecycleMethods();
            serverConnectors.forEach(serverConnector -> {
                try {
                    serverConnector.start();
                    isStarted = true;
                    log.info("Microservices server started in " + (System.currentTimeMillis() - startTime) + "ms");
                } catch (ServerConnectorException e) {
                    log.error("Error while starting the Microservices server. " + e.getMessage(), e);
                    throw new RuntimeException("Error while starting the Microservices server.", e);
                }
            });
        }
    }

    /**
     * Stop this Microservices runner. This will stop all the HTTP Transports.
     */
    public void stop() {
        if (isGrpcService) {
            grpcBuilder.stop();
            log.info("gRPC server stopped");
        } else {
            serverConnectors.forEach(serverConnector -> ((HTTPServerConnector) serverConnector).stop());
            log.info("Microservices server stopped");
        }
    }

    /**
     * Get the MicroservicesRegistry instance of this runner.
     *
     * @return MicroservicesRegistry instance of this runner
     */
    public MicroservicesRegistryImpl getMsRegistry() {
        return msRegistry;
    }

    protected void handleServiceLifecycleMethods() {
        msRegistry.initServices();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                msRegistry.preDestroyServices();
            }
        });
    }
}
