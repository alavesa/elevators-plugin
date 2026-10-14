package fi.alavesa.elevators.button;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 0.3.0: landing buttons are copied to each new floor. */
class ButtonCloneTest {
    @Test
    void callButtonIsRetargetedAndRenamed() {
        ButtonDef src = new ButtonDef("call_1");
        src.action = ButtonAction.CALL; src.floor = "1"; src.space = "1";
        src.dx = 1.5; src.dy = 1.2; src.dz = -0.5; src.label = "Call to 1"; src.model = "btn"; src.cooldown = 7; src.width = 0.3;
        ButtonDef b = ButtonDef.cloneForFloor(src, "1", "2");
        assertEquals("call_2", b.id);
        assertEquals(ButtonAction.CALL, b.action);
        assertEquals("2", b.floor);
        assertEquals("2", b.space);
        assertEquals("Call to 2", b.label);
        assertEquals(1.5, b.dx, 1e-9); assertEquals(1.2, b.dy, 1e-9); assertEquals(-0.5, b.dz, 1e-9);
        assertEquals("btn", b.model); assertEquals(7, b.cooldown); assertEquals(0.3, b.width, 1e-9);
    }

    @Test
    void otherButtonsKeepTheirTargetAndGetASuffix() {
        ButtonDef src = new ButtonDef("lobby");
        src.action = ButtonAction.FLOOR; src.floor = "1"; src.space = "1";
        ButtonDef b = ButtonDef.cloneForFloor(src, "1", "roof");
        assertEquals("lobby_roof", b.id);
        assertEquals("1", b.floor);
        assertEquals("roof", b.space);
    }
}
