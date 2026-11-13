package net.teujaem.config;

import java.util.List;
import java.util.Map;

public class ConfigManger {
    public static final int DEFAULT_PORT = 8080;
    public static final Layout DEFAULT_LAYOUT = defaultLayout("bidirectional");

    public record Channel(String id, String name) {}
    public record Layout(int chatGap, int iconSize, int fontSize, int maxChatWidth, int topBarOffset,
                         String growthDirection, int sideWidth, int iconOffsetX, int iconOffsetY,
                         int iconMotionDistance, int iconMotionDurationMs, String horizontalGrowth,
                         String chatAlign, int cardRadius, int chatMotionX, int chatMotionY,
                         int chatMotionDurationMs, int chatMotionStaggerMs) {}

    private final List<Channel> channels;
    private final int port;
    private final String defaultMode;
    private final Map<String, Layout> layouts;

    public ConfigManger(String id, int port) {
        this(id.isBlank() ? List.of() : List.of(new Channel(id, id)), port);
    }

    public ConfigManger(List<Channel> channels, int port) {
        this(channels, port, DEFAULT_LAYOUT.horizontalGrowth(), DEFAULT_LAYOUT, defaultLayout("unidirectional"));
    }

    public ConfigManger(List<Channel> channels, int port, String defaultMode,
                        Layout bidirectional, Layout unidirectional) {
        this.channels = List.copyOf(channels);
        this.port = port;
        this.defaultMode = defaultMode;
        this.layouts = Map.of("bidirectional", bidirectional, "unidirectional", unidirectional);
    }

    private static Layout defaultLayout(String mode) {
        return new Layout(0, 130, 60, 0, 10, "up", 48, 0, 0, 3, 2800, mode, "auto", 0, 0, 0, 4000, 350);
    }

    public String getId() {
        return channels.isEmpty() ? "" : channels.getFirst().id();
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public int getPort() {
        return port;
    }

    public Layout getLayout() {
        return getLayout(defaultMode);
    }

    public Layout getLayout(String mode) {
        return layouts.get(mode);
    }
}
