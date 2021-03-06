/**
 * The MIT License
 * Copyright © 2017, 2019 WebFolder OÜ
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.webfolder.cormorant.api;

import static io.undertow.Handlers.gracefulShutdown;
import static io.undertow.security.api.AuthenticationMode.CONSTRAINT_DRIVEN;
import static io.undertow.servlet.Servlets.filter;
import static io.undertow.servlet.api.ServletContainer.Factory.newInstance;
import static io.undertow.servlet.api.ServletStackTraces.NONE;
import static io.undertow.util.Methods.COPY;
import static io.undertow.util.Methods.DELETE;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.Methods.HEAD;
import static io.undertow.util.Methods.POST;
import static io.undertow.util.Methods.PUT;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.MINUTES;
import static javax.servlet.DispatcherType.REQUEST;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import javax.servlet.ServletException;
import javax.ws.rs.core.Application;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.server.servlet.Filter30Dispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.slf4j.Logger;

import io.undertow.Undertow;
import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.AllowedMethodsHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.HttpContinueAcceptingHandler;
import io.undertow.server.handlers.HttpContinueReadHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.accesslog.AccessLogHandler;
import io.undertow.server.handlers.accesslog.AccessLogReceiver;
import io.undertow.server.handlers.accesslog.DefaultAccessLogReceiver;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.ServletContainer;
import io.webfolder.cormorant.api.exception.CormorantException;

public class CormorantServer {

    public static final String  DEFAULT_HOST         = "localhost";

    public static final int     DEFAULT_PORT         = 5000;

    public static final String  DEFAULT_CONTEXT_PATH = "";

    private static final long   SHUTDOWN_TIMEOUT     = MINUTES.toSeconds(1);

    private final Logger log = getLogger(CormorantServer.class);

    private final PathHandler root = new PathHandler();

    private final ServletContainer container = newInstance();

    private volatile DeploymentManager manager;

    private volatile Undertow server;

    private volatile ResteasyDeployment deployment;

    private volatile GracefulShutdownHandler gracefulHandler;

    private volatile ReentrantLock lock = new ReentrantLock(true);

    private ExecutorService accessLogExecutor = newSingleThreadExecutor();

    private Path accessLogPath;

    private String host;

    private int port;

    private String contextPath;

    public CormorantServer() {
        setHost(DEFAULT_HOST);
        setPort(DEFAULT_PORT);
        setContextPath(DEFAULT_CONTEXT_PATH);
    }

    public CormorantServer deploy(
                            final Application application,
                            final HttpHandler ...httpHandlers) {
        return deploy(application, di -> di);
    }

    public CormorantServer deploy(
                            final Application application                          ,
                            final Function<DeploymentInfo, DeploymentInfo> function,
                            final HttpHandler ...httpHandlers) {

        deployment = new ResteasyDeploymentImpl();
        deployment.setAddCharset(false);
        deployment.setApplication(application);

        final DeploymentInfo deploymentInfo = initDeployment(application);

        manager = container.addDeployment(function.apply(deploymentInfo));
        manager.deploy();

        try {
            final HttpHandler handler = manager.start();
            root.addPrefixPath(deploymentInfo.getContextPath(), handler);
        } catch (ServletException e) {
            log.error(e.getMessage(), e);
            throw new CormorantException(e);
        }

        return this;
    }

    protected DeploymentInfo initDeployment(final Application application) {
        final FilterInfo filterInfo = filter("RestEasyFilter", Filter30Dispatcher.class).setAsyncSupported(true);
        DeploymentInfo di = new DeploymentInfo()
                        .addServletContextAttribute(ResteasyDeployment.class.getName(), deployment)
                        .addFilter(filterInfo)
                        .addFilterUrlMapping("RestEasyFilter", "/*", REQUEST)
                        .setContextPath(getContextPath())
                        .setDeploymentName("cormorant")
                        .setDisplayName("cormorant")
                        .setAuthenticationMode(CONSTRAINT_DRIVEN)
                        .setServletStackTraces(NONE)
                        .setSecurityDisabled(true)
                        .setClassLoader(deployment.getApplication().getClass().getClassLoader());
        if ( accessLogPath != null ) {
            File outputDirectory = accessLogPath.getParent().toFile();
            String logBaseName = accessLogPath.getFileName().toString();
            String logNameSuffix = ".log";
            String fileName = accessLogPath.getFileName().toString();
            int start = fileName.lastIndexOf(".");
            if (start > 0) {
                logNameSuffix = fileName.substring(start, fileName.length());
                logBaseName = fileName.substring(0, start);
            }
            boolean rotate = true;
            AccessLogReceiver accessLogReceiver = new DefaultAccessLogReceiver(accessLogExecutor,
                                                                               outputDirectory,
                                                                               logBaseName,
                                                                               logNameSuffix,
                                                                               rotate);
            HandlerWrapper accessLogHandlerWrapper = next -> new AccessLogHandler(next,
                                                                                  accessLogReceiver,
                                                                                  "combined",
                                                                                  CormorantServer.class.getClassLoader());
            di = di.addInitialHandlerChainWrapper(accessLogHandlerWrapper);
        }
        return di;
    }

    public CormorantServer start() {
        return start(null);
    }

    public CormorantServer start(
                                final Function<HttpHandler, HttpHandler> function) {
        final HttpContinueReadHandler      readHandler      = new HttpContinueReadHandler(root);
        final HttpContinueAcceptingHandler acceptingHandler = new HttpContinueAcceptingHandler(readHandler);
        final HttpHandler                  handler          = function != null ? function.apply(acceptingHandler) : null;
                                           gracefulHandler  = gracefulShutdown(handler != null ? handler : acceptingHandler);
        final AllowedMethodsHandler        allowedHandler   = new AllowedMethodsHandler(gracefulHandler,
                                                                       HEAD  , GET,
                                                                       POST  , PUT,
                                                                       DELETE, COPY);

        server = Undertow
                    .builder()
                    .addHttpListener(port, host)
                    .setHandler(allowedHandler)
                    .build();
        server.start();

        deployment.start();

        return this;
    }

    public void stop() {
        if (lock != null) {
            if (lock.tryLock()) {
                try {
                    log.info("cormorant server is shutting down.");
                    if ( gracefulHandler != null ) {
                        try {
                            gracefulHandler.shutdown();
                            gracefulHandler.awaitShutdown(SHUTDOWN_TIMEOUT);
                        } catch (InterruptedException e) {
                            log.error(e.getMessage(), e);
                        } finally {
                            gracefulHandler = null;
                        }
                    }
                    if ( deployment != null ) {
                        deployment.stop();
                        deployment = null;
                    }
                    if ( manager != null ) {
                        manager.undeploy();
                        manager = null;
                    }
                    if ( server != null ) {
                        server.stop();
                        server = null;
                    }
                    accessLogExecutor.shutdown();
                    log.info("Server terminated successfully.");
                } finally {
                    lock.unlock();
                    lock = null;
                }
            }
        }
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getContextPath() {
        return contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public void setAccessLogPath(Path accessLogPath) {
        this.accessLogPath = accessLogPath;
    }
}
