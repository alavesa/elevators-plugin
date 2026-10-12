package fi.alavesa.elevators;

import fi.alavesa.elevators.model.ParseOptions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validated values from config.yml. {@link #problems} lists what was wrong and what was used instead. */
public final class Settings {

    public final Material baseItem;
    public final String namespace, packDescription;
    public final int minFormat, maxFormat;
    public final ParseOptions parse;
    public final boolean exportOnStart;
    public final double seatOffset;
    public final int teleportDuration, safeExitSearch;
    public final boolean obstructionCheck, lockRiders;
    public final int debounceTicks;
    public final double defaultButtonSize;
    public final float labelScale;
    public final Component prefix;
    public final List<String> problems = new ArrayList<>();

    public Settings(FileConfiguration c) {
        Material m = Material.matchMaterial(c.getString("pack.base-item", "turtle_scute"));
        if (m == null || !m.isItem()) { problems.add("pack.base-item '" + c.getString("pack.base-item") + "' is not an item - using turtle_scute"); m = Material.TURTLE_SCUTE; }
        baseItem = m;
        String ns = c.getString("pack.namespace", "elevators").toLowerCase(Locale.ROOT);
        if (!ns.matches("[a-z0-9_.-]+")) { problems.add("pack.namespace '" + ns + "' is not a valid namespace - using 'elevators'"); ns = "elevators"; }
        namespace = ns;
        packDescription = c.getString("pack.description", "Elevators (generated)");
        int min = c.getInt("pack.min-format", 46), max = c.getInt("pack.max-format", 999);
        if (min < 1 || max < min) { problems.add("pack.min-format/max-format invalid - using 46..999"); min = 46; max = 999; }
        minFormat = min; maxFormat = max;
        parse = ParseOptions.of(c.getString("pack.mesh-policy", "fail"), c.getString("pack.missing-texture", "fail"));
        exportOnStart = c.getBoolean("pack.export-on-start", true);
        seatOffset = clamp(c, "ride.seat-offset", 0.0, -2, 2);
        teleportDuration = (int) clamp(c, "ride.teleport-duration", 1, 0, 10);
        safeExitSearch = (int) clamp(c, "ride.safe-exit-search", 3, 0, 10);
        obstructionCheck = c.getBoolean("ride.obstruction-check", true);
        lockRiders = c.getBoolean("ride.lock-riders-while-moving", true);
        debounceTicks = (int) clamp(c, "buttons.debounce-ticks", 3, 0, 40);
        defaultButtonSize = clamp(c, "buttons.default-size", 0.4, 0.1, 4);
        labelScale = (float) clamp(c, "buttons.label-scale", 0.5, 0.1, 4);
        prefix = LegacyComponentSerializer.legacyAmpersand().deserialize(c.getString("messages.prefix", "&8[&6Elevator&8] &r"));
    }

    private double clamp(FileConfiguration c, String key, double def, double lo, double hi) {
        double v = c.getDouble(key, def);
        if (v < lo || v > hi) { problems.add(key + " = " + v + " is outside " + lo + ".." + hi + " - using " + def); return def; }
        return v;
    }
}
