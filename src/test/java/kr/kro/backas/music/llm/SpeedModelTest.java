package kr.kro.backas.music.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeedModelTest {

    @Test
    void unmeasuredModelUsesTheDefaultCurve() {
        SpeedModel model = new SpeedModel();
        assertEquals(SpeedModel.DEFAULT_SPEED, model.estimate(1), 0.001);
        assertTrue(model.estimate(2) < model.estimate(1));
        assertFalse(model.isMeasured(1));
    }

    @Test
    void measurementsReplaceEstimatesAndConverge() {
        SpeedModel model = new SpeedModel();
        model.record(1, 80);
        assertEquals(80, model.estimate(1), 0.001);
        for (int i = 0; i < 30; i++) model.record(1, 90);
        assertEquals(90, model.estimate(1), 0.5);
    }

    @Test
    void unmeasuredLevelsScaleFromTheNearestMeasuredLevel() {
        SpeedModel model = new SpeedModel();
        model.record(1, 85);
        double two = model.estimate(2);
        assertEquals(85 * Math.pow(0.5, SpeedModel.DECAY), two, 0.001);
        model.record(2, 60);
        assertEquals(60, model.estimate(2), 0.001);
        assertEquals(60 * Math.pow(2.0 / 3, SpeedModel.DECAY), model.estimate(3), 0.001);
    }

    @Test
    void levelsBetweenTwoMeasurementsAreInterpolated() {
        SpeedModel model = new SpeedModel();
        model.record(1, 80);
        model.record(4, 68);
        assertEquals(76, model.estimate(2), 0.001);
        assertEquals(72, model.estimate(3), 0.001);
        assertEquals(68 * Math.pow(4.0 / 5, SpeedModel.DECAY), model.estimate(5), 0.001);
    }

    @Test
    void ignoresInvalidSamples() {
        SpeedModel model = new SpeedModel();
        model.record(1, 0);
        model.record(1, Double.NaN);
        model.record(1, Double.POSITIVE_INFINITY);
        assertFalse(model.isMeasured(1));
    }

    @Test
    void restoredSpeedIsUsedAtOnceAndThenTunedByNewMeasurements() {
        SpeedModel model = new SpeedModel();
        model.restore(1, 70);
        model.restore(2, 50);
        assertTrue(model.isMeasured(1));
        assertEquals(70, model.estimate(1), 0.001);
        assertEquals(50, model.estimate(2), 0.001);
        model.record(1, 90);
        assertEquals(76, model.estimate(1), 0.001);
        assertEquals(76, model.learned()[1], 0.001);
        assertEquals(0, model.learned()[3], 0.001);
    }

    @Test
    void restoreNeverOverwritesAMeasurementOrAcceptsBadValues() {
        SpeedModel model = new SpeedModel();
        model.record(1, 80);
        model.restore(1, 20);
        model.restore(2, Double.NaN);
        model.restore(0, 50);
        model.restore(SpeedModel.MAX_LEVEL + 1, 50);
        assertEquals(80, model.estimate(1), 0.001);
        assertFalse(model.isMeasured(2));
        assertFalse(model.isMeasured(SpeedModel.MAX_LEVEL));
    }
}
