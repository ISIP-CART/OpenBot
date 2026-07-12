package org.openbot.cartfollow;

import org.openbot.vehicle.Control;

/** Pure safety gate that converts UI or behavior decisions into bounded protocol commands. */
public final class RealCartSafetyController {
  public enum Mode {
    MANUAL,
    AUTO
  }

  public static final int MANUAL_FORWARD = 28;
  public static final int MANUAL_REVERSE = 24;
  public static final int MANUAL_TURN = 20;
  public static final int AUTO_MAX = 32;
  public static final int SEARCH_SPEED = 18;
  public static final long INFERENCE_TIMEOUT_MS = 400L;
  public static final long SEARCH_LIMIT_MS = 2000L;

  public static final class Output {
    public final int left;
    public final int right;
    public final String reason;

    private Output(int left, int right, String reason) {
      this.left = left;
      this.right = right;
      this.reason = reason;
    }

    public boolean isStop() {
      return left == 0 && right == 0;
    }
  }

  private Mode mode = Mode.MANUAL;
  private boolean foreground;
  private boolean connected;
  private boolean firmwareReady;
  private boolean autoUnlocked;
  private boolean emergencyLatched;
  private long lastInferenceMs = -1L;
  private long searchStartMs = -1L;

  public synchronized void setForeground(boolean foreground) {
    this.foreground = foreground;
    if (!foreground) autoUnlocked = false;
  }

  public synchronized void setConnection(boolean connected, boolean firmwareReady) {
    this.connected = connected;
    this.firmwareReady = firmwareReady;
    if (!connected || !firmwareReady) autoUnlocked = false;
  }

  public synchronized void setMode(Mode mode) {
    this.mode = mode;
    autoUnlocked = false;
    searchStartMs = -1L;
    lastInferenceMs = -1L;
  }

  public synchronized Mode getMode() {
    return mode;
  }

  public synchronized boolean unlockAuto() {
    autoUnlocked =
        mode == Mode.AUTO && foreground && connected && firmwareReady && !emergencyLatched;
    return autoUnlocked;
  }

  public synchronized boolean isAutoUnlocked() {
    return autoUnlocked;
  }

  public synchronized void latchEmergency() {
    emergencyLatched = true;
    autoUnlocked = false;
  }

  public synchronized boolean isEmergencyLatched() {
    return emergencyLatched;
  }

  public synchronized Output manual(int left, int right) {
    if (!canMove() || mode != Mode.MANUAL) return stop("manual_blocked");
    return new Output(left, right, "manual");
  }

  public synchronized Output auto(FollowStateMachine.FrameResult frame, long nowMs) {
    lastInferenceMs = nowMs;
    if (!canMove() || mode != Mode.AUTO || !autoUnlocked || frame == null) {
      return stop("auto_blocked");
    }

    BehaviorDecisionResult decision = frame.behaviorDecision;
    if (decision == null) return stop("decision_missing");

    switch (decision.selectedAction) {
      case FOLLOW_SLOW:
        searchStartMs = -1L;
        return scale(frame.control, AUTO_MAX, "follow_slow");
      case FOLLOW_CAUTION:
        searchStartMs = -1L;
        return scale(frame.control, Math.round(AUTO_MAX * 0.65f), "follow_caution");
      case LOCAL_SEARCH_LEFT:
      case LOCAL_SEARCH_RIGHT:
        if (searchStartMs < 0L) searchStartMs = nowMs;
        if (nowMs - searchStartMs > SEARCH_LIMIT_MS) {
          autoUnlocked = false;
          return stop("search_timeout");
        }
        return decision.selectedAction == BehaviorAction.LOCAL_SEARCH_LEFT
            ? new Output(-SEARCH_SPEED, SEARCH_SPEED, "search_left")
            : new Output(SEARCH_SPEED, -SEARCH_SPEED, "search_right");
      default:
        searchStartMs = -1L;
        return stop(decision.selectedAction.name().toLowerCase());
    }
  }

  public synchronized Output watchdog(long nowMs) {
    if (mode == Mode.AUTO
        && autoUnlocked
        && (lastInferenceMs < 0L || nowMs - lastInferenceMs > INFERENCE_TIMEOUT_MS)) {
      autoUnlocked = false;
      return stop("inference_timeout");
    }
    return null;
  }

  public static Output stop(String reason) {
    return new Output(0, 0, reason);
  }

  private boolean canMove() {
    return foreground && connected && firmwareReady && !emergencyLatched;
  }

  private static Output scale(Control control, int maxAbs, String reason) {
    if (control == null) return stop("control_missing");
    return new Output(
        Math.round(control.getLeft() * maxAbs), Math.round(control.getRight() * maxAbs), reason);
  }
}
