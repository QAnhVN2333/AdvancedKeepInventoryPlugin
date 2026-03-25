package com.lyrinth.listener;

import com.lyrinth.AdvancedKeepInventory;
import com.lyrinth.manager.*;
import com.lyrinth.model.*;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Logger;

/**
 * DeathListener - Xử lý sự kiện người chơi chết.
 * Đây là "bộ não" chính, quyết định giữ/drop item, trừ tiền, trừ EXP.
 *
 * EventPriority.HIGH: để các plugin khác có cơ hội xử lý trước (nếu cần).
 */
public class DeathListener implements Listener {

    private final AdvancedKeepInventory plugin;
    private final Logger logger;

    public DeathListener(AdvancedKeepInventory plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        String worldName = victim.getWorld().getName();

        // Bước 1: Lấy WorldRule từ cache (không đọc file config!)
        ConfigManager configManager = plugin.getConfigManager();
        WorldRule rule = configManager.getWorldRule(worldName);

        // Nếu world không có trong config hoặc bị tắt -> bỏ qua, để Vanilla xử lý
        if (rule == null || !rule.enabled()) return;

        // Khi admin bật keepInventory bằng lệnh Vanilla VÀ config bật respect -> plugin nhường lại cho Vanilla
        if (rule.respectVanillaGamerule()
                && victim.getWorld().getGameRuleValue(GameRule.KEEP_INVENTORY) == Boolean.TRUE) {
            if (configManager.isDebug()) {
                logger.info("[Debug] World '" + worldName + "' có Vanilla keepInventory=true"
                        + " và respect-vanilla-gamerule=true. Plugin bỏ qua, để Vanilla xử lý.");
            }
            return;
        }

        // Bước 2: Xác định nguyên nhân chết
        DeathCause deathCause = determineDeathCause(victim);

        // Bước 3: Kiểm tra bypass permission (VIP/Staff không bao giờ rớt đồ)
        boolean keepBypass = victim.hasPermission("advancedkeepinventory.death.keep.bypass");
        boolean moneyBypass = victim.hasPermission("advancedkeepinventory.death.money.bypass");

        if (configManager.isDebug()) {
            logger.info("[Debug] " + victim.getName() + " chết tại " + worldName
                    + " | Cause: " + deathCause + " | KeepBypass: " + keepBypass);
        }

        // Bước 4: Xử lý vật phẩm (Item Management)
        handleItems(event, victim, rule, deathCause, keepBypass);

        // Bước 5: [ANTI-ABUSE] Rapid Death Protection — gọi MỘT LẦN DUY NHẤT per death event.
        // Tập trung tại đây để EXP và Economy dùng chung kết quả.
        // Nếu gọi riêng trong từng handler: EXP gọi -> ghi timestamp T=0,
        // Economy gọi lại -> elapsed=0ms < cooldown -> bị chặn ngay cùng lần chết!
        AntiAbuseManager antiAbuse = plugin.getAntiAbuseManager();
        boolean chargeAllowed = antiAbuse.canChargePlayer(victim.getUniqueId());

        if (!chargeAllowed && configManager.isDebug()) {
            logger.info("[Debug] Rapid Death Protection kích hoạt: " + victim.getName()
                    + " chết liên tục trong cooldown window. Bỏ qua trừ tiền và EXP.");
        }

        // Bước 6: Xử lý kinh nghiệm (EXP) — truyền chargeAllowed vào
        handleExp(event, victim, rule, deathCause, chargeAllowed);

        // Bước 7: Xử lý kinh tế (Economy) - chạy ASYNC — truyền chargeAllowed vào
        if (!moneyBypass) {
            handleEconomy(victim, rule, deathCause, chargeAllowed);
        }
    }

