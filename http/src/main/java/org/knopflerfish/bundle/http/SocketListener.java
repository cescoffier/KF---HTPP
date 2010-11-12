/*
 * Copyright (c) 2003-2008, KNOPFLERFISH project
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials
 *   provided with the distribution.
 *
 * - Neither the name of the KNOPFLERFISH project nor the names of its
 *   contributors may be used to endorse or promote products derived
 *   from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.knopflerfish.bundle.http;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;

public class SocketListener implements Runnable, ServiceTrackerCustomizer {

    // private constants

    private final static String zeroAddress = "0.0.0.0";

    // private fields

    private final HttpConfigWrapper httpConfig;

    private final Logger log;

    private final TransactionManager transactionManager;

    private final BundleContext bc;

    private final HttpServer httpServer;

    private ServiceTracker securityTracker = null;

    private int port = -1;

    private String host = null;

    private int maxConnections = -1;

    private boolean isSecure = false;

    private boolean isEnabled = false; // initial state of this object is

    // disabled
    private Boolean requireClientAuth = null;

    private boolean done = false;

    private ServerSocket socket = null;

    private Thread thread = null;

    // constructors

    public SocketListener(final HttpConfigWrapper httpConfig,
                          final Logger log,
                          final TransactionManager transactionManager,
                          final BundleContext bc,
                          final HttpServer httpServer)
  {

        this.httpConfig = httpConfig;
        this.log = log;
        this.transactionManager = transactionManager;
        this.bc = bc;
        this.httpServer = httpServer;
    }

    public void updated() throws ConfigurationException {

        // the following if statements prevents unnecessary calls to init
        // (nothing
        // changed)
        if (isSecure == httpConfig.isSecure()
                && (requireClientAuth != null)
                && (requireClientAuth.booleanValue() == httpConfig
                        .requireClientAuth()) && port == httpConfig.getPort()
                && httpConfig.getHost().equals(host)
                && httpConfig.getMaxConnections() == maxConnections
                && isEnabled == httpConfig.isEnabled()) {
            return;
        }

        isSecure = httpConfig.isSecure();
        requireClientAuth = new Boolean(httpConfig.requireClientAuth());
        port = httpConfig.getPort();
        host = httpConfig.getHost();
        maxConnections = httpConfig.getMaxConnections();
        isEnabled = httpConfig.isEnabled();

        destroy();

        if (!isEnabled) {
            return;
        }

        /**
         * We want to be able to do either HTTPS or HTTP. The latter would
         * always be executed synchronously, e.g. in this invocation. This might
         * not be the case for HTTPs, which might happen on different threads.
         * Therefore, try to throw any ConfigurationEx as early as possible
         */
        if ((port < 0) || (port > 0xFFFF)) {
            throw new ConfigurationException(
                    (httpConfig.isSecure() ? HttpConfig.HTTPS_PORT_KEY
                            : HttpConfig.HTTP_PORT_KEY), "invalid value="
                            + port);

        }
        if (maxConnections < 1) {
            throw new ConfigurationException("maxConnections", "invalid value="
                    + maxConnections);

        }

        if (!isSecure) {
            // for HTTP create the socket right away AND start
            try {
                if (log.isDebugEnabled())
                    log.debug("Creating socket");
                if (host == null || host.length() == 0) {
                    socket = new ServerSocket(port, maxConnections);
                } else {
                    try {
                        socket = new ServerSocket(port, maxConnections,
                                InetAddress.getByName(host));
                    } catch (UnknownHostException uhe) {
                        socket = new ServerSocket(port, maxConnections);
                    }
                }

                init();

            } catch (Exception e) {
                if (log.isDebugEnabled())
                    log.debug("Exception creating HTTP Socket", e);
                throw new ConfigurationException(
                        "Exception creating HTTP Socket", e.toString());
            }

        } else // secure case, can not create socket by myself, need to get
                // service
        {
            securityTracker = new ServiceTracker(this.bc,
                    "javax.net.ssl.SSLServerSocketFactory", this);
            securityTracker.open();
        }
    }

    public Object addingService(ServiceReference sRef) {
        // TE this class must not explicitly reference SSLServerFactory.
        Object factory = null;

        if (this.socket != null) {
            if (log.isWarnEnabled())
                log.warn("SEVERAL  SSLServerSocketFactories are available,"
                         +" selection random");
            return null; // do not track
        }

        factory = this.bc.getService(sRef);

        // find the two methods using reflection
        Method create2 = null;
        Method create3 = null;

        try {
            create2 = factory.getClass().getMethod("createServerSocket",
                    new Class[] { int.class, int.class });
            create3 = factory.getClass().getMethod("createServerSocket",
                    new Class[] { int.class, int.class, InetAddress.class });

        } catch (Exception cmethE) {
            log.error("not an SSL factory, or no access : " + factory, cmethE);

        }

        if (host == null || host.length() == 0) {
            try {
                socket = (ServerSocket) create2.invoke(factory, new Object[] {
                        new Integer(port), new Integer(maxConnections) });

            } catch (Exception ex) {
                ex.printStackTrace();
                log.error("creating ssl socket on server:", ex);
            }

        } else {
            try {
                try {
                    socket = (ServerSocket) create3.invoke(factory,
                            new Object[] { new Integer(port),
                                    new Integer(maxConnections),
                                    InetAddress.getByName(host) });

                } catch (UnknownHostException uhe) {
                    socket = (ServerSocket) create2.invoke(factory,
                            new Object[] { new Integer(port),
                                    new Integer(maxConnections) });
                }

            } catch (Exception ex) {
                log.error("creating socket ", ex);
            }

        }

        if (socket != null) {
            try {
                Class sslSockClass = Class
                        .forName("javax.net.ssl.SSLServerSocket");
                Method auth = sslSockClass.getMethod("setNeedClientAuth",
                        new Class[] { boolean.class });
                auth.invoke(socket, new Object[] { requireClientAuth });

            } catch (Exception exc) {
                log.error(exc.toString());
            }
        }

        if (socket != null) {
            try {
                init();
            } catch (Exception e) {
                log.error("Can not initialize", e);
                socket = null;
            }
        }

        if (socket == null) {
            this.bc.ungetService(sRef);
            factory = null;

        }

        return factory;
    }

    public void modifiedService(ServiceReference arg0, Object arg1) {

    }

    public void removedService(ServiceReference sRef, Object arg1) {
        if (log.isDebugEnabled())
            log.debug("SSLFactory Security service was removed.");

        uninit();

        this.bc.ungetService(sRef);

    }

    private synchronized void init() {
        // socket exists, otherwise not called
        done = false;

        port = socket.getLocalPort();
        if (port!=httpConfig.getPort()) {
          // Update config to reflect the actual port used (if port was
          // specified as 0 this will make a difference).
          httpConfig.setPort(port);

          if (isSecure) {
            // Make sure that the service property port.https is
            // correct. Only needed for https, since init() is called
            // asynchroneous in that case.
            httpServer.doHttpReg();
          }
        }

        String sch = httpConfig.getScheme().toUpperCase();

        if (log.isInfoEnabled())
            log.info(sch + " server started on port " + port);

        thread = new Thread(this, sch + " server:" + port);
        thread.start();

    }

    private synchronized void uninit() {
        done = true;

        Thread[] threads = new Thread[transactionManager.activeCount()];
        transactionManager.enumerate(threads);
        for (int i = 0; i < threads.length; i++)
            if (threads[i] != null)
                threads[i].interrupt();

        threads = new Thread[transactionManager.activeCount()];
        transactionManager.enumerate(threads);
            for (int i = 0; i < threads.length; i++) {
                if (threads[i] != null) {
                    try {
                        if (! httpConfig.getStopFast()) {
                            threads[i].join(5000);
                        } else {
                            threads[i].join(100);
                        }

                    } catch (InterruptedException ignore) {
                    }
                    if (! httpConfig.getStopFast() && threads[i].isAlive()) {
                        // TBD, threads[i].stop();
                        log.error("Thread " + threads[i] + ", refuse to stop");
                    }
                }
        }

        if (thread != null)
            thread.interrupt();

        if (socket != null) {
            // Try different ways to find out local address.
            int port = socket.getLocalPort();
            InetAddress address = socket.getInetAddress();
            if (address.getHostAddress().equals(zeroAddress)) {
                try {
                    address = InetAddress.getLocalHost();
                } catch (UnknownHostException ignore) {
                    try {
                        address = InetAddress.getByName("localhost");
                    } catch (UnknownHostException ignore2) {
                        try {
                            address = InetAddress.getByName("127.0.0.1");
                        } catch (UnknownHostException e) {
                            address = null;
                            log.error("Failed to get local address", e);
                        }
                    }
                }
            }
            try {
                if (address != null) {
                    (new Socket(address, port)).close();
                }
            } catch (IOException ignore) {
                // Socket could already be closed?!
            } finally {
                socket = null;
            }
        }

        if (thread != null) {
            try {
                thread.join(60000);
            } catch (InterruptedException ignore) {
            } finally {
                if (thread.isAlive()) {
                    log.error("Failed to stop socket listener thread, " + thread);
                }
                thread = null;
            }
        }
    }

    public void destroy() {

        if (!httpConfig.isSecure()) {
            uninit();

        } else {
            ServiceTracker tempTr = this.securityTracker;
            this.securityTracker = null;
            if (tempTr != null) {
                try {
                    tempTr.close();

                } catch (Exception excpt) {
                }
            }
        }
    }

    public void run() {

        ServerSocket socket = this.socket;
        Socket client = null;

        while (!done) {

            try {

                while (transactionManager.activeCount() >= httpConfig
                        .getMaxConnections()) {
                    try {
                        Thread.sleep(50);
                        if (done)
                            break;
                    } catch (Exception e) {
                    }
                }

                if (!done) {
                    client = socket.accept();
                    if (done) {
                        client.close();
                        break;
                    }
                }

                transactionManager.startTransaction(client, httpConfig);

            } catch (InterruptedIOException iioe) {
                if (!done && log.isDebugEnabled())
                    log.debug("Communication error on "
                            + (host != null ? (host + ":") : "") + port, iioe);
            } catch (IOException ioe) {
                if (!done && log.isDebugEnabled())
                    log.debug("Communication error on "
                            + (host != null ? (host + ":") : "") + port, ioe);
            } catch (ThreadDeath td) {
                throw td;
            } catch (Throwable t) {
                if (!done && log.isDebugEnabled())
                    log.debug("Internal error on"
                            + (host != null ? (host + ":") : "") + port, t);
            }

        }

        try {
            if (socket != null)
                socket.close();
        } catch (IOException ignore) {
        }
        socket = null;
    }

} // SocketListener
