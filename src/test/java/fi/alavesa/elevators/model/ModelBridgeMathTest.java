package fi.alavesa.elevators.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** BetterModel cabins must face exactly as the ItemDisplay cabins did (BetterModel adds its own 180-degree turn). */
class ModelBridgeMathTest {
    @Test
    void cabinYawCompensatesBetterModelsBuiltInTurn() {
        assertEquals(180f, ModelBridge.modelYaw(0f, 0), 1e-6);
        assertEquals(-90f, ModelBridge.modelYaw(90f, 0), 1e-6);
        assertEquals(0f, ModelBridge.modelYaw(180f, 0), 1e-6);
        assertEquals(0f, ModelBridge.modelYaw(0f, 180), 1e-6);     // config flip for a backwards model
        assertEquals(-170f, ModelBridge.modelYaw(0f, 10), 1e-6);   // normalised into -180..180
    }
}
