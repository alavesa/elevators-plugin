package fi.alavesa.elevators.button;

import fi.alavesa.elevators.type.ElevatorType;

import java.util.function.Predicate;

/** Decides whether a player may use an elevator / press a button. Pure: permissions come in as a predicate. */
public final class ButtonAuthorizer {

    public static final String USE = "elevators.use";

    private ButtonAuthorizer() { }

    /** Null when allowed, otherwise the missing permission node. */
    public static String denyElevator(ElevatorType type, Predicate<String> has) {
        if (!has.test(USE)) return USE;
        if (type != null && type.permission != null && !type.permission.isEmpty() && !has.test(type.permission)) return type.permission;
        return null;
    }

    /** Null when allowed, otherwise the missing permission node. */
    public static String denyButton(ButtonDef button, ElevatorType type, Predicate<String> has) {
        String d = denyElevator(type, has);
        if (d != null) return d;
        if (button.permission != null && !button.permission.isEmpty() && !has.test(button.permission)) return button.permission;
        return null;
    }
}
