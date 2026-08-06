package me.foesio.foCoinflip.listener;

import java.util.function.Supplier;
import me.foesio.foCoinflip.CoinflipManager;
import me.foesio.core.FoCoreContext;
import me.foesio.core.editor.EditorMenuHolder;
import me.foesio.foCoinflip.gui.AnimationHolder;
import me.foesio.foCoinflip.gui.GuiManager;
import me.foesio.foCoinflip.gui.MainGuiHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

public class CoinflipListener implements Listener {

    private final CoinflipManager coinflipManager;
    private final GuiManager guiManager;
    private final Supplier<FoCoreContext> coreSupplier;

    public CoinflipListener(CoinflipManager coinflipManager,
            GuiManager guiManager, Supplier<FoCoreContext> coreSupplier) {
        this.coinflipManager = coinflipManager;
        this.guiManager = guiManager;
        this.coreSupplier = coreSupplier;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        EditorMenuHolder editorHolder = holder instanceof EditorMenuHolder candidate
                && "focoinflip-admin".equals(candidate.id()) ? candidate : null;
        if (!(holder instanceof MainGuiHolder) && editorHolder == null && !(holder instanceof AnimationHolder)) {
            return;
        }

        event.setCancelled(true);

        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() < 0 || event.getRawSlot() >= topSize) {
            return;
        }

        if (holder instanceof MainGuiHolder mainGuiHolder) {
            guiManager.handleMainClick(player, mainGuiHolder, event.getRawSlot());
            return;
        }

        if (editorHolder != null) {
            guiManager.handleAdminClick(player, editorHolder, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        FoCoreContext core = coreSupplier.get();
        if (core != null && core.inventoryCloseSuppressor().consumeSuppressedClose(player)) {
            return;
        }

        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof AnimationHolder animationHolder) {
            guiManager.handleAnimationClose(player, animationHolder);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        coinflipManager.handleCreatorQuit(player.getUniqueId());
        guiManager.handlePlayerQuit(player);
    }
}
