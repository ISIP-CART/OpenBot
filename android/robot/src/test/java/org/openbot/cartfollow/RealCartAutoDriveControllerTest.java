package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.openbot.tflite.Detector;
import org.openbot.vehicle.Control;

public class RealCartAutoDriveControllerTest {
  @Test
  public void stoppedCartRequiresThreeCenteredFarFrames() {
    RealCartAutoDriveController controller = new RealCartAutoDriveController();
    assertTrue(controller.update(followFrame(0f, 0.79f), 0L).isStop());
    assertTrue(controller.update(followFrame(0f, 0.79f), 33L).isStop());

    RealCartAutoDriveController.Result result = controller.update(followFrame(0f, 0.79f), 66L);
    assertEquals(14, result.left);
    assertEquals(14, result.right);
    assertEquals(RealCartAutoDriveController.Phase.MOVING_STRAIGHT, result.phase);
  }

  @Test
  public void stoppedCartCannotStartOffCenterOrAtSetpoint() {
    RealCartAutoDriveController controller = new RealCartAutoDriveController();
    for (int i = 0; i < 5; i++) {
      assertTrue(controller.update(followFrame(0.6f, 0.79f), i * 30L).isStop());
    }
    assertTrue(
        controller
            .update(frame(0f, 0.90f, FollowState.FOLLOW, BehaviorAction.FOLLOW_CAUTION), 200L)
            .isStop());
  }

  @Test
  public void stoppedCartCanStartWithStableGentleCurve() {
    RealCartAutoDriveController controller = new RealCartAutoDriveController();
    assertTrue(controller.update(followFrame(0.30f, 0.75f), 0L).isStop());
    assertTrue(controller.update(followFrame(0.30f, 0.75f), 30L).isStop());

    RealCartAutoDriveController.Result result =
        controller.update(followFrame(0.30f, 0.75f), 60L);
    assertEquals(12, result.left);
    assertEquals(14, result.right);
    assertEquals(RealCartAutoDriveController.Phase.CURVE_LEFT, result.phase);
    assertEquals("小车左缓弯", RealCartFollowFragment.commandForAutoResult(result));

    controller = new RealCartAutoDriveController();
    controller.update(followFrame(-0.30f, 0.75f), 0L);
    controller.update(followFrame(-0.30f, 0.75f), 30L);
    result = controller.update(followFrame(-0.30f, 0.75f), 60L);
    assertEquals(14, result.left);
    assertEquals(12, result.right);
    assertEquals(RealCartAutoDriveController.Phase.CURVE_RIGHT, result.phase);
    assertEquals("小车右缓弯", RealCartFollowFragment.commandForAutoResult(result));
  }

  @Test
  public void movingCartUsesOnlyForwardStraightAndCurveCommands() {
    RealCartAutoDriveController controller = movingController();
    RealCartAutoDriveController.Result result = null;
    for (int i = 0; i < 6; i++) {
      result = controller.update(followFrame(0.30f, 0.75f), 100L + i * 30L);
      assertFalse(result.isStop());
      assertTrue(result.left >= 0 && result.right >= 0);
      assertTrue(result.left <= 14 && result.right <= 14);
    }
    assertEquals(12, result.left);
    assertEquals(14, result.right);

    for (int i = 0; i < 12; i++) {
      result = controller.update(followFrame(-0.30f, 0.75f), 400L + i * 30L);
      assertFalse(result.isStop());
      assertTrue(result.left >= 0 && result.right >= 0);
    }
    assertEquals(14, result.left);
    assertEquals(12, result.right);
  }

  @Test
  public void largeOffsetDistanceAndSearchAlwaysStop() {
    RealCartAutoDriveController controller = movingController();
    for (int i = 0; i < 8; i++) {
      controller.update(followFrame(0.75f, 0.75f), 100L + i * 30L);
    }
    assertTrue(controller.getLastResult().isStop());

    controller = movingController();
    assertTrue(
        controller
            .update(frame(0f, 0.90f, FollowState.FOLLOW, BehaviorAction.FOLLOW_CAUTION), 100L)
            .isStop());
    assertTrue(
        controller
            .update(frame(0f, 0.75f, FollowState.SEARCH, BehaviorAction.LOCAL_SEARCH_LEFT), 200L)
            .isStop());
  }

