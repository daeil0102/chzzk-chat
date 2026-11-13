package net.teujaem;

import net.teujaem.command.ConsoleCommand;
import net.teujaem.config.ConfigLoad;
import net.teujaem.event.ChatManager;
import net.teujaem.listener.ChatListener;
import net.teujaem.server.ChatViewServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        var config = args.length == 0 ? new ConfigLoad() : new ConfigLoad(Path.of(args[0]));
        try {
            var settings = config.reload();
            var manager = new ChatManager();
            var listener = new ChatListener(manager);
            var server = new ChatViewServer(config.getPath().resolveSibling("images"), listener::channelStatuses, settings);
            manager.register(server);
            server.start(settings.getPort());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                listener.stop();
                server.stop();
            }));
            listener.applyChannels(settings.getChannels());
            new ConsoleCommand(config, listener, server, settings.getPort()).run();
        } catch (IOException | UncheckedIOException | IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }
}
