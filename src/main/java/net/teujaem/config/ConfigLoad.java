package net.teujaem.config;

import net.teujaem.config.ConfigManger.Channel;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class ConfigLoad {
    private final Path config;

    public ConfigLoad() {
        this(defaultPath());
    }

    public ConfigLoad(Path config) {
        this.config = config.toAbsolutePath().normalize();
    }

    public Path getPath() {
        return config;
    }

    private static Path defaultPath() {
        try {
            Path location = Path.of(ConfigLoad.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location)
                    ? location.resolveSibling("config.yaml")
                    : Path.of("config.yaml");
        } catch (URISyntaxException error) {
            throw new IllegalStateException("설정 파일 경로 확인 실패", error);
        }
    }

    public ConfigManger reload() {
        createDefaults(config, "/config.yaml", Map.of());
        Map<?, ?> root = readConfig(config);
        Object rawPort = root.containsKey("port") ? root.get("port") : ConfigManger.DEFAULT_PORT;
        if (!(rawPort instanceof Integer port) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("port는 1~65535 사이의 정수여야 합니다.");
        }
        var defaults = ConfigManger.DEFAULT_LAYOUT;
        Object rawHorizontal = root.containsKey("horizontalGrowth")
                ? root.get("horizontalGrowth") : defaults.horizontalGrowth();
        String horizontal = switch (rawHorizontal instanceof String value ? value : "") {
            case "bidirectional", "center" -> "bidirectional";
            case "unidirectional", "left" -> "unidirectional";
            default -> throw new IllegalArgumentException("horizontalGrowth는 bidirectional 또는 unidirectional이어야 합니다.");
        };
        createProfile("bidirectional", "center", root);
        createProfile("unidirectional", "left", root);
        var bidirectional = readLayout("bidirectional");
        var unidirectional = readLayout("unidirectional");
        Map<String, Channel> channels = new LinkedHashMap<>();
        Object rawChannels = root.get("channels");
        if (rawChannels instanceof String id) {
            if (!id.isBlank()) {
                addChannel(channels, id, null);
            }
        } else if (rawChannels instanceof List<?> entries) {
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> fields)) {
                    throw new IllegalArgumentException("channels 목록의 각 항목에 id를 지정하세요.");
                }
                addChannel(channels, fields.get("id"), fields.get("name"));
            }
        } else {
            throw new IllegalArgumentException("channels에 채널 ID 또는 채널 목록을 지정하세요.");
        }
        return new ConfigManger(List.copyOf(channels.values()), port, horizontal, bidirectional, unidirectional);
    }

    private ConfigManger.Layout readLayout(String mode) {
        Map<?, ?> profile = readConfig(config.resolveSibling("config-" + mode + ".yaml"));
        var defaults = ConfigManger.DEFAULT_LAYOUT;
        Object direction = profile.containsKey("growthDirection") ? profile.get("growthDirection") : defaults.growthDirection();
        if (!"up".equals(direction) && !"down".equals(direction)) {
            throw new IllegalArgumentException("growthDirection은 up 또는 down이어야 합니다.");
        }
        Object align = profile.containsKey("chatAlign") ? profile.get("chatAlign") : defaults.chatAlign();
        if (!(align instanceof String alignment)
                || !List.of("auto", "left", "center", "right").contains(alignment)) {
            throw new IllegalArgumentException("chatAlign은 auto, left, center, right 중 하나여야 합니다.");
        }
        return new ConfigManger.Layout(
                readInteger(profile, "chatGap", defaults.chatGap()),
                readPixels(profile, "iconSize", defaults.iconSize()),
                readPixels(profile, "fontSize", defaults.fontSize()),
                readPixels(profile, "maxChatWidth", defaults.maxChatWidth()),
                readPixels(profile, "topBarOffset", defaults.topBarOffset()),
                (String) direction,
                readPixels(profile, "sideWidth", defaults.sideWidth()),
                readInteger(profile, "iconOffsetX", defaults.iconOffsetX()),
                readInteger(profile, "iconOffsetY", defaults.iconOffsetY()),
                readPixels(profile, "iconMotionDistance", defaults.iconMotionDistance()),
                readNonNegativeInteger(profile, "iconMotionDurationMs", defaults.iconMotionDurationMs(), "ms"),
                mode,
                alignment,
                readPixels(profile, "cardRadius", defaults.cardRadius()),
                readPixels(profile, "chatMotionX", defaults.chatMotionX()),
                readPixels(profile, "chatMotionY", defaults.chatMotionY()),
                readNonNegativeInteger(profile, "chatMotionDurationMs", defaults.chatMotionDurationMs(), "ms"),
                readNonNegativeInteger(profile, "chatMotionStaggerMs", defaults.chatMotionStaggerMs(), "ms"));
    }

    private void createProfile(String mode, String previousMode, Map<?, ?> legacy) {
        String name = "config-" + mode + ".yaml";
        Path target = config.resolveSibling(name);
        Path previous = config.resolveSibling("config-" + previousMode + ".yaml");
        if (Files.notExists(target) && Files.isRegularFile(previous)) {
            try {
                Files.copy(previous, target);
            } catch (FileAlreadyExistsException ignored) {
                // Another process created the configuration first.
            } catch (IOException error) {
                throw new UncheckedIOException("설정 파일 이전 실패: " + target, error);
            }
        }
        createDefaults(target, "/" + name, legacy);
    }

    private static Yaml yamlReader() {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    private static Map<?, ?> readConfig(Path path) {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object document = yamlReader().load(reader);
            if (document == null) {
                return Map.of();
            }
            if (document instanceof Map<?, ?> entries) {
                return entries;
            }
            throw new IllegalArgumentException(path.getFileName() + "에 YAML 설정 항목을 지정하세요.");
        } catch (IOException error) {
            throw new UncheckedIOException("설정 파일 로드 실패: " + path, error);
        } catch (YAMLException error) {
            throw new IllegalArgumentException(path.getFileName() + ": " + error.getMessage(), error);
        }
    }

    private static void createDefaults(Path path, String resource, Map<?, ?> legacy) {
        if (!Files.notExists(path)) {
            return;
        }
        try (var input = Objects.requireNonNull(ConfigLoad.class.getResourceAsStream(resource), resource)) {
            if (legacy.isEmpty()) {
                Files.copy(input, path);
                return;
            }
            String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> profile = yamlReader().load(template);
            boolean migrated = false;
            for (var entry : profile.entrySet()) {
                if (legacy.containsKey(entry.getKey())) {
                    entry.setValue(legacy.get(entry.getKey()));
                    migrated = true;
                }
            }
            Files.writeString(path, migrated ? new Yaml().dump(profile) : template,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException ignored) {
            // Another process created the configuration first.
        } catch (IOException error) {
            throw new UncheckedIOException("설정 파일 생성 실패: " + path, error);
        }
    }

    private static int readPixels(Map<?, ?> root, String key, int defaultValue) {
        return readNonNegativeInteger(root, key, defaultValue, "px");
    }

    private static int readNonNegativeInteger(Map<?, ?> root, String key, int defaultValue, String unit) {
        int value = readInteger(root, key, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(key + "는 0 이상의 정수(" + unit + ")여야 합니다.");
        }
        return value;
    }

    private static int readInteger(Map<?, ?> root, String key, int defaultValue) {
        Object value = root.containsKey(key) ? root.get(key) : defaultValue;
        if (!(value instanceof Integer integer)) {
            throw new IllegalArgumentException(key + "는 정수여야 합니다.");
        }
        return integer;
    }

    private static void addChannel(Map<String, Channel> channels, Object rawId, Object rawName) {
        if (!(rawId instanceof String id) || !id.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("채널 id는 따옴표로 감싼 32자리 16진수여야 합니다.");
        }
        if (rawName != null && (!(rawName instanceof String name) || name.isBlank())) {
            throw new IllegalArgumentException("채널 name은 비어 있지 않은 문자열이어야 합니다.");
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        if (channels.putIfAbsent(normalized, new Channel(normalized, rawName == null ? normalized : (String) rawName)) != null) {
            throw new IllegalArgumentException("중복된 채널 ID: " + id);
        }
    }
}
