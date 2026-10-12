package fi.alavesa.elevators.elevator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The elevator state machine without any Bukkit: floors, a request queue, doors and motion.
 * The host (Bukkit {@code Elevator}) implements {@link Port} to move entities, play sounds and check
 * the world, and calls {@link #tick()} once per server tick.
 *
 * States: IDLE (doors closed, resting at {@code current}), DOORS_OPEN (resting, doors open, timer running),
 * MOVING (travelling to {@code target}).
 */
public final class ElevatorCore {

    public enum State { IDLE, DOORS_OPEN, MOVING }

    public enum Result { OK, QUEUED, ALREADY_THERE, INVALID_FLOOR, OBSTRUCTED, NO_FLOORS, BUSY }

    /** What the host must provide / gets told. */
    public interface Port {
        /** True when the cabin volume at this floor contains solid blocks or the floor is otherwise unusable. */
        boolean isObstructed(Floor floor);
        void onDepart(Floor from, Floor to);
        void onMove(double y, double velocity);
        void onArrive(Floor floor);
        void onDoors(boolean open);
        /** A queued request was dropped (destination obstructed when its turn came). */
        void onDropped(Floor floor, Result why);
    }

    private final List<Floor> floors = new ArrayList<>();
    private final Port port;
    private double speed, acceleration;
    private int doorTime, doorCloseDelay;

    private State state = State.IDLE;
    private Floor current;
    private Floor target;
    private double y;
    private ElevatorMotion motion;
    private int moveTick;
    private int doorTicks;
    private boolean doorHold;
    private final Deque<Floor> queue = new ArrayDeque<>();

    public ElevatorCore(Port port, double speed, double acceleration, int doorTime, int doorCloseDelay) {
        this.port = port;
        configure(speed, acceleration, doorTime, doorCloseDelay);
    }

    public void configure(double speed, double acceleration, int doorTime, int doorCloseDelay) {
        this.speed = speed;
        this.acceleration = acceleration;
        this.doorTime = Math.max(1, doorTime);
        this.doorCloseDelay = Math.max(0, doorCloseDelay);
    }

    // ---------------------------------------------------------------- floors

    public List<Floor> floors() { return Collections.unmodifiableList(floors); }

    public Floor floor(String name) {
        if (name == null) return null;
        for (Floor f : floors) if (f.name().equalsIgnoreCase(name)) return f;
        return null;
    }

    /** Add or replace a floor (by name). Floors stay sorted by Y. */
    public void putFloor(Floor f) {
        floors.removeIf(x -> x.name().equalsIgnoreCase(f.name()));
        floors.add(f);
        Collections.sort(floors);
        if (current == null) { current = f; y = f.y(); }
        else if (current.name().equalsIgnoreCase(f.name())) { current = f; if (state != State.MOVING) y = f.y(); }
    }

    public boolean removeFloor(String name) {
        Floor f = floor(name);
        if (f == null) return false;
        floors.remove(f);
        queue.removeIf(q -> q.name().equalsIgnoreCase(name));
        if (target != null && target.name().equalsIgnoreCase(name) && state == State.MOVING) {
            Floor n = nearestFloor();
            if (n != null) retarget(n); else { state = State.IDLE; target = null; }
        }
        if (current != null && current.name().equalsIgnoreCase(name)) current = nearestFloor();
        return true;
    }

    /** Place the cabin at a floor without moving (load from disk / shutdown snap). */
    public void rest(Floor f) {
        current = f;
        target = null;
        y = f.y();
        state = State.IDLE;
        motion = null;
        queue.clear();
    }

    public Floor nearestFloor() {
        Floor best = null;
        for (Floor f : floors) if (best == null || Math.abs(f.y() - y) < Math.abs(best.y() - y)) best = f;
        return best;
    }

    /** Nearest floor in the direction of travel that the cabin can still stop at; falls back to the nearest. */
    public Floor nearestAhead() {
        if (state != State.MOVING || motion == null) return nearestFloor();
        Floor best = null;
        for (Floor f : floors) {
            double d = (f.y() - y) * motion.direction;
            if (d < -0.01) continue;
            if (best == null || Math.abs(f.y() - y) < Math.abs(best.y() - y)) best = f;
        }
        return best != null ? best : nearestFloor();
    }

    // ---------------------------------------------------------------- requests

    /** Ask the elevator to go to / come to a floor (a floor button, a call button, /elevator call). */
    public Result request(Floor f) {
        if (floors.isEmpty()) return Result.NO_FLOORS;
        if (f == null || floor(f.name()) == null) return Result.INVALID_FLOOR;
        f = floor(f.name());
        if (state != State.MOVING && current != null && f.name().equalsIgnoreCase(current.name())) {
            openDoors();
            return Result.ALREADY_THERE;
        }
        if (state == State.MOVING && target != null && target.name().equalsIgnoreCase(f.name())) return Result.BUSY;
        for (Floor q : queue) if (q.name().equalsIgnoreCase(f.name())) return Result.QUEUED;
        if (state != State.MOVING && queue.isEmpty() && port.isObstructed(f)) return Result.OBSTRUCTED;
        queue.add(f);
        if (state == State.DOORS_OPEN) { doorHold = false; doorTicks = Math.min(doorTicks, doorCloseDelay); }
        return state == State.MOVING || queue.size() > 1 ? Result.QUEUED : Result.OK;
    }

    public Result request(String floorName) { return request(floor(floorName)); }

    public void openDoors() {
        if (state == State.MOVING) return;
        if (state != State.DOORS_OPEN) { state = State.DOORS_OPEN; port.onDoors(true); }
        doorTicks = doorTime;
    }

    /** Keep the doors open until closeDoors() / a request. */
    public void holdDoors() { openDoors(); doorHold = true; }

    public void closeDoors() {
        if (state != State.DOORS_OPEN) return;
        doorHold = false;
        doorTicks = Math.min(doorTicks, doorCloseDelay);
    }

    /** Emergency stop: brake to the nearest reachable floor ahead and open the doors there. Clears the queue. */
    public Floor stop() {
        queue.clear();
        if (state != State.MOVING) return current;
        Floor n = nearestAhead();
        if (n == null) return null;
        retarget(n);
        return n;
    }

    private void retarget(Floor f) {
        target = f;
        motion = new ElevatorMotion(y, f.y(), speed, acceleration);
        moveTick = 0;
    }

    // ---------------------------------------------------------------- tick

    public void tick() {
        switch (state) {
            case IDLE -> {
                if (queue.isEmpty() || current == null) return;
                Floor next = queue.poll();
                if (port.isObstructed(next)) { port.onDropped(next, Result.OBSTRUCTED); return; }
                Floor from = current;
                retarget(next);
                state = State.MOVING;
                port.onDepart(from, next);
            }
            case DOORS_OPEN -> {
                if (doorHold) return;
                if (--doorTicks <= 0) { state = State.IDLE; port.onDoors(false); }
            }
            case MOVING -> {
                moveTick++;
                y = motion.positionAt(moveTick);
                port.onMove(y, motion.velocityAt(moveTick));
                if (motion.done(moveTick)) {
                    y = target.y();
                    current = target;
                    target = null;
                    motion = null;
                    state = State.DOORS_OPEN;
                    doorTicks = doorTime;
                    doorHold = false;
                    port.onArrive(current);
                    port.onDoors(true);
                }
            }
        }
    }

    // ---------------------------------------------------------------- accessors

    public State state() { return state; }
    public Floor current() { return current; }
    public Floor target() { return target; }
    public double y() { return y; }
    public boolean moving() { return state == State.MOVING; }
    public boolean doorsOpen() { return state == State.DOORS_OPEN; }
    public List<Floor> queue() { return new ArrayList<>(queue); }
    public double direction() { return motion == null ? 0 : motion.direction; }
    public int moveTick() { return moveTick; }
}
