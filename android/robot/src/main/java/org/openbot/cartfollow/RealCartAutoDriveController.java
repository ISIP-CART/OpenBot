package org.openbot.cartfollow;

import org.openbot.vehicle.Control;

/** Converts shared follow decisions into the small command set allowed on the real cart. */
public final class RealCartAutoDriveController {
  public enum Phase {
    LOCKED,
    WAIT_TARGET,
    WAIT_CENTER,
    MOVING_STRAIGHT,
    CURVE_LEFT,
    CURVE_RIGHT,
    RECOVERY_STOP
  }

  public static final int STRAIGHT_SPEED = 14;
  public static final int CURVE_INNER_SPEED = 12;
  public static final int CURVE_OUTER_SPEED = 14;
  public static final float FILTER_ALPHA = 0.25f;
  public static final float CENTER_TURN_LIMIT = 0.15f;
  public static final float CURVE_TURN_LIMIT = 0.45f;
  public static final float START_HEIGHT_SCALE = 0.80f;
  public static final int START_STABLE_FRAMES = 3;
  public static final long RECOVERY_LIMIT_MS = 2000L;

  public static final class Result {
    public final int left;
    public final int right;
    public final Phase phase;
    public final String reason;
    public final float rawTurn;
    public final float filteredTurn;
    public final float heightScale;
    public final boolean lockout;

    private Result(
        int left,
        int right,
        Phase phase,
        String reason,
        float rawTurn,
        float filteredTurn,
        float heightScale,
        boolean lockout) {
      this.left = clamp(left);
      this.right = clamp(right);
      this.phase = phase;
      this.reason = reason;
      this.rawTurn = rawTurn;
      this.filteredTurn = filteredTurn;
      this.heightScale = heightScale;
      this.lockout = lockout;
    }

    public boolean isStop() {
      return left == 0 && right == 0;
    }

    private static int clamp(int value) {
      return Math.max(0, Math.min(RealCartSafetyController.AUTO_MAX, value));
    }
  }

  private boolean moving;
  private boolean hasFilteredTurn;
  private float filteredTurn;
  private int centeredFrames;
  private long recoveryStartMs = -1L;
  private Result lastResult = stopped(Phase.LOCKED, "auto_locked", false, 0f, 0f, Float.NaN);

  public synchronized Result update(FollowStateMachine.FrameResult frame, long nowMs) {
    if (frame == null || frame.behaviorDecision == null) {
      return stopNonRecovery(Phase.WAIT_TARGET, "decision_missing", 0f, Float.NaN);
    }

    BehaviorDecisionResult decision = frame.behaviorDecision;
    float heightScale =
        frame.distanceEstimate == null ? Float.NaN : frame.distanceEstimate.heightScale;
    float rawTurn = turnFrom(frame.control);

    if (isRecoveryDecision(frame, decision)) {
      return recoveryStop(nowMs, rawTurn, heightScale, decision.actionReason);
    }

    recoveryStartMs = -1L;
    if (decision.selectedAction != BehaviorAction.FOLLOW_SLOW
        || frame.state != FollowState.FOLLOW
        || frame.distanceEstimate == null
        || frame.distanceEstimate.state != DistanceState.TOO_FAR
        || !isFinite(heightScale)) {
      return stopNonRecovery(
          Phase.WAIT_TARGET, decision.selectedAction.name().toLowerCase(), rawTurn, heightScale);
    }

    updateFilteredTurn(rawTurn);
    float absTurn = Math.abs(filteredTurn);
    if (absTurn > CURVE_TURN_LIMIT) {
      return stopNonRecovery(Phase.WAIT_CENTER, "target_too_far_off_center", rawTurn, heightScale);
    }

    if (!moving) {
      if (heightScale <= START_HEIGHT_SCALE && absTurn <= CENTER_TURN_LIMIT) {
        centeredFrames++;
      } else {
        centeredFrames = 0;
      }
      if (centeredFrames < START_STABLE_FRAMES) {
        return stopped(
            Phase.WAIT_CENTER,
            heightScale > START_HEIGHT_SCALE ? "target_not_far_enough" : "center_stabilizing",
            false,
            rawTurn,
            filteredTurn,
            heightScale);
      }
      moving = true;
      centeredFrames = 0;
      return remember(
          new Result(
              STRAIGHT_SPEED,
              STRAIGHT_SPEED,
              Phase.MOVING_STRAIGHT,
              "centered_follow",
              rawTurn,
              filteredTurn,
              heightScale,
              false));
    }

    if (absTurn <= CENTER_TURN_LIMIT) {
      return remember(
          new Result(
              STRAIGHT_SPEED,
              STRAIGHT_SPEED,
              Phase.MOVING_STRAIGHT,
              "centered_follow",
              rawTurn,
              filteredTurn,
              heightScale,
              false));
    }

    // Preserve ControlGenerator's verified wheel relationship without allowing either wheel to
    // reverse.
    boolean leftWheelSlower = filteredTurn > 0f;
    return remember(
        new Result(
            leftWheelSlower ? CURVE_INNER_SPEED : CURVE_OUTER_SPEED,
            leftWheelSlower ? CURVE_OUTER_SPEED : CURVE_INNER_SPEED,
            leftWheelSlower ? Phase.CURVE_LEFT : Phase.CURVE_RIGHT,
            "gentle_curve",
            rawTurn,
            filteredTurn,
            heightScale,
            false));
  }

