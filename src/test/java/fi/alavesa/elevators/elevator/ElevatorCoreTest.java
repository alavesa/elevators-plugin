package fi.alavesa.elevators.elevator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ElevatorCoreTest {

    static final class FakePort implements ElevatorCore.Port {
        final Set<String> obstructed = new HashSet<>();
        final List<String> log = new ArrayList<>();
        double lastY; int moves;
        @Override public boolean isObstructed(Floor f) { return obstructed.contains(f.name()); }
        @Override public void onDepart(Floor from, Floor to) { log.add("depart " + from.name() + "->" + to.name()); }
        @Override public void onMove(double y, double v) { lastY = y; moves++; }
        @Override public void onArrive(Floor f) { log.add("arrive " + f.name()); }
        @Override public void onDoors(boolean open) { log.add(open ? "open" : "close"); }
        @Override public void onDropped(Floor f, ElevatorCore.Result why) { log.add("dropped " + f.name() + " " + why); }
    }

    FakePort port;
    ElevatorCore core;

    @BeforeEach
    void setUp() {
        port = new FakePort();
        core = new ElevatorCore(port, 4.0, 4.0, 40, 5);
        core.putFloor(new Floor("ground", 64));
        core.putFloor(new Floor("first", 70));
        core.putFloor(new Floor("roof", 90));
    }

    private void run(int ticks) { for (int i = 0; i < ticks; i++) core.tick(); }

    /** Tick until the state changes away from {@code from} (bounded). */
    private int runWhile(ElevatorCore.State from) {
        int n = 0;
        while (core.state() == from && n < 5000) { core.tick(); n++; }
        return n;
    }

    @Test
    void floorsAreSortedAndLookedUpCaseInsensitively() {
        assertEquals(List.of("ground", "first", "roof"), core.floors().stream().map(Floor::name).toList());
        assertEquals(70, core.floor("FIRST").y());
        assertEquals("ground", core.current().name());
        assertEquals(64, core.y());
    }

    @Test
    void requestTravelsOpensDoorsAndClosesThem() {
        assertEquals(ElevatorCore.Result.OK, core.request("first"));
        assertEquals(ElevatorCore.State.IDLE, core.state());
        core.tick();
        assertEquals(ElevatorCore.State.MOVING, core.state());
        assertEquals("depart ground->first", port.log.get(0));
        int travel = runWhile(ElevatorCore.State.MOVING);
        assertEquals(50, travel, "6 blocks at 4 b/s with 4 b/s² = 1 s + 0.5 s + 1 s");
        assertEquals(ElevatorCore.State.DOORS_OPEN, core.state());
        assertEquals("first", core.current().name());
        assertEquals(70, core.y(), 1e-9);
        assertTrue(port.log.contains("arrive first"));
        assertTrue(port.log.contains("open"));
        assertTrue(port.moves > 10);
        assertEquals(40, runWhile(ElevatorCore.State.DOORS_OPEN), "doors stay open door-time ticks");
        assertEquals(ElevatorCore.State.IDLE, core.state());
        assertEquals("close", port.log.get(port.log.size() - 1));
    }

    @Test
    void invalidFloorAndSameFloorAndQueueing() {
        assertEquals(ElevatorCore.Result.INVALID_FLOOR, core.request("basement"));
        assertEquals(ElevatorCore.Result.ALREADY_THERE, core.request("ground"));
        assertEquals(ElevatorCore.State.DOORS_OPEN, core.state(), "requesting the current floor re-opens the doors");
        assertEquals(ElevatorCore.Result.OK, core.request("roof"));
        assertEquals(ElevatorCore.Result.QUEUED, core.request("first"));
        assertEquals(ElevatorCore.Result.QUEUED, core.request("first"), "duplicate stays queued once");
        assertEquals(2, core.queue().size());
        assertEquals(5, runWhile(ElevatorCore.State.DOORS_OPEN), "doors close after door-close-delay once a floor is chosen");
        core.tick();
        assertEquals(ElevatorCore.State.MOVING, core.state());
        assertEquals("roof", core.target().name());
        assertEquals(ElevatorCore.Result.BUSY, core.request("roof"));
        runWhile(ElevatorCore.State.MOVING);
        assertEquals("roof", core.current().name());
        runWhile(ElevatorCore.State.DOORS_OPEN);   // doors time out, then the queued 'first' is served
        core.tick();
        assertEquals(ElevatorCore.State.MOVING, core.state());
        runWhile(ElevatorCore.State.MOVING);
        assertEquals("first", core.current().name());
    }

    @Test
    void obstructedDestinationIsRefusedOrDropped() {
        port.obstructed.add("roof");
        assertEquals(ElevatorCore.Result.OBSTRUCTED, core.request("roof"));
        assertEquals(ElevatorCore.State.IDLE, core.state());
        assertTrue(core.queue().isEmpty());
        // obstruction that appears while queued is dropped when its turn comes
        assertEquals(ElevatorCore.Result.OK, core.request("first"));
        core.tick();
        port.obstructed.remove("roof");
        assertEquals(ElevatorCore.Result.QUEUED, core.request("roof"));
        port.obstructed.add("roof");
        runWhile(ElevatorCore.State.MOVING);
        assertEquals("first", core.current().name());
        runWhile(ElevatorCore.State.DOORS_OPEN);
        core.tick();
        assertTrue(port.log.contains("dropped roof OBSTRUCTED"), port.log.toString());
        assertEquals(ElevatorCore.State.IDLE, core.state());
        assertEquals("first", core.current().name());
    }

    @Test
    void stopBrakesToNearestFloorAheadAndClearsQueue() {
        core.request("roof");
        core.request("first");
        core.tick();
        run(20);
        assertTrue(core.y() > 64 && core.y() < 70);
        Floor stopAt = core.stop();
        assertEquals("first", stopAt.name(), "nearest floor in the direction of travel");
        assertTrue(core.queue().isEmpty());
        runWhile(ElevatorCore.State.MOVING);
        assertEquals("first", core.current().name());
        assertEquals(ElevatorCore.State.DOORS_OPEN, core.state());
        assertEquals(70, core.y(), 1e-9);
    }

    @Test
    void restSnapsForShutdownAndRemovingFloorsIsSafe() {
        core.request("roof");
        core.tick();
        run(30);
        Floor n = core.nearestFloor();
        core.rest(n);
        assertEquals(ElevatorCore.State.IDLE, core.state());
        assertEquals(n.y(), core.y());
        assertTrue(core.queue().isEmpty());
        core.request("roof");
        core.tick();
        run(10);
        assertTrue(core.removeFloor("roof"));
        assertNotEquals("roof", core.target() == null ? "" : core.target().name());
        run(400);
        assertNotNull(core.current());
        assertNotEquals("roof", core.current().name());
        assertEquals(2, core.floors().size());
    }

    @Test
    void holdDoorsKeepsThemOpenUntilClosed() {
        core.openDoors();
        core.holdDoors();
        run(500);
        assertEquals(ElevatorCore.State.DOORS_OPEN, core.state());
        core.closeDoors();
        run(6);
        assertEquals(ElevatorCore.State.IDLE, core.state());
        ElevatorCore empty = new ElevatorCore(port, 1, 1, 10, 1);
        assertEquals(ElevatorCore.Result.NO_FLOORS, empty.request("x"));
    }
}
