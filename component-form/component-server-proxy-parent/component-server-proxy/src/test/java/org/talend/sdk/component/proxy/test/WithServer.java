/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
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
package org.talend.sdk.component.proxy.test;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Stream.of;
import static org.apache.openejb.loader.JarLocation.jarLocation;

import play.Application;
import play.ApplicationLoader;
import play.Environment;
import play.Mode;
import play.test.TestServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.ServerSocket;
import java.net.URL;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;

import org.apache.johnzon.jaxrs.JsrProvider;
import org.apache.johnzon.jaxrs.jsonb.jaxrs.JsonbJaxrsProvider;
import org.apache.openejb.loader.Files;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.Zips;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.talend.sdk.component.proxy.test.component.TheService;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class SurefireWorkaroundOutput implements Runnable {

    private final String name;

    private final InputStream stream;

    private final ByteArrayOutputStream builder = new ByteArrayOutputStream();

    SurefireWorkaroundOutput(final String name, final InputStream stream) {
        this.name = name;
        this.stream = stream;
    }

    @Override
    public void run() {
        try {
            final byte[] buf = new byte[64];
            int num;
            while ((num = stream.read(buf)) != -1) { // todo: rework it to handle EOL
                for (int i = 0; i < num; i++) {
                    if (buf[i] == '\r' || buf[i] == '\n') {
                        doLog();
                        builder.reset();
                    } else {
                        builder.write(buf[i]);
                    }
                }
            }
            if (builder.size() > 0) {
                doLog();
            }
        } catch (final IOException e) {
            // no-op
        }
    }

    private void doLog() {
        final String string = builder.toString().trim();
        if (string.isEmpty()) {
            return;
        }
        log.info("[" + name + "] " + string);
    }

}

class Network {