    /**
     * Xác định nguyên nhân cái chết: PLAYER (PvP), MOB (PvE), hoặc NATURAL.
     * Null-safe: player.getKiller() có thể null (chết do Skeleton bắn, TNT, v.v.)
     */
    private DeathCause determineDeathCause(Player victim) {
        Player killer = victim.getKiller();

        // Nếu có killer là Player (và không phải tự giết mình) -> PvP
        if (killer != null && killer != victim) {
            return DeathCause.PLAYER;
        }

        if (victim.getLastDamageCause() != null) {
            String causeName = victim.getLastDamageCause().getCause().name();
            // Keep name-based matching to avoid hard enum references not present on older APIs.
            if ("ENTITY_ATTACK".equals(causeName)
                    || "ENTITY_SWEEP_ATTACK".equals(causeName)
                    || "ENTITY_EXPLOSION".equals(causeName)
                    || "PROJECTILE".equals(causeName)
                    || "MAGIC".equals(causeName)
                    || "THORNS".equals(causeName)
                    || "SONIC_BOOM".equals(causeName)) {
                return DeathCause.MOB;
            }
            return DeathCause.NATURAL;
        }

        return DeathCause.NATURAL;
    }

    // ==================== ITEM HANDLING ====================

    /**
     * Xử lý logic giữ/drop vật phẩm khi chết.
     * Ưu tiên: Bypass > BlanketKeep > ItemRule chi tiết.
     *
     * Sử dụng removeIf() thay vì Iterator thủ công để tránh ConcurrentModificationException.
     */
    private void handleItems(PlayerDeathEvent event, Player victim, WorldRule rule,
                             DeathCause deathCause, boolean keepBypass) {

        BlanketKeepRule blanketKeep = rule.blanketKeep();
        ItemRule itemRule = rule.itemRule();

        // Trường hợp 1: Bypass permission -> giữ hết
        if (keepBypass) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            return;
        }

        // Trường hợp 2: Blanket keep ALL (giữ toàn bộ theo nguyên nhân chết)
        if (blanketKeep.shouldKeepAll(deathCause)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            return;
        }

        // Trường hợp 3: Xử lý chi tiết từng item
        // Tắt keepInventory mặc định để ta kiểm soát hoàn toàn
        event.setKeepInventory(false);

        // Lấy danh sách drops hiện tại (bao gồm cả armor và main hand)
        List<ItemStack> drops = event.getDrops();

        // Danh sách items sẽ được giữ lại (trả về inventory sau khi respawn)
        List<ItemStack> keptItems = new ArrayList<>();

        // Xử lý giữ giáp đang mặc (keep-equipped-armor)
        ItemStack[] armorContents = victim.getInventory().getArmorContents();
        if (blanketKeep.keepEquippedArmor() && armorContents != null) {
            for (ItemStack armor : armorContents) {
                if (armor != null && !armor.getType().isAir()) {
                    keptItems.add(armor.clone());
                    // Xóa armor khỏi drops list
                    removeMatchingItem(drops, armor);
                }
            }
        }

        // Xử lý giữ vật phẩm tay chính (keep-main-hand)
        ItemStack mainHand = victim.getInventory().getItemInMainHand();
        if (blanketKeep.keepMainHand() && !mainHand.getType().isAir()) {
            keptItems.add(mainHand.clone());
            removeMatchingItem(drops, mainHand);
        }

        // Xử lý chi tiết từng item còn lại dựa trên ItemRule
        // Dùng removeIf() để an toàn với ConcurrentModification
        drops.removeIf(item -> {
            if (item == null || item.getType().isAir()) return true; // Xóa item null/air

            // Nếu item KHÔNG nên bị drop (nghĩa là nên giữ) -> thêm vào keptItems
            if (!itemRule.shouldDrop(item)) {
                keptItems.add(item.clone());
                return true; // Xóa khỏi drops (vì ta sẽ trả lại)
            }

            // Item nên bị drop -> giữ nguyên trong drops list
            return false;
        });

