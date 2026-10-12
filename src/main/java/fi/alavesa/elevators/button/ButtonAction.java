package fi.alavesa.elevators.button;

import java.util.Locale;

/** What a button does when a player right-clicks it. */
public enum ButtonAction {
    /** Go to the button's floor (a floor-select button inside the cabin). */
    FLOOR,
    /** Bring the cabin to the button's floor (a landing button outside the cabin). */
    CALL,
    OPEN,
    CLOSE,
    STOP;

    public boolean needsFloor() { return this == FLOOR || this == CALL; }

    public static ButtonAction parse(String s) {
        if (s == null) return null;
        String k = s.toLowerCase(Locale.ROOT);
        int colon = k.indexOf(':');
        if (colon > 0) k = k.substring(0, colon);
        return switch (k) {
            case "floor", "go", "select" -> FLOOR;
            case "call" -> CALL;
            case "open" -> OPEN;
            case "close" -> CLOSE;
            case "stop", "halt" -> STOP;
            default -> null;
        };
    }
}
