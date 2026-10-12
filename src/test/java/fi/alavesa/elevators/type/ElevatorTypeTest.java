package fi.alavesa.elevators.type;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ElevatorTypeTest {

    @Test
    void defaultsAreValidOnceAModelIsSet() {
        ElevatorType t = new ElevatorType("glass");
        assertFalse(t.validate().isEmpty(), "model is required");
        t.model = "glass_cabin";
        assertEquals(List.of(), t.validate());
    }

    @Test
    void rejectsBadValues() {
        ElevatorType t = new ElevatorType("Bad Id!");
        t.model = "m";
        t.speed = 0;
        t.acceleration = -1;
        t.doorTime = 0;
        t.cabinWidth = 40;
        t.capacity = -2;
        t.soundArrive = "Not A Sound";
        List<String> e = t.validate();
        assertTrue(e.stream().anyMatch(s -> s.contains("id")));
        assertTrue(e.stream().anyMatch(s -> s.contains("speed")));
        assertTrue(e.stream().anyMatch(s -> s.contains("acceleration")));
        assertTrue(e.stream().anyMatch(s -> s.contains("door-time")));
        assertTrue(e.stream().anyMatch(s -> s.contains("cabin")));
        assertTrue(e.stream().anyMatch(s -> s.contains("capacity")));
        assertTrue(e.stream().anyMatch(s -> s.contains("sound-arrive")));
        assertEquals(7, e.size(), e.toString());
    }

    @Test
    void setParsesKeysAndReportsUnknownOrBadNumbers() {
        ElevatorType t = new ElevatorType("x");
        assertNull(t.set("speed", "3.5"));
        assertEquals(3.5, t.speed);
        assertNull(t.set("music", "minecraft:music_disc.cat"));
        assertNull(t.set("music", "none"));
        assertEquals("", t.music);
        assertNotNull(t.set("speed", "fast"));
        assertNotNull(t.set("colour", "red"));
        assertNull(t.set("permission", "elevators.vip"));
        assertEquals("elevators.vip", t.permission);
    }

    @Test
    void roundTripsThroughYaml() {
        ElevatorType t = new ElevatorType("fast");
        t.model = "cab"; t.modelOpen = "cab_open"; t.speed = 6; t.doorTime = 30; t.cabinWidth = 2.5; t.music = "minecraft:music_disc.13"; t.capacity = 4;
        YamlConfiguration y = new YamlConfiguration();
        t.toSection(y.createSection("types.fast"));
        String text = y.saveToString();
        YamlConfiguration back = new YamlConfiguration();
        assertDoesNotThrow(() -> back.loadFromString(text));
        ElevatorType r = ElevatorType.fromSection("fast", back.getConfigurationSection("types.fast"));
        assertEquals("cab_open", r.modelOpen);
        assertEquals(6, r.speed);
        assertEquals(30, r.doorTime);
        assertEquals(2.5, r.cabinWidth);
        assertEquals("minecraft:music_disc.13", r.music);
        assertEquals(4, r.capacity);
        assertEquals(List.of(), r.validate());
    }
}
