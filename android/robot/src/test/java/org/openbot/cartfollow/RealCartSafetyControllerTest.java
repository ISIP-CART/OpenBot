package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;
import org.openbot.vehicle.Control;

public class RealCartSafetyControllerTest {
  @Test
  public void manualRequiresForegroundConnectionAndHandshake() {
    RealCartSafetyController controller = new RealCartSafetyController();
    assertTrue(
        controller
            .manual(
                RealCartSafetyController.MANUAL_FORWARD,
                RealCartSafetyController.MANUAL_FORWARD)
            .isStop());

    controller.setForeground(true);
    controller.setConnection(true, true);
    RealCartSafetyController.Output output =
        controller.manual(
            RealCartSafetyController.MANUAL_FORWARD,
            RealCartSafetyController.MANUAL_FORWARD);
    assertEquals(14, output.left);
    assertEquals(14, output.right);
  }

  @Test
  public void realCartSpeedCapsUseLowSpeedBenchValues() {
    assertEquals(14, RealCartSafetyController.MANUAL_FORWARD);
    assertEquals(12, RealCartSafetyController.MANUAL_REVERSE);
    assertEquals(5, RealCartSafetyController.MANUAL_TURN);
    assertEquals(14, RealCartSafetyController.AUTO_MAX);
    assertEquals(5, RealCartSafetyController.SEARCH_SPEED);
  }

  @Test
  public void modeChangeRevokesAutoUnlock() {
    RealCartSafetyController controller = readyAutoController();
    assertTrue(controller.unlockAuto());
    controller.setMode(RealCartSafetyController.Mode.MANUAL);
    assertFalse(controller.isAutoUnlocked());
  }

  @Test
  public void autoOutputPreservesRatioAndLimit() {
    RealCartSafetyController controller = readyAutoController();
    assertTrue(controller.unlockAuto());
    FollowStateMachine.FrameResult frame =
        frame(new Control(0.6f, 0.3f), BehaviorAction.FOLLOW_SLOW);

    RealCartSafetyController.Output output = controller.auto(frame, 1000L);
    assertEquals(Math.round(0.6f * RealCartSafetyController.AUTO_MAX), output.left);
    assertEquals(Math.round(0.3f * RealCartSafetyController.AUTO_MAX), output.right);
  }

  @Test
  public void staleInferenceStopsAndRevokesUnlock() {
    RealCartSafetyController controller = readyAutoController();
    assertTrue(controller.unlockAuto());
    controller.auto(frame(new Control(0.4f, 0.4f), BehaviorAction.FOLLOW_SLOW), 1000L);

    RealCartSafetyController.Output output =
        controller.watchdog(1000L + RealCartSafetyController.INFERENCE_TIMEOUT_MS + 1L);
    assertNotNull(output);
    assertTrue(output.isStop());
    assertFalse(controller.isAutoUnlocked());
  }

  @Test
  public void searchStopsAfterTwoSeconds() {
    RealCartSafetyController controller = readyAutoController();
    assertTrue(controller.unlockAuto());
    FollowStateMachine.FrameResult frame =
        frame(new Control(0f, 0f), BehaviorAction.LOCAL_SEARCH_LEFT);
    assertFalse(controller.auto(frame, 1000L).isStop());

    RealCartSafetyController.Output output =
        controller.auto(frame, 1000L + RealCartSafetyController.SEARCH_LIMIT_MS + 1L);
    assertTrue(output.isStop());
    assertFalse(controller.isAutoUnlocked());
  }

  @Test
  public void emergencyLatchBlocksEveryMotionRequest() {
    RealCartSafetyController controller = new RealCartSafetyController();
    controller.setForeground(true);
    controller.setConnection(true, true);
    controller.latchEmergency();
    assertTrue(controller.manual(28, 28).isStop());
  }

  private static RealCartSafetyController readyAutoController() {
    RealCartSafetyController controller = new RealCartSafetyController();
    controller.setForeground(true);
    controller.setConnection(true, true);
    controller.setMode(RealCartSafetyController.Mode.AUTO);
    return controller;
  }

  private static FollowStateMachine.FrameResult frame(Control control, BehaviorAction action) {
    FollowStateMachine.FrameResult frame =
        new FollowStateMachine.FrameResult(
            FollowState.FOLLOW,
            control,
            null,
            null,
            Collections.emptyList(),
            true,
            false,
            null,
            -1);
    frame.behaviorDecision =
        new BehaviorDecisionResult(FollowState.FOLLOW, action, "test", null, 1f);
    return frame;
  }
}
