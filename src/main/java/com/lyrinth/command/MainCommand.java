package com.lyrinth.command;

import com.lyrinth.AdvancedKeepInventory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * MainCommand - Xử lý lệnh /advancedkeepinventory (alias: /aki).
 * Hiện tại chỉ hỗ trợ subcommand: reload.
 */
public class MainCommand implements CommandExecutor, TabCompleter {

    private final AdvancedKeepInventory plugin;

    public MainCommand(AdvancedKeepInventory plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        // Không có argument -> hiển thị usage
        if (args.length == 0) {
            String usageMsg = plugin.getConfigManager().getMessage("command.usage");
            // Fallback nếu key chưa có trong messages.yml
            String display = usageMsg.isEmpty()
                    ? "§6[AKI] §fUsage: /" + label + " reload"
                    : usageMsg.replace("%label%", label);
            sendRaw(sender, display);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        return switch (subCommand) {
            case "reload" -> handleReload(sender);
            default -> {
                String invalidMsg = plugin.getConfigManager().getMessage("command.invalid-subcommand");
                String display = invalidMsg.isEmpty()
                        ? "§6[AKI] §cLệnh không hợp lệ! Dùng: /" + label + " reload"
                        : invalidMsg.replace("%label%", label);
                sendRaw(sender, display);
                yield true;
            }
        };
    }

    /**
     * Xử lý lệnh reload - load lại toàn bộ config từ file.
     */
    private boolean handleReload(CommandSender sender) {
        // Kiểm tra quyền
        if (!sender.hasPermission("advancedkeepinventory.admin")) {
            String noPermMsg = plugin.getConfigManager().getMessage("command.no-permission");
            if (!noPermMsg.isEmpty() && sender instanceof Player player) {
                plugin.getMessageManager().send(player, noPermMsg);
            } else {
                sender.sendMessage("§cBạn không có quyền sử dụng lệnh này!");
            }
            return true;
        }

        // Reload config
        plugin.getConfigManager().loadConfig();
        // Xóa cooldown cache khi reload
        plugin.getAntiAbuseManager().clearCooldowns();

        // Gửi thông báo thành công
        String successMsg = plugin.getConfigManager().getMessage("command.reload-success");
        if (!successMsg.isEmpty() && sender instanceof Player player) {
            plugin.getMessageManager().send(player, successMsg);
        } else {
            sender.sendMessage("§a[AKI] Config đã được reload thành công!");
        }

        // Log tên người dùng vào console (dùng key có placeholder %player%)
        String logMsg = plugin.getConfigManager().getMessage("plugin.reload-by");
        plugin.getLogger().info(logMsg.isEmpty()
                ? "Config đã được reload bởi " + sender.getName()
                : logMsg.replace("%player%", sender.getName()));

        return true;
    }

    /**
     * Gửi message thô (legacy §-format hoặc MiniMessage) cho CommandSender.
     * Console dùng sendMessage() bình thường; Player dùng MessageManager để parse MiniMessage.
     */
    private void sendRaw(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            plugin.getMessageManager().send(player, message);
        } else {
            sender.sendMessage(message);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        // Tab-complete cho argument đầu tiên
        if (args.length == 1 && sender.hasPermission("advancedkeepinventory.admin")) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }
}
