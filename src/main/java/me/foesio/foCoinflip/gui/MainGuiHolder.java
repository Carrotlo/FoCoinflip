package me.foesio.foCoinflip.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class MainGuiHolder implements InventoryHolder {

    private final int page;
    private final Map<Integer, UUID> slotToCoinflip = new HashMap<>();

    public MainGuiHolder(int page) {
        this.page = page;
    }

    public int getPage() {
        return page;
    }

    public void bindSlot(int slot, UUID coinflipId) {
        slotToCoinflip.put(slot, coinflipId);
    }

    public UUID getCoinflip(int slot) {
        return slotToCoinflip.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
