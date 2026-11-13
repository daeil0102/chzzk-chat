package net.teujaem.listener;

import net.teujaem.config.ConfigManger.Channel;
import net.teujaem.event.ChatManager;
import xyz.r2turntrue.chzzk4j.ChzzkClient;
import xyz.r2turntrue.chzzk4j.ChzzkClientBuilder;
import xyz.r2turntrue.chzzk4j.chat.ChzzkChat;
import xyz.r2turntrue.chzzk4j.chat.ChzzkChatBuilder;
import xyz.r2turntrue.chzzk4j.chat.event.ChatMessageEvent;
import xyz.r2turntrue.chzzk4j.chat.event.ConnectEvent;
import xyz.r2turntrue.chzzk4j.chat.event.ConnectionClosedEvent;
import xyz.r2turntrue.chzzk4j.chat.event.ErrorEvent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ChatListener {
    private final ChatManager manager;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ChzzkClient client = new ChzzkClientBuilder().build();
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();

    public record ChannelStatus(String id, String name, String status) {}

    public ChatListener(ChatManager manager) {
        this.manager = manager;
    }

    private static final class Subscription {
        private final String id;
        private String name;
        private String status = "연결 중";
        private CompletableFuture<ChzzkChat> connection;
        private ScheduledFuture<?> retry;
        private long attempt;

        private Subscription(Channel channel) {
            id = channel.id();
            name = channel.name();
        }

        private void disconnect() {
            if (retry != null) {
                retry.cancel(false);
            }
            if (connection != null) {
                connection.thenAccept(chat -> {
                    if (chat.isConnectedToChat()) {
                        chat.closeAsync().exceptionally(error -> null);
                    }
                });
            }
        }
    }

    public synchronized void applyChannels(List<Channel> channels) {
        Map<String, Subscription> updated = new LinkedHashMap<>();
        for (Channel channel : channels) {
            Subscription subscription = subscriptions.remove(channel.id());
            if (subscription == null) {
                var added = new Subscription(channel);
                updated.put(added.id, added);
                scheduler.execute(() -> connect(added));
            } else {
                subscription.name = channel.name();
                updated.put(subscription.id, subscription);
            }
        }
        subscriptions.values().forEach(Subscription::disconnect);
        subscriptions.clear();
        subscriptions.putAll(updated);
    }

    private synchronized void connect(Subscription channel) {
        if (!isRegistered(channel)) {
            return;
        }
        channel.status = "연결 중";
        channel.retry = null;
        long attempt = ++channel.attempt;
        AtomicBoolean retryScheduled = new AtomicBoolean();
        Runnable retry = () -> {
            if (retryScheduled.compareAndSet(false, true)) {
                scheduleRetry(channel, attempt);
            }
        };

        try {
            var chat = new ChzzkChatBuilder(client, channel.id).withAutoReconnect(false).build();
            chat.on(ConnectEvent.class, event -> setStatus(channel, "연결됨"));
            chat.on(ConnectionClosedEvent.class, event -> retry.run());
            chat.on(ErrorEvent.class, event -> logError(channel, event.getException()));
            chat.on(ChatMessageEvent.class, event -> {
                var message = event.getMessage();
                publish(channel, message.getContent());
            });
            channel.connection = chat.connectAsync().thenApply(result -> chat);
            channel.connection.whenComplete((result, error) -> {
                if (error != null) {
                    logError(channel, error);
                    retry.run();
                }
            });
        } catch (IOException | RuntimeException error) {
            logError(channel, error);
            retry.run();
        }
    }

    private synchronized void scheduleRetry(Subscription channel, long attempt) {
        if (isRegistered(channel) && channel.attempt == attempt && !scheduler.isShutdown()) {
            channel.status = "연결 안 됨 · 20초 후 재시도";
            channel.retry = scheduler.schedule(() -> {
                synchronized (this) {
                    if (channel.attempt == attempt) {
                        connect(channel);
                    }
                }
            }, 20, TimeUnit.SECONDS);
        }
    }

    public synchronized int retryFailedChannels() {
        int retried = 0;
        for (Subscription channel : subscriptions.values()) {
            if (channel.retry != null && channel.retry.cancel(false)) {
                connect(channel);
                retried++;
            }
        }
        return retried;
    }

    private boolean isRegistered(Subscription channel) {
        return subscriptions.get(channel.id) == channel;
    }

    private synchronized void logError(Subscription channel, Throwable error) {
        if (!isRegistered(channel)) {
            return;
        }
        while (error.getCause() != null) {
            error = error.getCause();
        }
        System.err.printf("[%s] %s%n", channel.name, error.getMessage());
    }

    private synchronized void setStatus(Subscription channel, String status) {
        if (isRegistered(channel)) {
            channel.status = status;
        }
    }

    private synchronized void publish(Subscription channel, String content) {
        if (isRegistered(channel) && content != null && !content.isBlank()) {
            manager.callEvent(content);
        }
    }

    public synchronized List<ChannelStatus> channelStatuses() {
        return subscriptions.values().stream()
                .map(channel -> new ChannelStatus(channel.id, channel.name, channel.status))
                .toList();
    }

    public synchronized void stop() {
        scheduler.shutdownNow();
        subscriptions.values().forEach(Subscription::disconnect);
        subscriptions.clear();
    }
}