        // Lưu danh sách items cần trả lại khi respawn (sử dụng metadata tạm)
        if (!keptItems.isEmpty()) {
            plugin.storeKeptItems(victim.getUniqueId(), keptItems);
        }
    }

    /**
     * Xóa 1 item cụ thể khỏi drops list (dùng cho armor/main hand).
     * So sánh bằng isSimilar() để match chính xác (bao gồm meta).
     *
     * [BUG-03 FIX] Bỏ check amount vì Bukkit có thể tách stack,
     * khiến amount trong drops khác với amount gốc -> item không bị xóa
     * -> dẫn đến dupe item khi vòng removeIf() phía dưới thêm lại vào keptItems.
     */
    private void removeMatchingItem(List<ItemStack> drops, ItemStack target) {
        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            if (drop != null && drop.isSimilar(target)) {
                it.remove();
                return; // Chỉ xóa 1 item match đầu tiên, tránh xóa quá nhiều
            }
        }
    }

    // ==================== EXP HANDLING ====================

    /**
     * Xử lý trừ kinh nghiệm khi chết.
     * [DESIGN-01] Kiểm tra applyOn để chỉ trừ EXP cho nguyên nhân chết phù hợp.
     * [ANTI-ABUSE] chargeAllowed=false -> bỏ qua trừ EXP (rapid death protection).
     */
    private void handleExp(PlayerDeathEvent event, Player victim, WorldRule rule,
                           DeathCause deathCause, boolean chargeAllowed) {
        ExpRule expRule = rule.expRule();

        // Kiểm tra tính năng EXP có bật không
        if (!expRule.activate()) return;

        // [DESIGN-01] Kiểm tra nguyên nhân chết có nằm trong applyOn không
        // VD: Nếu config chỉ có [PLAYER, MOB] mà chết do NATURAL -> bỏ qua
        if (!expRule.shouldApply(deathCause)) {
            if (plugin.getConfigManager().isDebug()) {
                logger.info("[Debug] EXP loss không áp dụng cho " + deathCause
                        + " (applyOn: " + expRule.applyOn() + ")");
            }
            return;
        }

        double percentLoss = expRule.percentLoss();
        if (percentLoss <= 0) return;

        // [ANTI-ABUSE] Rapid Death Protection: nếu chết liên tục -> không trừ EXP
        if (!chargeAllowed) {
            if (plugin.getConfigManager().isDebug()) {
                logger.info("[Debug] Rapid Death Protection: bỏ qua trừ EXP cho "
                        + victim.getName() + " (chết liên tục).");
            }
            // Vẫn chặn drop EXP orb ra ngoài, nhưng giữ nguyên level/exp hiện tại
            event.setDroppedExp(0);
            event.setNewLevel(victim.getLevel());
            event.setNewExp(0);
            return;
        }

        int currentLevel = victim.getLevel();

        // Tính số level bị mất
        int levelsToLose = (int) Math.floor(currentLevel * percentLoss);

        // Thiết lập: không drop EXP mặc định của Vanilla
        event.setDroppedExp(0);
        event.setNewLevel(Math.max(0, currentLevel - levelsToLose));
        event.setNewExp(0);

        // Gửi thông báo mất EXP với NotificationConfig tương ứng
        String msg = plugin.getConfigManager().getMessage("death.exp-loss");
        if (!msg.isEmpty()) {
            String percentStr = String.valueOf((int) (percentLoss * 100));
            plugin.getMessageManager().send(
                    victim,
                    msg,
                    Map.of("%percent%", percentStr),
                    plugin.getConfigManager().getNotificationConfig("death.exp-loss")
            );
        }

        if (plugin.getConfigManager().isDebug()) {
            logger.info("[Debug] " + victim.getName() + " mất " + levelsToLose
                    + " level (" + (percentLoss * 100) + "%)");
        }
    }

    // ==================== ECONOMY HANDLING ====================

    /**
     * Xử lý trừ tiền khi chết.
     * TẤT CẢ lệnh gọi Vault/PlayerPoints được chạy trên ASYNC thread
     * để tránh Lag Spike khi database phản hồi chậm.
     *
     * [DESIGN-04] Chỉ capture UUID/String vào async lambda, KHÔNG capture Player object.
     * [ANTI-ABUSE] chargeAllowed=false -> bỏ qua trừ tiền (rapid death protection).
     */
    private void handleEconomy(Player victim, WorldRule rule, DeathCause deathCause,
                               boolean chargeAllowed) {
        EconomyRule ecoRule = rule.economyRule();
        EconomyManager ecoManager = plugin.getEconomyManager();

        // Kiểm tra tính năng tiền có bật không
        if (!ecoRule.activate()) return;

        String type = ecoRule.type();

        // Kiểm tra economy provider có sẵn không
        if (!ecoManager.isAvailable(type)) {
            if (plugin.getConfigManager().isDebug()) {
                logger.info("[Debug] Economy type '" + type + "' không khả dụng. Bỏ qua trừ tiền.");
            }
            return;
        }

        // [ANTI-ABUSE] Rapid Death Protection: nếu chết liên tục -> không trừ tiền
        if (!chargeAllowed) {
            if (plugin.getConfigManager().isDebug()) {
                logger.info("[Debug] Rapid Death Protection: bỏ qua trừ tiền cho "
                        + victim.getName() + " (chết liên tục).");
            }
            return;
        }

        // [DESIGN-04] Chỉ capture primitive/String/UUID — KHÔNG giữ Player object trong async
        // Lý do: Player object giữ strong reference đến CraftPlayer -> GC không thu hồi được
        // nếu player disconnect trước khi async task hoàn thành.
        UUID victimUUID = victim.getUniqueId();
        String victimName = victim.getName();
        Player killer = victim.getKiller();
        UUID killerUUID = (killer != null && deathCause == DeathCause.PLAYER) ? killer.getUniqueId() : null;
        String killerName = (killer != null) ? killer.getName() : null;
        // [DESIGN-04] Capture IP trước khi vào async (vì getAddress() không an toàn khi player offline)
        String victimIp = (victim.getAddress() != null) ? victim.getAddress().getAddress().getHostAddress() : null;
        String killerIp = (killer != null && killer.getAddress() != null) ? killer.getAddress().getAddress().getHostAddress() : null;
        boolean giveToKiller = ecoRule.giveToKiller() && killerUUID != null;

        // Chạy ASYNC để tránh lag main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                // [DESIGN-05] Bỏ playerName thừa trong getBalance()
                double balance = ecoManager.getBalance(victimUUID, type);
                if (balance <= 0) return;

                // Tính số tiền bị trừ: percent * balance, tối đa max-loss
                double loss = Math.min(balance * ecoRule.percent(), ecoRule.maxLoss());
                if (loss <= 0) return;

                // [BUG-01 FIX] Kiểm tra anti-abuse nếu PvP và có killer
                // [BUG-02 FIX] Dùng biến cục bộ để quyết định có chuyển tiền cho killer hay không
                // Trước đây: return luôn cả hàm → nạn nhân không bị trừ tiền = chết free!
                // Bây giờ: chỉ tắt flag giveToKiller, vẫn tiếp tục trừ tiền nạn nhân
                boolean actuallyGiveToKiller = giveToKiller;
                if (giveToKiller) {
                    AntiAbuseManager antiAbuse = plugin.getAntiAbuseManager();

                    // [DESIGN-04] Dùng IP đã capture sẵn thay vì truy cập Player object
                    if (!antiAbuse.canReceiveMoney(killerUUID, victimUUID, killerIp, victimIp, balance)) {
                        if (plugin.getConfigManager().isDebug()) {
                            logger.info("[Debug] Anti-abuse blocked money transfer: "
                                    + victimName + " -> " + killerName
                                    + ". Still charged victim, but killer won't receive money.");
                        }
                        // Chỉ tắt chuyển tiền cho killer, KHÔNG return
                        actuallyGiveToKiller = false;
                    }
                }

                // Trừ tiền nạn nhân (luôn thực hiện bất kể anti-abuse). Tuy nhiên nếu bal dưới ngưỡng
                boolean withdrawn = ecoManager.withdraw(victimUUID, loss, type);
                if (!withdrawn) return;

                // Nếu PvP và giveToKiller được phép -> chuyển tiền cho sát thủ
                if (actuallyGiveToKiller) {
                    ecoManager.deposit(killerUUID, loss, type);
                    sendKillerMessage(killerUUID, victimName, loss);
                }

                // Gửi thông báo cho nạn nhân
                if (deathCause == DeathCause.PLAYER) {
                    sendVictimMessage(victimUUID, killerName, loss);
                } else {
                    // [BUG FIX] Truyền loss vào để thông báo số tiền bị mất khi chết tự nhiên
                    sendNaturalDeathMessage(victimUUID, loss);
                }

                if (plugin.getConfigManager().isDebug()) {
                    logger.info("[Debug] " + victimName + " mất " + String.format("%.2f", loss)
                            + " " + type + " | Killer: " + (killerName != null ? killerName : "N/A"));
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Gửi thông báo cho nạn nhân (PvP death).
     * [DESIGN-04] Lookup Player từ UUID trên main thread — an toàn khi player đã disconnect.
     */
    private void sendVictimMessage(UUID victimUUID, String killerName, double loss) {
        // Key đầy đủ: "death.victim-pvp" (khớp với cấu trúc messages.yml)
        String msg = plugin.getConfigManager().getMessage("death.victim-pvp");
        NotificationConfig notifConfig = plugin.getConfigManager().getNotificationConfig("death.victim-pvp");
        if (msg.isEmpty()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                Player onlineVictim = Bukkit.getPlayer(victimUUID);
                if (onlineVictim != null) {
                    plugin.getMessageManager().send(onlineVictim, msg, Map.of(
                            "%killer%", killerName != null ? killerName : "Unknown",
                            "%money%", String.format("%.2f", loss)
                    ), notifConfig);
                }
            }
        }.runTask(plugin);
    }

    /**
     * Gửi thông báo cho sát thủ (nhận tiền).
     */
    private void sendKillerMessage(UUID killerUUID, String victimName, double loss) {
        // Key đầy đủ: "death.killer-pvp"
        String msg = plugin.getConfigManager().getMessage("death.killer-pvp");
        NotificationConfig notifConfig = plugin.getConfigManager().getNotificationConfig("death.killer-pvp");
        if (msg.isEmpty()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                Player onlineKiller = Bukkit.getPlayer(killerUUID);
                if (onlineKiller != null) {
                    plugin.getMessageManager().send(onlineKiller, msg, Map.of(
                            "%victim%", victimName,
                            "%money%", String.format("%.2f", loss)
                    ), notifConfig);
                }
            }
        }.runTask(plugin);
    }

    /**
     * Gửi thông báo chết tự nhiên.
     * [BUG FIX] Thêm parameter loss + placeholder %money% để hiển thị số tiền mất.
     */
    private void sendNaturalDeathMessage(UUID victimUUID, double loss) {
        // Key đầy đủ: "death.natural-death"
        String msg = plugin.getConfigManager().getMessage("death.natural-death");
        NotificationConfig notifConfig = plugin.getConfigManager().getNotificationConfig("death.natural-death");
        if (msg.isEmpty()) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                Player onlineVictim = Bukkit.getPlayer(victimUUID);
                if (onlineVictim != null) {
                    // [BUG FIX] Truyền %money% để người chơi biết mất bao nhiêu tiền
                    plugin.getMessageManager().send(onlineVictim, msg, Map.of(
                            "%money%", String.format("%.2f", loss)
                    ), notifConfig);
                }
            }
        }.runTask(plugin);
    }
}
