package me.foesio.foCoinflip.gui;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class AnimationHolder implements InventoryHolder {

    private final UUID sessionId;

    public AnimationHolder(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
