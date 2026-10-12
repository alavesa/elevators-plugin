package fi.alavesa.elevators.type;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A kind of elevator: cabin model(s), size, motion, doors, sounds and permission. Instances
 * ({@code Elevator}) reference a type by id. Plain data + validation, no Bukkit runtime objects.
 */
public final class ElevatorType {

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern SOUND = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");

    public final String id;
    public String model = "";            // cabin model name (models/<name>.bbmodel), doors closed
    public String modelOpen = "";        // optional: cabin model with doors open
    public double speed = 2.0;           // blocks per second
    public double acceleration = 2.0;    // blocks per second^2
    public int doorTime = 60;            // ticks the doors stay open on arrival
    public int doorCloseDelay = 10;      // ticks until the doors close after a floor is chosen
    public double cabinWidth = 3, cabinDepth = 3, cabinHeight = 3;   // blocks; riders inside this box travel
    public String soundStart = "minecraft:block.piston.extend";
    public String soundMove = "";        // repeated every soundMoveInterval ticks while moving
    public int soundMoveInterval = 20;
    public String soundArrive = "minecraft:block.note_block.bell";
    public String soundDoorsOpen = "minecraft:block.iron_door.open";
    public String soundDoorsClose = "minecraft:block.iron_door.close";
    public String music = "";            // e.g. minecraft:music_disc.cat, looped every musicInterval ticks while riding
    public int musicInterval = 200;
    public float volume = 1f;
    public String permission = "";       // extra permission required to use elevators of this type
    public int capacity = 0;             // max riders, 0 = unlimited
    public int buttonCooldown = 10;      // ticks, default for buttons of this type
    public int callCooldown = 20;        // ticks between /elevator call uses per player

    public ElevatorType(String id) { this.id = id; }

    /** Human-readable problems; empty when the type is usable. */
    public List<String> validate() {
        List<String> e = new ArrayList<>();
        if (id == null || !ID.matcher(id).matches()) e.add("id must be 1-32 chars of a-z 0-9 _ - (got '" + id + "')");
        if (model == null || model.isBlank()) e.add("model is empty (set it to a models/<name>.bbmodel name)");
        if (!(speed > 0) || speed > 50) e.add("speed must be > 0 and <= 50 blocks/s (got " + speed + ")");
        if (!(acceleration > 0) || acceleration > 100) e.add("acceleration must be > 0 and <= 100 blocks/s² (got " + acceleration + ")");
        if (doorTime < 1 || doorTime > 6000) e.add("door-time must be 1..6000 ticks (got " + doorTime + ")");
        if (doorCloseDelay < 0 || doorCloseDelay > 6000) e.add("door-close-delay must be 0..6000 ticks (got " + doorCloseDelay + ")");
        for (double[] d : new double[][] {{cabinWidth}, {cabinDepth}, {cabinHeight}}) if (!(d[0] >= 1) || d[0] > 16) e.add("cabin width/depth/height must be 1..16 blocks (got " + cabinWidth + "x" + cabinDepth + "x" + cabinHeight + ")");
        if (soundMoveInterval < 1) e.add("sound-move-interval must be >= 1 tick");
        if (musicInterval < 1) e.add("music-interval must be >= 1 tick");
        if (!(volume >= 0) || volume > 10) e.add("volume must be 0..10");
        if (capacity < 0) e.add("capacity must be >= 0 (0 = unlimited)");
        if (buttonCooldown < 0) e.add("button-cooldown must be >= 0 ticks");
        if (callCooldown < 0) e.add("call-cooldown must be >= 0 ticks");
        for (String[] s : new String[][] {{"sound-start", soundStart}, {"sound-move", soundMove}, {"sound-arrive", soundArrive}, {"sound-doors-open", soundDoorsOpen}, {"sound-doors-close", soundDoorsClose}, {"music", music}}) {
            if (s[1] != null && !s[1].isEmpty() && !SOUND.matcher(s[1]).matches()) e.add(s[0] + " '" + s[1] + "' is not a valid sound id (namespace:path, lower-case)");
        }
        return e;
    }

    /** Settable keys for /elevator type set. */
    public static final List<String> KEYS = List.of("model", "model-open", "speed", "acceleration", "door-time", "door-close-delay",
        "cabin-width", "cabin-depth", "cabin-height", "sound-start", "sound-move", "sound-move-interval", "sound-arrive",
        "sound-doors-open", "sound-doors-close", "music", "music-interval", "volume", "permission", "capacity", "button-cooldown", "call-cooldown");

