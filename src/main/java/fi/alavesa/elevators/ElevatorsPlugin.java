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
    private fi.alavesa.elevators.model.ModelBridge bridge;
    private boolean bmActive;
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
        // BetterModel installed: mirror our cabin models into its folder (it builds the one pack) and let it render + animate.
        if (fi.alavesa.elevators.model.ModelBridge.installed()) {
            bridge = new fi.alavesa.elevators.model.ModelBridge(this, "elevator");
            if (bridge.active()) {
                try {
                    int n = bridge.sync(new File(getDataFolder(), "models"));
                    bmActive = true;
                    getLogger().info("BetterModel detected: cabins are rendered by BetterModel (" + n + " model file(s) synced to plugins/BetterModel/models, prefix elevator_). The built-in pack exporter is not needed.");
                } catch (Throwable t) { getLogger().warning("BetterModel sync failed, using the built-in exporter: " + t); }
            }
        }
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
            if (settings.exportOnStart && !bmActive) exporter.export(null);   // BetterModel builds the pack itself
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
    /** The BetterModel bridge when BetterModel renders the cabins, else null. */
    public fi.alavesa.elevators.model.ModelBridge bridge() { return bmActive ? bridge : null; }
    public NamespacedKey keyElevator() { return keyElevator; }
    public NamespacedKey keyButton() { return keyButton; }
    public NamespacedKey keyRole() { return keyRole; }
}
