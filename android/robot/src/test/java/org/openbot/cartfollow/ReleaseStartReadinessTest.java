package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReleaseStartReadinessTest {
  @Test
  public void readinessUsesSafetyOrderedGates() {
    assertState(
        ReleaseStartReadiness.State.EMERGENCY,
        ReleaseStartReadiness.evaluate(true, false, false, false, "", false));
    assertState(
        ReleaseStartReadiness.State.BLE_DISCONNECTED,
        ReleaseStartReadiness.evaluate(false, false, false, false, "", false));
    assertState(
        ReleaseStartReadiness.State.FIRMWARE_WAIT,
        ReleaseStartReadiness.evaluate(false, true, false, false, "", false));
    assertState(
        ReleaseStartReadiness.State.CAMERA_WAIT,
        ReleaseStartReadiness.evaluate(false, true, true, false, "", false));
    assertState(
        ReleaseStartReadiness.State.MODEL_ERROR,
        ReleaseStartReadiness.evaluate(
            false, true, true, false, "模型加载失败: missing model", true));
    assertState(
        ReleaseStartReadiness.State.MODEL_LOADING,
        ReleaseStartReadiness.evaluate(false, true, true, false, "", true));

    ReleaseStartReadiness ready =
        ReleaseStartReadiness.evaluate(false, true, true, true, "", true);
    assertEquals(ReleaseStartReadiness.State.READY, ready.state);
    assertTrue(ready.ready());
  }

  private static void assertState(
      ReleaseStartReadiness.State expected, ReleaseStartReadiness actual) {
    assertEquals(expected, actual.state);
    assertFalse(actual.ready());
  }
}
