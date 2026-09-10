package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.openbot.vehicle.Control;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InitializationPositioningControllerTest {
  private static final int W = 100;
  private static final int H = 200;

  @Test
  public void framingUsesSameOnePercentMarginsInAllOrientations() {
    for (int orientation : new int[] {0, 90, 180, 270}) {
      assertTrue(framing(new RectF(.02f, .02f, .98f, .98f), orientation).fullBody());
      assertFalse(framing(new RectF(.009f, .02f, .98f, .98f), orientation).fullBody());
      assertFalse(framing(new RectF(.02f, .009f, .98f, .98f), orientation).fullBody());
      assertFalse(framing(new RectF(.02f, .02f, .991f, .98f), orientation).fullBody());
      assertFalse(framing(new RectF(.02f, .02f, .98f, .991f), orientation).fullBody());
    }
  }

  @Test
  public void stableClippedPersonReversesThenStopsAndSettles() {
    InitializationPositioningController controller = new InitializationPositioningController();
    RectF clipped = fromScreen(new RectF(.2f, 0f, .8f, .95f), 0);
    assertEquals(InitializationPositioningEvidence.Phase.WAIT_STABLE, update(controller, clipped, 0).phase);
    assertEquals(InitializationPositioningEvidence.Phase.WAIT_STABLE, update(controller, clipped, 250).phase);
    assertEquals(InitializationPositioningEvidence.Phase.REVERSING, update(controller, clipped, 500).phase);

    RectF complete = fromScreen(new RectF(.1f, .02f, .9f, .98f), 0);
    InitializationPositioningEvidence stopped = update(controller, complete, 550);
    assertEquals(InitializationPositioningEvidence.Phase.SETTLING, stopped.phase);
    assertFalse(stopped.shouldReverse());
    assertEquals(InitializationPositioningEvidence.Phase.READY, update(controller, complete, 750).phase);
  }

  @Test
  public void movementCompetitionAndTrackChangesCannotStartOrMaintainReverse() {
    InitializationPositioningController controller = new InitializationPositioningController();
    RectF clipped = fromScreen(new RectF(.2f, 0f, .8f, .95f), 0);
    update(controller, clipped, 0);
    update(controller, fromScreen(new RectF(.3f, 0f, .9f, .95f), 0), 250);
    assertEquals(InitializationPositioningEvidence.Phase.WAIT_STABLE, update(controller, clipped, 500).phase);
    update(controller, clipped, 750);
    assertEquals(InitializationPositioningEvidence.Phase.REVERSING, update(controller, clipped, 1000).phase);
    assertEquals(
        InitializationPositioningEvidence.Phase.WAIT_STABLE,
        controller.update(clipped, 1, false, 1, W, H, 0, 1050).phase);
    assertEquals(
        InitializationPositioningEvidence.Phase.WAIT_STABLE,
        controller.update(clipped, 2, true, 1, W, H, 0, 1100).phase);
  }

  @Test
  public void reverseHasCumulativeSixSecondLimit() {
    InitializationPositioningController controller = new InitializationPositioningController();
    RectF clipped = fromScreen(new RectF(.2f, 0f, .8f, .95f), 0);
    update(controller, clipped, 0);
    update(controller, clipped, 250);
    assertEquals(InitializationPositioningEvidence.Phase.REVERSING, update(controller, clipped, 500).phase);
    assertEquals(InitializationPositioningEvidence.Phase.TIMEOUT, update(controller, clipped, 6500).phase);
  }

  @Test
  public void simulatorUsesSameFixedReverseAndStopsOnStaleFrame() {
    FollowStateMachine.FrameResult frame = new FollowStateMachine.FrameResult(
        FollowState.AUTO_POSITIONING, new Control(0, 0), null, null,
        Collections.emptyList(), false, false, null, -1);
    frame.behaviorDecision = new BehaviorDecisionResult(
        FollowState.AUTO_POSITIONING, BehaviorAction.INITIALIZATION_REVERSE,
        "stable_start_reverse", null, 1f);
    frame.frameTiming = new FrameTimingEvidence(1000L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    SimulatorAutoDriveController controller = new SimulatorAutoDriveController();
    SimulatorAutoDriveController.Result moving = controller.update(frame, 1000L);
    assertEquals(SimulatorAutoDriveController.Phase.INITIALIZATION_REVERSE, moving.phase);
    assertEquals(-8, moving.left);
    assertEquals(-8, moving.right);
    assertEquals(0, controller.update(frame, 1401L).left);
  }

  private static InitializationPositioningEvidence update(
      InitializationPositioningController controller, RectF box, long now) {
    return controller.update(box, 1, true, 1, W, H, 0, now);
  }

  private static InitializationFraming.Result framing(RectF screen, int orientation) {
    return InitializationFraming.evaluate(fromScreen(screen, orientation), W, H, orientation);
  }

  private static RectF fromScreen(RectF screen, int orientation) {
    switch (orientation) {
      case 90:
        return new RectF(
            screen.top * W,
            (1f - screen.right) * H,
            screen.bottom * W,
            (1f - screen.left) * H);
      case 180:
        return new RectF(
            (1f - screen.right) * W,
            (1f - screen.bottom) * H,
            (1f - screen.left) * W,
            (1f - screen.top) * H);
      case 270:
        return new RectF(
            (1f - screen.bottom) * W,
            screen.left * H,
            (1f - screen.top) * W,
            screen.right * H);
      default:
        return new RectF(screen.left * W, screen.top * H, screen.right * W, screen.bottom * H);
    }
  }
}
