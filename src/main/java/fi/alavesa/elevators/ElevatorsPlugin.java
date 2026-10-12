package fi.alavesa.elevators;

import fi.alavesa.elevators.button.ButtonManager;
import fi.alavesa.elevators.command.ElevatorCommand;
import fi.alavesa.elevators.elevator.ElevatorManager;
import fi.alavesa.elevators.model.PackExporter;
import fi.alavesa.elevators.type.TypeRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Elevators - configurable elevators with Blockbench cabins, interaction-entity buttons and a
 * .bbmodel -> resource pack exporter. See README.md.
 */
public final class ElevatorsPlugin extends JavaPlugin {

    private Settings settings;
    private TypeRegistry types;
    private ElevatorManager elevators;
    private ButtonManager buttons;
    private PackExporter exporter;
    private NamespacedKey keyElevator, keyButton, keyRole;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "types.yml").exists()) saveResource("types.yml", false);
        new File(getDataFolder(), "models").mkdirs();
        keyElevator = new NamespacedKey(this, "elevator");
        keyButton = new NamespacedKey(this, "button");
        keyRole = new NamespacedKey(this, "role");
        settings = new Settings(getConfig());
        for (String p : settings.problems) getLogger().warning("config.yml: " + p);
        types = new TypeRegistry(new File(getDataFolder(), "types.yml"), getLogger());
        types.load();
        buttons = new ButtonManager(this);
        elevators = new ElevatorManager(this);
        exporter = new PackExporter(this);

        ElevatorCommand cmd = new ElevatorCommand(this);
        getCommand("elevator").setExecutor(cmd);
        getCommand("elevator").setTabCompleter(cmd);
        Bukkit.getPluginManager().registerEvents(new ElevatorListener(this), this);

        // worlds and other plugins are ready one tick later
        Bukkit.getScheduler().runTask(this, () -> {
            elevators.load();
            if (settings.exportOnStart) exporter.export(null);
        });
        Bukkit.getScheduler().runTaskTimer(this, () -> { buttons.tick(); elevators.tick(); }, 1L, 1L);
        getLogger().info("Elevators " + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (elevators != null) { elevators.save(); elevators.shutdownAll(); }
        getLogger().info("Elevators disabled - cabins parked, riders put down.");
    }

    /** Reload config + types + elevators (cabins are parked at the nearest floor first). */
    public void reloadAll() {
        reloadConfig();
        settings = new Settings(getConfig());
        for (String p : settings.problems) getLogger().warning("config.yml: " + p);
        elevators.save();
        types.load();
        elevators.load();
    }

    public void msg(CommandSender to, String text) {
        to.sendMessage(settings.prefix.append(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(text)));
    }

    public Settings settings() { return settings; }
    public TypeRegistry types() { return types; }
    public ElevatorManager elevators() { return elevators; }
    public ButtonManager buttons() { return buttons; }
    public PackExporter exporter() { return exporter; }
    public NamespacedKey keyElevator() { return keyElevator; }
    public NamespacedKey keyButton() { return keyButton; }
    public NamespacedKey keyRole() { return keyRole; }
}