  @Test
  public void recoveryLocksOutAfterTwoSeconds() {
    RealCartAutoDriveController controller = movingController();
    RealCartAutoDriveController.Result first =
        controller.update(
            frame(0f, Float.NaN, FollowState.IDENTITY_UNCERTAIN, BehaviorAction.MOTION_STOP),
            1000L);
    assertTrue(first.isStop());
    assertFalse(first.lockout);

    RealCartAutoDriveController.Result timedOut =
        controller.update(
            frame(0f, Float.NaN, FollowState.IDENTITY_UNCERTAIN, BehaviorAction.MOTION_STOP),
            1000L + RealCartAutoDriveController.RECOVERY_LIMIT_MS);
    assertTrue(timedOut.isStop());
    assertTrue(timedOut.lockout);
    assertEquals(RealCartAutoDriveController.Phase.LOCKED, timedOut.phase);
    assertEquals("target_missing_timeout", timedOut.reason);
  }

  @Test
  public void visiblePersonKeepsStationaryRecoveryAliveAndResetsMissingTimer() {
    RealCartAutoDriveController controller = movingController();
    RealCartAutoDriveController.Result visible =
        controller.update(recoveryFrame(true), 1000L);
    assertTrue(visible.isStop());
    assertFalse(visible.lockout);
    assertEquals("person_visible_reacquire", visible.reason);
    assertFalse(controller.update(recoveryFrame(true), 11000L).lockout);

    assertFalse(controller.update(recoveryFrame(false), 12000L).lockout);
    assertFalse(controller.update(recoveryFrame(true), 13500L).lockout);
    assertFalse(controller.update(recoveryFrame(false), 15000L).lockout);
    assertTrue(
        controller
            .update(
                recoveryFrame(false),
                15000L + RealCartAutoDriveController.RECOVERY_LIMIT_MS)
            .lockout);
  }

  private static RealCartAutoDriveController movingController() {
    RealCartAutoDriveController controller = new RealCartAutoDriveController();
    controller.update(followFrame(0f, 0.75f), 0L);
    controller.update(followFrame(0f, 0.75f), 30L);
    controller.update(followFrame(0f, 0.75f), 60L);
    return controller;
  }

  private static FollowStateMachine.FrameResult followFrame(float turn, float heightScale) {
    return frame(turn, heightScale, FollowState.FOLLOW, BehaviorAction.FOLLOW_SLOW);
  }

  private static FollowStateMachine.FrameResult recoveryFrame(boolean personVisible) {
    return frame(
        0f,
        Float.NaN,
        FollowState.IDENTITY_UNCERTAIN,
        BehaviorAction.MOTION_STOP,
        personVisible);
  }

  private static FollowStateMachine.FrameResult frame(
      float turn, float heightScale, FollowState state, BehaviorAction action) {
    return frame(turn, heightScale, state, action, false);
  }

  private static FollowStateMachine.FrameResult frame(
      float turn,
      float heightScale,
      FollowState state,
      BehaviorAction action,
      boolean personVisible) {
    List<Detector.Recognition> persons = new ArrayList<>();
    if (personVisible) {
      persons.add(new Detector.Recognition("1", "person", 0.9f, new RectF(1, 1, 10, 20), 0));
    }
    FollowStateMachine.FrameResult frame =
        new FollowStateMachine.FrameResult(
            state,
            new Control(0.6f - turn, 0.6f + turn),
            null,
            null,
            persons,
            true,
            false,
            null,
            -1);
    DistanceState distanceState =
        Float.isNaN(heightScale)
            ? DistanceState.UNKNOWN
            : heightScale < 0.85f ? DistanceState.TOO_FAR : DistanceState.OK;
    frame.distanceEstimate =
        new ImageSetpointDistanceEstimator.DistanceEstimate(
            heightScale, heightScale, 0f, distanceState, 1f, null);
    String reason = state == FollowState.IDENTITY_UNCERTAIN ? "identity_uncertain" : "test";
    frame.behaviorDecision = new BehaviorDecisionResult(state, action, reason, null, 1f);
    return frame;
  }
}
