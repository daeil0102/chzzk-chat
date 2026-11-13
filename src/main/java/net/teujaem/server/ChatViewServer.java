package net.teujaem.server;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.teujaem.config.ConfigManger;
import net.teujaem.event.ChatEvent;
import net.teujaem.listener.ChatListener.ChannelStatus;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public class ChatViewServer implements ChatEvent {
    private static final int MESSAGE_LIMIT = 300;
    private static final Gson JSON = new Gson();
    private static final Set<String> THEME_IMAGES = Set.of(
            "icon.png", "top_bar.png", "bottom_bar.png", "backgrund_left.png",
            "backgrund_middle.png", "backgrund_right.png", "base_backgrund.png", "main_backgrund.png");
    private static final Set<String> LEGACY_IMAGES = Set.of("bar.png", "backgrund.png");
    private static final Set<String> IMAGE_MODES = Set.of("center", "left");
    private static final Set<String> BARS = Set.of("top_bar.png", "bottom_bar.png");
    private static final Set<String> PAGE_PATHS = Set.of(
            "/", "/bidirectional", "/bidirectional/", "/unidirectional", "/unidirectional/");
    private static final String FONT_NAME = "Jua-Regular.ttf";
    private final ArrayDeque<Message> messages = new ArrayDeque<>();
    private final Path images;
    private final Path fonts;
    private final Supplier<List<ChannelStatus>> channels;
    private HttpServer server;
    private long sequence;
    private volatile Page page;
    private volatile ConfigManger settings;

    private record Message(long id, String content) {}
    private record Page(String version, byte[] html) {}
    private record Snapshot(String resourceVersion, ConfigManger.Layout layout,
                            List<ChannelStatus> channels, List<Message> messages) {}

    public ChatViewServer(Path images, Supplier<List<ChannelStatus>> channels, ConfigManger settings) {
        this.images = images;
        this.fonts = images.resolveSibling("fonts");
        this.channels = channels;
        this.settings = settings;
    }

    public void start(int port) throws IOException {
        updateResources();
        server = bindServer(port);
        server.createContext("/images/", exchange -> serveThemeImage(exchange));
        server.createContext("/fonts/" + FONT_NAME, exchange -> {
            if (acceptGet(exchange, "/fonts/" + FONT_NAME)) {
                serveResource(exchange, fonts.resolve(FONT_NAME), "/fonts/" + FONT_NAME, "font/ttf");
            }
        });
        server.createContext("/api/chat", exchange -> {
            String path = exchange.getRequestURI().getPath();
            var current = settings;
            ConfigManger.Layout layout = switch (path) {
                case "/api/chat" -> current.getLayout();
                case "/api/chat/bidirectional" -> current.getLayout("bidirectional");
                case "/api/chat/unidirectional" -> current.getLayout("unidirectional");
                default -> null;
            };
            if (layout == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            } else if (acceptGet(exchange, path)) {
                var snapshot = new Snapshot(page.version(), layout, channels.get(), snapshotMessages());
                send(exchange, "application/json; charset=utf-8", JSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!PAGE_PATHS.contains(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            } else if (acceptGet(exchange, path)) {
                send(exchange, "text/html; charset=utf-8", page.html());
            }
        });
        server.start();
        String address = "http://localhost:" + server.getAddress().getPort();
        System.out.println("치지직 채팅: " + address + "/");
        System.out.println("양방향: " + address + "/bidirectional");
        System.out.println("단방향: " + address + "/unidirectional");
    }

    public void setSettings(ConfigManger settings) {
        this.settings = settings;
    }

    public void updateResources() throws IOException {
        Files.createDirectories(images);
        Files.createDirectories(fonts);
        boolean migrateLegacyImages = IMAGE_MODES.stream().noneMatch(mode -> Files.exists(images.resolve(mode)));
        for (String mode : IMAGE_MODES) {
            Path folder = Files.createDirectories(images.resolve(mode));
            for (String name : THEME_IMAGES) {
                if (mode.equals("center") || !BARS.contains(name)) {
                    createResource(folder.resolve(name), "/images/" + name,
                            migrateLegacyImages ? rootImage(name) : null);
                }
            }
        }
        createResource(fonts.resolve(FONT_NAME), "/fonts/" + FONT_NAME, null);
        createResource(fonts.resolve("OFL.txt"), "/fonts/OFL.txt", null);

        String version = UUID.randomUUID().toString();
        try (var input = bundledResource("/index.html")) {
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("__CHAT_RESOURCE_VERSION__", version);
            page = new Page(version, html.getBytes(StandardCharsets.UTF_8));
            System.out.println("이미지 폴더: " + images.toAbsolutePath());
        }
    }

    private static void createResource(Path target, String resource, Path template) throws IOException {
        if (Files.notExists(target)) {
            try (var input = template != null && Files.isRegularFile(template)
                    ? Files.newInputStream(template) : bundledResource(resource)) {
                Files.copy(input, target);
            } catch (FileAlreadyExistsException ignored) {
                // A custom file appeared during the update.
            }
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("리소스 파일을 사용할 수 없습니다: " + target);
        }
    }

    private static java.io.InputStream bundledResource(String resource) throws IOException {
        var input = ChatViewServer.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IOException("내장 리소스를 찾을 수 없습니다: " + resource);
        }
        return input;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Override
    public void onChat(String message) {
        handleChat(message);
    }

    public synchronized void handleChat(String message) {
        if (messages.size() == MESSAGE_LIMIT) {
            messages.removeFirst();
        }
        messages.addLast(new Message(++sequence, message));
    }

    private synchronized List<Message> snapshotMessages() {
        return List.copyOf(messages);
    }

    private static HttpServer bindServer(int port) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (BindException ex) {
            if (port != ConfigManger.DEFAULT_PORT) {
                throw new IOException(port + " 포트에 연결할 수 없습니다. config.yaml에서 다른 포트를 지정하세요.", ex);
            }
            System.out.println("8080 포트가 사용 중입니다. 다른 로컬 포트로 시작합니다.");
            try {
                return HttpServer.create(new InetSocketAddress("127.0.0.1", 8081), 0);
            } catch (BindException fallbackInUse) {
                return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            }
        }
    }

    private static boolean acceptGet(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(path)) {
            exchange.sendResponseHeaders(404, -1);
        } else if (!exchange.getRequestMethod().equals("GET")) {
            exchange.getResponseHeaders().set("Allow", "GET");
            exchange.sendResponseHeaders(405, -1);
        } else {
            return true;
        }
        exchange.close();
        return false;
    }

    private void serveThemeImage(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.substring("/images/".length()).split("/", -1);
        String name = parts[parts.length - 1];
        boolean root = parts.length == 1 && (THEME_IMAGES.contains(name) || LEGACY_IMAGES.contains(name));
        boolean themed = parts.length == 2 && IMAGE_MODES.contains(parts[0]) && THEME_IMAGES.contains(name);
        if (!root && !themed) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        if (!acceptGet(exchange, path)) {
            return;
        }

        Path custom = themed ? images.resolve(parts[0]).resolve(name) : rootImage(name);
        serveResource(exchange, custom, "/images/" + name, "image/png");
    }

    private Path rootImage(String name) {
        Path custom = images.resolve(name);
        if (name.equals("top_bar.png") && !Files.isRegularFile(custom)) {
            return images.resolve("bar.png");
        }
        return custom;
    }

    private static void serveResource(HttpExchange exchange, Path custom, String resource, String type) throws IOException {
        byte[] data;
        try (var input = Files.isRegularFile(custom) ? Files.newInputStream(custom) : bundledResource(resource)) {
            data = input.readAllBytes();
        } catch (IOException error) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        send(exchange, type, data);
    }

    private static void send(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
