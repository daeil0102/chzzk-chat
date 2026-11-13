package net.teujaem.command;

import net.teujaem.config.ConfigLoad;
import net.teujaem.listener.ChatListener;
import net.teujaem.server.ChatViewServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.util.Locale;

public final class ConsoleCommand {
    private static final String HELP = "명령어: reload (설정과 리소스 적용), retry (연결 재시도), update (리소스 적용), help (명령어 보기)";

    private final ConfigLoad config;
    private final ChatListener listener;
    private final ChatViewServer server;
    private final int startupPort;

    public ConsoleCommand(ConfigLoad config, ChatListener listener, ChatViewServer server, int startupPort) {
        this.config = config;
        this.listener = listener;
        this.server = server;
        this.startupPort = startupPort;
    }

    public void run() throws IOException {
        System.out.println(HELP);
        var console = new BufferedReader(new InputStreamReader(System.in));
        String command;
        while ((command = console.readLine()) != null) {
            switch (command.strip().toLowerCase(Locale.ROOT)) {
                case "reload" -> reload();
                case "retry" -> System.out.printf("즉시 재시도 요청 · %d개 채널%n", listener.retryFailedChannels());
                case "update" -> update();
                case "" -> { }
                default -> System.out.println(HELP);
            }
        }
    }

    private void update() {
        try {
            server.updateResources();
            System.out.println("리소스 적용 완료 · 열린 채팅 화면을 새로고침합니다.");
        } catch (IOException error) {
            System.err.println("리소스 적용 실패: " + error.getMessage());
        }
    }

    private void reload() {
        try {
            var settings = config.reload();
            server.updateResources();
            listener.applyChannels(settings.getChannels());
            server.setSettings(settings);
            System.out.printf("설정 및 리소스 적용 완료 · %s 모드 · 등록 채널 %d개%n",
                    settings.getLayout().horizontalGrowth(), settings.getChannels().size());
            if (settings.getPort() != startupPort) {
                System.out.println("포트 변경을 적용하려면 서버를 다시 시작하세요.");
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException error) {
            System.err.println("설정 또는 리소스를 적용할 수 없습니다. 기존 설정을 사용합니다. " + error.getMessage());
        }
    }
}
