package me.foesio.foCoinflip.util;

import java.util.Map;
import me.foesio.core.message.FoMessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

/** Prevents Core's English missing-key diagnostic from becoming player text. */
public final class ConfiguredPublicMessages {

    private ConfiguredPublicMessages() {
    }

    public static void send(FoMessageService service, CommandSender recipient, String path) {
        send(service, recipient, path, Map.of());
    }

    public static void send(FoMessageService service, CommandSender recipient, String path,
                            Map<String, String> placeholders) {
        if (hasText(service.config(), path)) {
            service.send(recipient, path, "", placeholders);
        }
    }

    public static void broadcast(FoMessageService service, String path, Map<String, String> placeholders) {
        if (hasText(service.config(), path)) {
            service.broadcastConfigured(path, placeholders);
        }
    }

    static boolean hasText(FileConfiguration config, String path) {
        if (!config.contains(path, true)) {
            return false;
        }
        if (config.isList(path)) {
            return !config.getStringList(path).isEmpty();
        }
        String value = config.getString(path);
        return value != null && !value.isEmpty();
    }
}
