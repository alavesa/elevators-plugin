package fi.alavesa.elevators.button;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A persisted button: where it sits relative to the elevator origin, what it does, how it looks.
 * {@code space} is "cabin" (the button travels with the cabin; offset is relative to the cabin floor
 * centre) or a floor name (a landing button fixed at that floor's level).
 */
public final class ButtonDef {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,24}");
    public static final String CABIN = "cabin";

    public final String id;
    public ButtonAction action = ButtonAction.FLOOR;
    public String floor = "";          // target floor for FLOOR / CALL
    public double dx, dy, dz;          // offset from the elevator origin (world axes), blocks
    public String space = CABIN;
    public String label = "";          // text shown above the button ("" = none)
    public String model = "";          // bbmodel name shown at the button ("" = no visual)
    public double width = 0.4, height = 0.4;   // interaction hit box
    public int cooldown = -1;          // ticks; -1 = the elevator type's button-cooldown
    public String permission = "";     // extra permission just for this button

    public ButtonDef(String id) { this.id = id; }

    public boolean inCabin() { return CABIN.equalsIgnoreCase(space); }

    /** A landing button copied from floor `ref` to floor `to`: same offsets, label and look; a CALL to `ref` becomes a
     *  CALL to `to`; the id and label get the new floor's name where they contained the old one. */
    public static ButtonDef cloneForFloor(ButtonDef src, String ref, String to) {
        String id = src.id.contains(ref.toLowerCase(java.util.Locale.ROOT)) ? src.id.replace(ref.toLowerCase(java.util.Locale.ROOT), to.toLowerCase(java.util.Locale.ROOT)) : src.id + "_" + to.toLowerCase(java.util.Locale.ROOT);
        ButtonDef b = new ButtonDef(id.replaceAll("[^a-z0-9_-]", "_"));
        b.action = src.action;
        b.floor = src.action == ButtonAction.CALL && src.floor.equalsIgnoreCase(ref) ? to : src.floor;
        b.dx = src.dx; b.dy = src.dy; b.dz = src.dz;
        b.space = to;
        b.label = src.label.contains(ref) ? src.label.replace(ref, to) : src.label;
        b.model = src.model; b.width = src.width; b.height = src.height; b.cooldown = src.cooldown; b.permission = src.permission;
        return b;
    }

    public int cooldownOr(int typeDefault) { return cooldown < 0 ? typeDefault : cooldown; }

    public List<String> validate(Set<String> floorNames) {
        List<String> e = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) e.add("button id must be 1-24 chars of a-z 0-9 _ - (got '" + id + "')");
        if (action == null) e.add("unknown action");
        else if (action.needsFloor()) {
            if (floor == null || floor.isBlank()) e.add("action " + action.name().toLowerCase(Locale.ROOT) + " needs a floor");
            else if (!containsIgnoreCase(floorNames, floor)) e.add("floor '" + floor + "' does not exist on this elevator");
        }
        if (!inCabin() && !containsIgnoreCase(floorNames, space)) e.add("space must be 'cabin' or an existing floor name (got '" + space + "')");
        if (Math.abs(dx) > 16 || Math.abs(dy) > 16 || Math.abs(dz) > 16) e.add("offset must stay within 16 blocks of the elevator origin");
        if (!(width > 0) || width > 4 || !(height > 0) || height > 4) e.add("size must be 0..4 blocks");
        if (cooldown < -1 || cooldown > 6000) e.add("cooldown must be -1 (type default) or 0..6000 ticks");
        return e;
    }

    private static boolean containsIgnoreCase(Set<String> set, String v) {
        for (String s : set) if (s.equalsIgnoreCase(v)) return true;
        return false;
    }

    public static final List<String> KEYS = List.of("action", "floor", "label", "model", "space", "size", "cooldown", "permission", "offset");

    /** Apply one key/value. Returns an error or null. */
    public String set(String key, String value) {
        try {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "action" -> { ButtonAction a = ButtonAction.parse(value); if (a == null) return "action must be floor|call|open|close|stop"; action = a; int c = value.indexOf(':'); if (c > 0) floor = value.substring(c + 1); }
                case "floor" -> floor = value;
                case "label" -> label = value.equalsIgnoreCase("none") || value.equals("-") ? "" : value;
                case "model" -> model = value.equalsIgnoreCase("none") || value.equals("-") ? "" : value;
                case "space" -> space = value;
                case "size" -> { String[] p = value.split("[,x ]"); width = Double.parseDouble(p[0]); height = p.length > 1 ? Double.parseDouble(p[1]) : width; }
                case "cooldown" -> cooldown = value.equalsIgnoreCase("default") ? -1 : Integer.parseInt(value);
                case "permission" -> permission = value.equalsIgnoreCase("none") || value.equals("-") ? "" : value;
                case "offset" -> { String[] p = value.split("[, ]+"); dx = Double.parseDouble(p[0]); dy = Double.parseDouble(p[1]); dz = Double.parseDouble(p[2]); }
                default -> { return "unknown key '" + key + "' (keys: " + String.join(", ", KEYS) + ")"; }
            }
        } catch (RuntimeException ex) {
            return "bad value '" + value + "' for " + key;
        }
        return null;
    }

    public static ButtonDef fromSection(String id, ConfigurationSection s) {
        ButtonDef b = new ButtonDef(id);
        b.action = ButtonAction.parse(s.getString("action", "floor"));
        b.floor = s.getString("floor", "");
        b.dx = s.getDouble("offset.x"); b.dy = s.getDouble("offset.y"); b.dz = s.getDouble("offset.z");
        b.space = s.getString("space", CABIN);
        b.label = s.getString("label", "");
        b.model = s.getString("model", "");
        b.width = s.getDouble("width", 0.4); b.height = s.getDouble("height", 0.4);
        b.cooldown = s.getInt("cooldown", -1);
        b.permission = s.getString("permission", "");
        return b;
    }

    public void toSection(ConfigurationSection s) {
        s.set("action", action == null ? "floor" : action.name().toLowerCase(Locale.ROOT));
        s.set("floor", floor.isEmpty() ? null : floor);
        s.set("offset.x", dx); s.set("offset.y", dy); s.set("offset.z", dz);
        s.set("space", space);
        s.set("label", label.isEmpty() ? null : label);
        s.set("model", model.isEmpty() ? null : model);
        s.set("width", width); s.set("height", height);
        s.set("cooldown", cooldown);
        s.set("permission", permission.isEmpty() ? null : permission);
    }

    public String describe() {
        return id + ": " + action.name().toLowerCase(Locale.ROOT) + (action.needsFloor() ? " " + floor : "") + " @ " + inCabin()
            + String.format(java.util.Locale.ROOT, " %s (%.2f, %.2f, %.2f)", inCabin() ? "cabin" : "floor " + space, dx, dy, dz)
            + (label.isEmpty() ? "" : " \"" + label + "\"") + (model.isEmpty() ? "" : " model " + model) + (cooldown >= 0 ? " cd " + cooldown + "t" : "")
            + (permission.isEmpty() ? "" : " perm " + permission);
    }
}
