package fi.alavesa.elevators.button;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Per player + button cooldowns in server ticks. Pure, so it is unit-tested with a fake clock. */
public final class ButtonCooldowns {

    private final Map<String, Long> until = new HashMap<>();

    private static String key(UUID player, String elevator, String button) {
        return player + "|" + elevator + "|" + button;
    }

    /** True (and arms the cooldown) when the button may fire now; false while still cooling down. */
    public boolean tryUse(UUID player, String elevator, String button, long nowTick, int cooldownTicks) {
        String k = key(player, elevator, button);
        Long u = until.get(k);
        if (u != null && nowTick < u) return false;
        if (cooldownTicks > 0) until.put(k, nowTick + cooldownTicks); else until.remove(k);
        return true;
    }

    public long remaining(UUID player, String elevator, String button, long nowTick) {
        Long u = until.get(key(player, elevator, button));
        return u == null ? 0 : Math.max(0, u - nowTick);
    }

    public void clear(UUID player) {
        until.keySet().removeIf(k -> k.startsWith(player.toString() + "|"));
    }

    public void clearElevator(String elevator) {
        until.keySet().removeIf(k -> k.contains("|" + elevator + "|"));
    }

    /** Drop expired entries (call every few seconds). */
    public void prune(long nowTick) {
        Iterator<Map.Entry<String, Long>> it = until.entrySet().iterator();
        while (it.hasNext()) if (it.next().getValue() <= nowTick) it.remove();
    }

    public int size() { return until.size(); }
}