    static int randomPort() {
        try (final ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void ensureStarted(final Supplier<Boolean> isStarted) {
        int maxRetries = 1200;
        while (!isStarted.get() && --maxRetries > 0) {
            try {
                Thread.sleep(100);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (maxRetries <= 0) {
            throw new IllegalStateException("Tacokit didn't start");
        }
    }
}

@Data
class Env implements AutoCloseable {

    private final WithServer.Tacokit tacokit;

    private final TestServer playServer;

    @Override
    public void close() {
        try {
            if (tacokit != null) {
                tacokit.close();
            }
        } finally {
            if (playServer != null) {
                playServer.stop();
            }
        }
    }
}

@Target(TYPE)
@Retention(RUNTIME)
@ExtendWith(WithServer.Extension.class)
public @interface WithServer {

    class Extension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

        private static final ExtensionContext.Namespace NAMESPACE =
                ExtensionContext.Namespace.create(Extension.class.getName());

        private static final AtomicReference<Env> ENV = new AtomicReference<>();

        @Override
        public void beforeAll(final ExtensionContext extensionContext) {
            final ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);

            if (ENV.get() == null) {
                synchronized (Extension.class) {
                    if (ENV.get() == null) {
                        final Tacokit tacokit = startTacokitRemoteServer(Network.randomPort());
                        ENV.set(new Env(tacokit, null));
                        Runtime.getRuntime().addShutdownHook(
                                new Thread(ENV.get()::close, "proxy-test-servers-shutdown"));

                        tacokit.prepare(extensionContext.getTestClass().get());
                        tacokit.launch();
                        store.put(Tacokit.class, tacokit);

                        final TestServer playServer = startPlay(tacokit.port);
                        store.put(TestServer.class, playServer);

                        if (!tacokit.isSkipped()) {
                            Network.ensureStarted(() -> {
                                try {
                                    return IO
                                            .slurp(new URL("http://localhost:" + tacokit.port + "/api/v1/environment"))
                                            .contains("\"version\"");
                                } catch (final IOException e) {
                                    return false;
                                }
                            });
                        }

                        ENV.set(new Env(tacokit, playServer));
                        playServer.start();
                        Network.ensureStarted(() -> {
                            try {
                                return IO
                                        .slurp(new URL("http://localhost:" + playServer.port()
                                                + "/componentproxy/api/v1/internaltest/ping"))
                                        .trim()
                                        .equals("ok");
                            } catch (final IOException e) {
                                return false;
                            }
                        });
                    }
                }
            }

            final Client client =
                    ClientBuilder.newClient().register(new JsonbJaxrsProvider<>()).register(new JsrProvider());
            store.put(TestServer.class, ENV.get().getPlayServer());
            store.put(Tacokit.class, ENV.get().getTacokit());
            store.put(Client.class, client);
            store.put(WebTarget.class,
                    client.target("http://localhost:" + ENV.get().getPlayServer().port() + "/componentproxy/api/v1"));
        }

        @Override
        public void afterAll(ExtensionContext extensionContext) {
            ofNullable(extensionContext.getStore(NAMESPACE).get(Client.class)).map(Client.class::cast).ifPresent(
                    Client::close);
        }

        @Override
        public boolean supportsParameter(final ParameterContext parameterContext,
                final ExtensionContext extensionContext) throws ParameterResolutionException {
            final Class<?> type = parameterContext.getParameter().getType();
            return extensionContext.getStore(NAMESPACE).get(type) != null;
        }

        @Override
        public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext)
                throws ParameterResolutionException {
            return extensionContext.getStore(NAMESPACE).get(parameterContext.getParameter().getType());
        }

        private TestServer startPlay(final int tacokitPort) {
            return new TestServer(Network.randomPort(), startPlayApplication(tacokitPort));
        }

        private Application startPlayApplication(final int tacokitPort) {
            final ApplicationLoader.Context context =
                    ApplicationLoader.create(
                            new Environment(new File(jarLocation(Extension.class), "test/play/conf"),
                                    Thread.currentThread().getContextClassLoader(), Mode.TEST),
                            new HashMap<String, Object>() {

                                {
                                    put("config.resource", "test/play/conf/application.conf");
                                    put("test.replaced", "done");
                                    put("talend.component.proxy.processing.headers",
                                            "Talend-Test=true\nTalend-Replaced=${test.replaced}");
                                    put("talend.component.proxy.server.base",
                                            "http://localhost:" + tacokitPort + "/api/v1");
                                }
                            });
            return ApplicationLoader.apply(context).load(context);
        }

        private Tacokit startTacokitRemoteServer(final int port) {
            return new Tacokit(port);
        }
    }

    @Slf4j
    @RequiredArgsConstructor
    class Tacokit implements AutoCloseable, Runnable {

        @Getter
        private final int port;

        private File libs;

        private File m2;

        private Process process;

        private Thread thread;

        private String getComponents() {
            return "org.talend.test:test-component:1.0.0";
        }

        private void createM2() {
            final File jar = new File(m2, "org/talend/test/test-component/1.0.0/test-component-1.0.0.jar");
            if (!jar.getParentFile().mkdirs()) {
                throw new IllegalStateException(
                        "Can't prepare test environment. cause : can't create component jar " + jar.getAbsolutePath());
            }
            try (final JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
                final String packageToInclude = TheService.class.getPackage().getName();
                final String[] pck = packageToInclude.split("\\.");
                for (int i = 0; i < pck.length; i++) {
                    final String folder = of(pck).limit(i + 1).collect(joining("/"));
                    final JarEntry entry = new JarEntry(folder + '/');
                    out.putNextEntry(entry);
                    out.closeEntry();
                }
                final String pckPath = packageToInclude.replace('.', '/');
                of(ofNullable(new File(jarLocation(Tacokit.class), pckPath).listFiles()).orElseGet(() -> new File[0]))
                        .forEach(file -> appendEntryToJar(out, pckPath, file));
                // add components icons
                of(ofNullable(new File(jarLocation(Tacokit.class), "icons").listFiles()).orElseGet(() -> new File[0]))
                        .filter(c -> c.getName().endsWith("_icon32.png"))
                        .forEach(file -> {
                            final JarEntry entry = new JarEntry("icons/" + file.getName());
                            try {
                                out.putNextEntry(entry);
                                IO.copy(file, out);
                                out.closeEntry();
                            } catch (final IOException e) {
                                throw new IllegalStateException(e);
                            }
                        });
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void appendEntryToJar(final JarOutputStream out, final String pckPath, final File file) {
            try {
                if (file.isDirectory()) {
                    final JarEntry entry = new JarEntry(pckPath + '/' + file.getName() + "/");
                    out.putNextEntry(entry);
                    out.closeEntry();
                    of(ofNullable(file.listFiles()).orElse(new File[0]))
                            .forEach(f -> appendEntryToJar(out, pckPath + "/" + file.getName(), f));
                } else if (file.getName().endsWith(".class") || file.getName().endsWith(".properties")) {
                    final JarEntry entry = new JarEntry(pckPath + '/' + file.getName());
                    out.putNextEntry(entry);
                    IO.copy(file, out);
                    out.closeEntry();
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void prepare(final Class<?> marker) {
            if (isSkipped()) {
                return;
            }
            final File target = jarLocation(marker).getParentFile();
            final File distrib = new File(target.getParentFile(),
                    "../../../component-server-parent/component-server/target/component-server-meecrowave-distribution.zip");
            if (!distrib.exists()) {
                throw new IllegalArgumentException(
                        "No component server zip available, did you build the server before? (" + distrib + ")");
            }
            final File distribExploded = new File(target, "test-tacokit-distrib_" + UUID.randomUUID().toString());
            distribExploded.mkdirs();
            Files.deleteOnExit(distribExploded);
            try {
                Zips.unzip(distrib, distribExploded);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            libs = new File(distribExploded, "component-server-distribution/lib");
            if (!libs.isDirectory()) {
                throw new IllegalStateException("No lib folder in " + distribExploded.getAbsolutePath());
            }
            m2 = new File(distribExploded, ".m2/repository");
            m2.mkdirs();
            createM2();
        }

        @Override
        public void run() {
            if (isSkipped()) {
                return;
            }
            final String classpath =
                    of(ofNullable(libs.listFiles()).orElseGet(() -> new File[0])).map(File::getAbsolutePath).collect(
                            joining(File.pathSeparator));

            try {
                process = new ProcessBuilder()
                        .directory(libs.getParentFile())
                        .command(of(
                                new File(System.getProperty("java.home"),
                                        "/bin/java" + (OS.WINDOWS.isCurrentOs() ? ".exe" : "")).getAbsolutePath(),
                                "-cp", classpath, "-Dtalend.component.server.maven.repository=" + m2.getAbsolutePath(),
                                "-Dtalend.component.server.component.coordinates=" + getComponents(),
                                "org.apache.meecrowave.runner.Cli", "--http", Integer.toString(port)).collect(toList()))
                        .start();
                new Thread(new SurefireWorkaroundOutput("tacokit-server-out", process.getInputStream()),
                        "tacokit-server-out").start();
                new Thread(new SurefireWorkaroundOutput("tacokit-server-err", process.getErrorStream()),
                        "tacokit-server-err").start();
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void close() {
            try {
                ofNullable(process).ifPresent(p -> {
                    try {
                        p.destroyForcibly().waitFor();
                        log.info("Killed tacokit test server");
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } finally {
                ofNullable(thread).ifPresent(t -> {
                    try {
                        t.join();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        // useful when developping/running tests not requiring the remote server
        private boolean isSkipped() {
            return Boolean.getBoolean("component.test.proxy.tacokit.skip");
        }

        private void launch() {
            thread = new Thread(this);
            thread.setName("tacokit-server@localhost:" + port);
            thread.start();
        }
    }
}
