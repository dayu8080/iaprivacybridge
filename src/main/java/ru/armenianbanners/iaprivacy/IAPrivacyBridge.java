package ru.armenianbanners.iaprivacy;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import dev.lone.itemsadder.api.Events.CustomBlockBreakEvent;
import dev.lone.itemsadder.api.Events.CustomBlockPlaceEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public class IAPrivacyBridge extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration dataConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        dataFile = new File(getDataFolder(), "regions.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Не удалось создать regions.yml", e);
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        if (Bukkit.getPluginManager().getPlugin("WorldGuard") == null) {
            getLogger().severe("WorldGuard не найден! Плагин отключается.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) {
            getLogger().severe("ItemsAdder не найден! Плагин отключается.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("IAPrivacyBridge включен. Отслеживаемые блоки: " + getConfig().getStringList("allowed-blocks"));
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void saveData() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Не удалось сохранить regions.yml", e);
        }
    }

    private String locationKey(Block block) {
        return block.getWorld().getName() + ";" + block.getX() + ";" + block.getY() + ";" + block.getZ();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockPlace(CustomBlockPlaceEvent event) {
        String namespacedId = event.getNamespacedID();
        List<String> allowed = getConfig().getStringList("allowed-blocks");

        if (!allowed.contains(namespacedId)) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (!player.hasPermission("iaprivacy.create")) {
            player.sendMessage("§cУ вас нет права на создание приват-зоны.");
            event.setCancelled(true);
            return;
        }

        int radius = getConfig().getInt("radius", 20);
        World world = block.getWorld();

        int x1 = block.getX() - radius;
        int y1 = getConfig().getBoolean("full-height", true) ? world.getMinHeight() : block.getY() - radius;
        int z1 = block.getZ() - radius;
        int x2 = block.getX() + radius;
        int y2 = getConfig().getBoolean("full-height", true) ? world.getMaxHeight() : block.getY() + radius;
        int z2 = block.getZ() + radius;

        RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));

        if (regionManager == null) {
            player.sendMessage("§cОшибка: WorldGuard не настроен для этого мира.");
            event.setCancelled(true);
            return;
        }

        BlockVector3 min = BlockVector3.at(x1, y1, z1);
        BlockVector3 max = BlockVector3.at(x2, y2, z2);
        String regionName = "iaprivacy_" + UUID.randomUUID().toString().substring(0, 8);
        ProtectedCuboidRegion candidate = new ProtectedCuboidRegion(regionName, min, max);

        boolean overlaps = regionManager.getApplicableRegions(candidate).size() > 0;
        if (overlaps) {
            player.sendMessage("§cЭта территория пересекается с другим приватом. Отступите дальше.");
            event.setCancelled(true);
            return;
        }

        DefaultDomain owners = new DefaultDomain();
        owners.addPlayer(player.getUniqueId());
        candidate.setOwners(owners);
        candidate.setPriority(0);

        applyFlags(candidate, player.getName());

        regionManager.addRegion(candidate);
        saveRegionManager(regionManager);

        dataConfig.set(locationKey(block), regionName);
        saveData();

        player.sendMessage("§aПриват-зона создана! Радиус: " + radius + " блоков.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        String namespacedId = event.getNamespacedID();
        List<String> allowed = getConfig().getStringList("allowed-blocks");

        if (!allowed.contains(namespacedId)) {
            return;
        }

        Block block = event.getBlock();
        Player player = event.getPlayer();
        String key = locationKey(block);
        String regionName = dataConfig.getString(key);

        if (regionName == null) {
            return;
        }

        World world = block.getWorld();
        RegionManager regionManager = WorldGuard.getInstance().getPlatform()
                .getRegionContainer().get(BukkitAdapter.adapt(world));

        if (regionManager != null) {
            ProtectedRegion region = regionManager.getRegion(regionName);
            if (region != null) {
                regionManager.removeRegion(regionName);
                saveRegionManager(regionManager);
                player.sendMessage("§eПриват-зона удалена.");
            }
        }

        dataConfig.set(key, null);
        saveData();
    }

    private void applyFlags(ProtectedRegion region, String ownerName) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        List<String> flagLines = getConfig().getStringList("flags");

        for (String line : flagLines) {
            String trimmed = line.trim();

            com.sk89q.worldguard.protection.flags.RegionGroup group = null;
            if (trimmed.startsWith("-g ")) {
                String[] gParts = trimmed.substring(3).trim().split("\\s+", 2);
                if (gParts.length != 2) continue;
                group = parseRegionGroup(gParts[0]);
                trimmed = gParts[1];
            }

            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length != 2) continue;

            String flagName = parts[0].toLowerCase();
            String value = parts[1].replace("{owner}", ownerName);

            if (flagName.equals("greeting")) {
                region.setFlag(com.sk89q.worldguard.protection.flags.Flags.GREET_MESSAGE, value);
                if (group != null) {
                    region.setFlag(com.sk89q.worldguard.protection.flags.Flags.GREET_MESSAGE.getRegionGroupFlag(), group);
                }
                continue;
            }
            if (flagName.equals("farewell")) {
                region.setFlag(com.sk89q.worldguard.protection.flags.Flags.FAREWELL_MESSAGE, value);
                if (group != null) {
                    region.setFlag(com.sk89q.worldguard.protection.flags.Flags.FAREWELL_MESSAGE.getRegionGroupFlag(), group);
                }
                continue;
            }

            Flag<?> flag = registry.get(flagName);
            if (flag == null) {
                getLogger().warning("Неизвестный флаг WorldGuard: '" + flagName + "' - пропущен.");
                continue;
            }

            if (flag instanceof StateFlag) {
                StateFlag.State state = value.equalsIgnoreCase("allow")
                        ? StateFlag.State.ALLOW
                        : value.equalsIgnoreCase("deny") ? StateFlag.State.DENY : null;
                if (state != null) {
                    region.setFlag((StateFlag) flag, state);
                    if (group != null) {
                        setRegionGroupSafely(region, flag, group);
                    }
                } else {
                    getLogger().warning("Некорректное значение для флага '" + flagName + "': " + value);
                }
            } else {
                getLogger().warning("Флаг '" + flagName + "' не является StateFlag и не поддержан явно - пропущен.");
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void setRegionGroupSafely(ProtectedRegion region, Flag<?> flag,
                                       com.sk89q.worldguard.protection.flags.RegionGroup group) {
        com.sk89q.worldguard.protection.flags.RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
        if (groupFlag != null) {
            region.setFlag((Flag) groupFlag, group);
        }
    }

    private com.sk89q.worldguard.protection.flags.RegionGroup parseRegionGroup(String name) {
        switch (name.toLowerCase()) {
            case "members":
                return com.sk89q.worldguard.protection.flags.RegionGroup.MEMBERS;
            case "non-members":
                return com.sk89q.worldguard.protection.flags.RegionGroup.NON_MEMBERS;
            case "owners":
                return com.sk89q.worldguard.protection.flags.RegionGroup.OWNERS;
            case "non-owners":
                return com.sk89q.worldguard.protection.flags.RegionGroup.NON_OWNERS;
            case "all":
                return com.sk89q.worldguard.protection.flags.RegionGroup.ALL;
            default:
                getLogger().warning("Неизвестная группа флага: '" + name + "', используется ALL.");
                return com.sk89q.worldguard.protection.flags.RegionGroup.ALL;
        }
    }

    private void saveRegionManager(RegionManager regionManager) {
        try {
            regionManager.saveChanges();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Не удалось сохранить изменения региона WorldGuard", e);
        }
    }
}