  public synchronized Result reset(String reason) {
    moving = false;
    hasFilteredTurn = false;
    filteredTurn = 0f;
    centeredFrames = 0;
    recoveryStartMs = -1L;
    return remember(stopped(Phase.LOCKED, reason, false, 0f, 0f, Float.NaN));
  }

  public synchronized Result getLastResult() {
    return lastResult;
  }

  private Result recoveryStop(long nowMs, float rawTurn, float heightScale, String reason) {
    moving = false;
    centeredFrames = 0;
    if (recoveryStartMs < 0L) recoveryStartMs = nowMs;
    boolean lockout = nowMs - recoveryStartMs >= RECOVERY_LIMIT_MS;
    if (lockout) {
      return remember(
          stopped(Phase.LOCKED, "recovery_timeout", true, rawTurn, filteredTurn, heightScale));
    }
    return remember(
        stopped(
            Phase.RECOVERY_STOP,
            reason == null ? "target_recovery" : reason,
            false,
            rawTurn,
            filteredTurn,
            heightScale));
  }

  private Result stopNonRecovery(Phase phase, String reason, float rawTurn, float heightScale) {
    moving = false;
    centeredFrames = 0;
    return remember(stopped(phase, reason, false, rawTurn, filteredTurn, heightScale));
  }

  private void updateFilteredTurn(float rawTurn) {
    if (!hasFilteredTurn) {
      filteredTurn = rawTurn;
      hasFilteredTurn = true;
    } else {
      filteredTurn = FILTER_ALPHA * rawTurn + (1f - FILTER_ALPHA) * filteredTurn;
    }
  }

  private Result remember(Result result) {
    lastResult = result;
    return result;
  }

  private static Result stopped(
      Phase phase,
      String reason,
      boolean lockout,
      float rawTurn,
      float filteredTurn,
      float heightScale) {
    return new Result(0, 0, phase, reason, rawTurn, filteredTurn, heightScale, lockout);
  }

  private static boolean isRecoveryDecision(
      FollowStateMachine.FrameResult frame, BehaviorDecisionResult decision) {
    if (frame.state == FollowState.IDENTITY_UNCERTAIN
        || frame.state == FollowState.LOST
        || frame.state == FollowState.SEARCH
        || decision.selectedAction == BehaviorAction.LOCAL_SEARCH_LEFT
        || decision.selectedAction == BehaviorAction.LOCAL_SEARCH_RIGHT) {
      return true;
    }
    return decision.selectedAction == BehaviorAction.MOTION_STOP
        && decision.actionReason != null
        && (decision.actionReason.startsWith("identity_")
            || decision.actionReason.startsWith("target_lost"));
  }

  private static float turnFrom(Control control) {
    return control == null ? 0f : (control.getRight() - control.getLeft()) / 2f;
  }

  private static boolean isFinite(float value) {
    return !Float.isNaN(value) && !Float.isInfinite(value);
  }
}
