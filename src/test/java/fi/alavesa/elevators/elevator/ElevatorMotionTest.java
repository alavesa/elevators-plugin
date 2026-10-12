package fi.alavesa.elevators.elevator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElevatorMotionTest {

    @Test
    void trapezoidReachesTargetMonotonicallyWithoutOvershoot() {
        ElevatorMotion m = new ElevatorMotion(64, 84, 2.0, 1.0);
        assertEquals(20, m.distance);
        // accel 2s over 1 block/s² -> 2 blocks, cruise 16 blocks at 2 b/s = 8 s, total 12 s = 240 ticks
        assertEquals(12.0, m.totalSeconds(), 1e-9);
        assertEquals(240, m.totalTicks());
        double prev = 64;
        double maxV = 0;
        for (int t = 0; t <= 260; t++) {
            double y = m.positionAt(t);
            assertTrue(y >= prev - 1e-9, "monotonic at tick " + t);
            assertTrue(y <= 84 + 1e-9, "no overshoot at tick " + t);
            maxV = Math.max(maxV, Math.abs(m.velocityAt(t)));
            prev = y;
        }
        assertEquals(84, m.positionAt(240), 1e-9);
        assertEquals(84, m.positionAt(1000), 1e-9);
        assertTrue(m.done(240));
        assertFalse(m.done(239));
        assertEquals(2.0, maxV, 1e-9);
        assertEquals(74, m.positionAt(120), 1e-6, "half way at half time");
    }

    @Test
    void shortTripIsATriangleAndDownwardsWorks() {
        ElevatorMotion m = new ElevatorMotion(70, 68, 5.0, 1.0);   // 2 blocks: sqrt(2) s each way
        assertEquals(2 * Math.sqrt(2), m.totalSeconds(), 1e-9);
        assertEquals(-1, m.direction);
        assertEquals(68, m.positionAt(m.totalTicks()), 1e-9);
        assertTrue(m.positionAt(10) < 70 && m.positionAt(10) > 68);
        assertTrue(Math.abs(m.velocityAt(5)) < 5.0, "never reaches full speed on a short trip");
        ElevatorMotion zero = new ElevatorMotion(10, 10, 2, 2);
        assertEquals(0, zero.totalTicks());
        assertEquals(10, zero.positionAt(0));
    }
}