    /** Apply one key/value (as typed by an admin). Returns an error message or null. */
    public String set(String key, String value) {
        try {
            switch (key.toLowerCase(Locale.ROOT)) {
                case "model" -> model = value;
                case "model-open" -> modelOpen = none(value);
                case "speed" -> speed = Double.parseDouble(value);
                case "acceleration" -> acceleration = Double.parseDouble(value);
                case "door-time" -> doorTime = Integer.parseInt(value);
                case "door-close-delay" -> doorCloseDelay = Integer.parseInt(value);
                case "cabin-width" -> cabinWidth = Double.parseDouble(value);
                case "cabin-depth" -> cabinDepth = Double.parseDouble(value);
                case "cabin-height" -> cabinHeight = Double.parseDouble(value);
                case "sound-start" -> soundStart = none(value);
                case "sound-move" -> soundMove = none(value);
                case "sound-move-interval" -> soundMoveInterval = Integer.parseInt(value);
                case "sound-arrive" -> soundArrive = none(value);
                case "sound-doors-open" -> soundDoorsOpen = none(value);
                case "sound-doors-close" -> soundDoorsClose = none(value);
                case "music" -> music = none(value);
                case "music-interval" -> musicInterval = Integer.parseInt(value);
                case "volume" -> volume = Float.parseFloat(value);
                case "permission" -> permission = none(value);
                case "capacity" -> capacity = Integer.parseInt(value);
                case "button-cooldown" -> buttonCooldown = Integer.parseInt(value);
                case "call-cooldown" -> callCooldown = Integer.parseInt(value);
                default -> { return "unknown key '" + key + "' (keys: " + String.join(", ", KEYS) + ")"; }
            }
        } catch (NumberFormatException ex) {
            return "'" + value + "' is not a number";
        }
        return null;
    }

    private static String none(String v) { return v == null || v.equalsIgnoreCase("none") || v.equals("-") ? "" : v; }

    public static ElevatorType fromSection(String id, ConfigurationSection s) {
        ElevatorType t = new ElevatorType(id);
        t.model = s.getString("model", "");
        t.modelOpen = s.getString("model-open", "");
        t.speed = s.getDouble("speed", t.speed);
        t.acceleration = s.getDouble("acceleration", t.acceleration);
        t.doorTime = s.getInt("door-time", t.doorTime);
        t.doorCloseDelay = s.getInt("door-close-delay", t.doorCloseDelay);
        t.cabinWidth = s.getDouble("cabin.width", t.cabinWidth);
        t.cabinDepth = s.getDouble("cabin.depth", t.cabinDepth);
        t.cabinHeight = s.getDouble("cabin.height", t.cabinHeight);
        t.soundStart = s.getString("sounds.start", t.soundStart);
        t.soundMove = s.getString("sounds.move", t.soundMove);
        t.soundMoveInterval = s.getInt("sounds.move-interval", t.soundMoveInterval);
        t.soundArrive = s.getString("sounds.arrive", t.soundArrive);
        t.soundDoorsOpen = s.getString("sounds.doors-open", t.soundDoorsOpen);
        t.soundDoorsClose = s.getString("sounds.doors-close", t.soundDoorsClose);
        t.music = s.getString("sounds.music", t.music);
        t.musicInterval = s.getInt("sounds.music-interval", t.musicInterval);
        t.volume = (float) s.getDouble("sounds.volume", t.volume);
        t.permission = s.getString("permission", "");
        t.capacity = s.getInt("capacity", 0);
        t.buttonCooldown = s.getInt("button-cooldown", t.buttonCooldown);
        t.callCooldown = s.getInt("call-cooldown", t.callCooldown);
        return t;
    }

    public void toSection(ConfigurationSection s) {
        s.set("model", model);
        s.set("model-open", modelOpen);
        s.set("speed", speed);
        s.set("acceleration", acceleration);
        s.set("door-time", doorTime);
        s.set("door-close-delay", doorCloseDelay);
        s.set("cabin.width", cabinWidth);
        s.set("cabin.depth", cabinDepth);
        s.set("cabin.height", cabinHeight);
        s.set("sounds.start", soundStart);
        s.set("sounds.move", soundMove);
        s.set("sounds.move-interval", soundMoveInterval);
        s.set("sounds.arrive", soundArrive);
        s.set("sounds.doors-open", soundDoorsOpen);
        s.set("sounds.doors-close", soundDoorsClose);
        s.set("sounds.music", music);
        s.set("sounds.music-interval", musicInterval);
        s.set("sounds.volume", (double) volume);
        s.set("permission", permission);
        s.set("capacity", capacity);
        s.set("button-cooldown", buttonCooldown);
        s.set("call-cooldown", callCooldown);
    }

    public String describe() {
        return "model " + model + (modelOpen.isEmpty() ? "" : " / open " + modelOpen) + ", " + speed + " b/s (acc " + acceleration + "), doors " + doorTime + "t, cabin "
            + cabinWidth + "x" + cabinDepth + "x" + cabinHeight + (permission.isEmpty() ? "" : ", perm " + permission) + (capacity > 0 ? ", capacity " + capacity : "")
            + (music.isEmpty() ? "" : ", music " + music);
    }
}
