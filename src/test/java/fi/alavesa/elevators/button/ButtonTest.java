package fi.alavesa.elevators.button;

import fi.alavesa.elevators.type.ElevatorType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ButtonTest {

    @Test
    void actionParsing() {
        assertEquals(ButtonAction.FLOOR, ButtonAction.parse("floor:2"));
        assertEquals(ButtonAction.CALL, ButtonAction.parse("CALL"));
        assertEquals(ButtonAction.STOP, ButtonAction.parse("halt"));
        assertNull(ButtonAction.parse("dance"));
        assertTrue(ButtonAction.CALL.needsFloor());
        assertFalse(ButtonAction.OPEN.needsFloor());
    }

    @Test
    void definitionValidationAndSetters() {
        ButtonDef b = new ButtonDef("b1");
        assertNull(b.set("action", "floor:roof"));
        assertEquals("roof", b.floor);
        assertEquals(List.of(), b.validate(Set.of("ground", "roof")));
        assertTrue(b.validate(Set.of("ground")).get(0).contains("does not exist"));
        assertNull(b.set("space", "attic"));
        assertTrue(b.validate(Set.of("ground", "roof")).stream().anyMatch(s -> s.contains("space")));
        assertNull(b.set("space", "cabin"));
        assertNull(b.set("offset", "0.5, 1.2, -1.4"));
        assertEquals(-1.4, b.dz);
        assertNull(b.set("size", "0.3x0.6"));
        assertEquals(0.6, b.height);
        assertNotNull(b.set("size", "huge"));
        assertNull(b.set("cooldown", "default"));
        assertEquals(-1, b.cooldown);
        assertEquals(10, b.cooldownOr(10));
        assertNull(b.set("cooldown", "40"));
        assertEquals(40, b.cooldownOr(10));
        assertNotNull(b.set("colour", "red"));
        ButtonDef bad = new ButtonDef("Bad id");
        bad.dx = 50;
        assertTrue(bad.validate(Set.of()).size() >= 3);
    }

    @Test
    void yamlRoundTrip() {
        ButtonDef b = new ButtonDef("call_g");
        b.action = ButtonAction.CALL; b.floor = "ground"; b.space = "ground"; b.dx = 1.5; b.dy = 1.2; b.dz = -0.7; b.label = "Call"; b.model = "button_red"; b.cooldown = 30; b.permission = "elevators.staff";
        YamlConfiguration y = new YamlConfiguration();
        b.toSection(y.createSection("buttons.call_g"));
        ButtonDef r = ButtonDef.fromSection("call_g", y.getConfigurationSection("buttons.call_g"));
        assertEquals(ButtonAction.CALL, r.action);
        assertEquals("ground", r.floor);
        assertFalse(r.inCabin());
        assertEquals(1.5, r.dx); assertEquals(-0.7, r.dz);
        assertEquals("Call", r.label);
        assertEquals("button_red", r.model);
        assertEquals(30, r.cooldown);
        assertEquals("elevators.staff", r.permission);
    }

    @Test
    void cooldownsPerPlayerAndButton() {
        ButtonCooldowns cd = new ButtonCooldowns();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(cd.tryUse(a, "lift", "b1", 100, 20));
        assertFalse(cd.tryUse(a, "lift", "b1", 110, 20));
        assertEquals(10, cd.remaining(a, "lift", "b1", 110));
        assertTrue(cd.tryUse(a, "lift", "b2", 110, 20), "other button of the same player is independent");
        assertTrue(cd.tryUse(b, "lift", "b1", 110, 20), "other player is independent");
        assertTrue(cd.tryUse(a, "lift", "b1", 120, 20), "usable again when the cooldown expired");
        assertTrue(cd.tryUse(a, "lift", "b3", 120, 0), "zero cooldown never blocks");
        assertTrue(cd.tryUse(a, "lift", "b3", 120, 0));
        cd.clear(a);
        assertTrue(cd.tryUse(a, "lift", "b1", 121, 20));
        cd.clearElevator("lift");
        assertEquals(0, cd.size());
        cd.tryUse(a, "x", "y", 0, 5);
        cd.prune(10);
        assertEquals(0, cd.size());
    }

    @Test
    void authorization() {
        ElevatorType t = new ElevatorType("vip");
        t.permission = "elevators.vip";
        ButtonDef b = new ButtonDef("b");
        b.permission = "elevators.button.secret";
        assertEquals("elevators.use", ButtonAuthorizer.denyElevator(t, p -> false));
        assertEquals("elevators.vip", ButtonAuthorizer.denyElevator(t, p -> p.equals("elevators.use")));
        assertNull(ButtonAuthorizer.denyElevator(t, p -> true));
        assertEquals("elevators.button.secret", ButtonAuthorizer.denyButton(b, t, p -> !p.contains("secret")));
        assertNull(ButtonAuthorizer.denyButton(b, t, p -> true));
        assertNull(ButtonAuthorizer.denyElevator(null, p -> p.equals("elevators.use")));
    }
}
