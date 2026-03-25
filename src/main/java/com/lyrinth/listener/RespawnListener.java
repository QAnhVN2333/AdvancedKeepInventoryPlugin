package com.lyrinth.listener;

import com.lyrinth.AdvancedKeepInventory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;

/**
 * RespawnListener - Trả lại vật phẩm đã giữ khi người chơi hồi sinh.
 * Khi chết, DeathListener lưu items cần giữ vào cache.
 * Khi respawn, listener này trả lại items đó cho inventory.
 *
 * [CRASH-SAFE] Cũng xử lý PlayerJoinEvent để recovery items
 * nếu server đã crash trước khi player kịp respawn.
 */
public class RespawnListener implements Listener {

    private final AdvancedKeepInventory plugin;

    public RespawnListener(AdvancedKeepInventory plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();

        // Lấy danh sách items đã giữ từ cache (tự fallback đọc file nếu RAM trống)
        List<ItemStack> keptItems = plugin.retrieveKeptItems(player.getUniqueId());
        if (keptItems == null || keptItems.isEmpty()) return;

        // Trả lại items vào inventory của người chơi
        // Dùng addItem() - tự tìm slot trống, nếu đầy thì drop ra đất
        for (ItemStack item : keptItems) {
            var leftover = player.getInventory().addItem(item);
            // Nếu inventory đầy -> drop item ra đất
            leftover.values().forEach(remaining ->
                    player.getWorld().dropItemNaturally(player.getLocation(), remaining)
            );
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[Debug] Trả lại " + keptItems.size()
                    + " item(s) cho " + player.getName() + " khi respawn.");
        }
    }

    /**
     * [CRASH-SAFE] Khi player join lại sau server crash/restart,
     * kiểm tra xem có file backup chưa trả không.
     *
     * Delay 20 ticks (1 giây) để đảm bảo player đã load đầy đủ
     * (inventory, location, v.v.) trước khi trả items.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Chỉ xử lý nếu player nằm trong danh sách pending recovery
        if (!plugin.getDeathCacheManager().hasPendingRecovery(player.getUniqueId())) return;

        // Delay 20 ticks (1s) để đảm bảo player đã load xong
        new BukkitRunnable() {
            @Override
            public void run() {
                // Kiểm tra player vẫn online sau 1 giây
                if (!player.isOnline()) return;

                boolean recovered = plugin.getDeathCacheManager().tryRecoverOnJoin(player);
                if (recovered) {
                    // Thông báo cho player biết items đã được khôi phục
                    String msg = plugin.getConfigManager().getMessage("death.items-recovered");
                    if (msg != null && !msg.isEmpty()) {
                        plugin.getMessageManager().send(
                                player, msg, Map.of(),
                                plugin.getConfigManager().getNotificationConfig("death.items-recovered")
                        );
                    } else {
                        // Fallback nếu chưa có message trong messages.yml
                        player.sendMessage("§a[AdvancedKeepInventory] §fItems từ lần chết trước đã được khôi phục!");
                    }
                }
            }
        }.runTaskLater(plugin, 20L);
    }
}
