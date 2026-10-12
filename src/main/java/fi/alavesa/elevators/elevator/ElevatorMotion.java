package fi.alavesa.elevators.elevator;

/**
 * Smooth vertical travel: a trapezoidal velocity profile (accelerate, cruise, decelerate) or a triangle
 * when the distance is too short to reach full speed. Positions are sampled per server tick.
 */
public final class ElevatorMotion {

    public final double from, to, distance, direction;
    public final double vmax, accel;      // blocks/s, blocks/s²
    private final double tAccel, dAccel, tCruise, total;   // seconds

    public ElevatorMotion(double from, double to, double speed, double acceleration) {
        this.from = from;
        this.to = to;
        this.distance = Math.abs(to - from);
        this.direction = Math.signum(to - from);
        this.vmax = Math.max(0.01, speed);
        this.accel = Math.max(0.01, acceleration);
        double ta = vmax / accel;
        double da = 0.5 * accel * ta * ta;
        if (2 * da >= distance) {
            ta = Math.sqrt(distance / accel);
            da = distance / 2;
            tCruise = 0;
        } else {
            tCruise = (distance - 2 * da) / vmax;
        }
        tAccel = ta;
        dAccel = da;
        total = 2 * tAccel + tCruise;
    }

    public double totalSeconds() { return total; }

    public int totalTicks() { return (int) Math.ceil(total * 20.0); }

    public boolean done(int tick) { return tick >= totalTicks(); }

    /** Cabin Y at a tick since departure; clamped to the destination. */
    public double positionAt(int tick) {
        double t = tick / 20.0;
        if (t >= total || distance == 0) return to;
        double d;
        double peak = accel * tAccel;
        if (t < tAccel) d = 0.5 * accel * t * t;
        else if (t < tAccel + tCruise) d = dAccel + peak * (t - tAccel);
        else { double td = t - tAccel - tCruise; d = dAccel + peak * tCruise + peak * td - 0.5 * accel * td * td; }
        d = Math.max(0, Math.min(distance, d));
        return from + direction * d;
    }

    /** Velocity (blocks/s, signed) at a tick. */
    public double velocityAt(int tick) {
        double t = tick / 20.0;
        if (t >= total || distance == 0) return 0;
        double peak = accel * tAccel;
        double v;
        if (t < tAccel) v = accel * t;
        else if (t < tAccel + tCruise) v = peak;
        else v = Math.max(0, peak - accel * (t - tAccel - tCruise));
        return direction * v;
    }
}
