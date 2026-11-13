package net.teujaem.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatManager {

    private final List<ChatEvent> listeners = new CopyOnWriteArrayList<>();

    public void register(ChatEvent listener) {
        listeners.add(listener);
    }

    public void unregister(ChatEvent listener) {
        listeners.remove(listener);
    }

    public void callEvent(String message) {
        for (ChatEvent listener : listeners) {
            listener.onChat(message);
        }
    }

}