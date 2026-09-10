package org.openbot.cartfollow;

import android.graphics.RectF;

/** Explainable snapshot of the automatic initialization positioning controller. */
public final class InitializationPositioningEvidence {
  public enum Phase { WAIT_STABLE, REVERSING, SETTLING, READY, TIMEOUT }

  public final Phase phase;
  public final int trackId;
  public final int stableFrames;
  public final long stableSpanMs;
  public final float centerSpanX;
  public final float centerSpanY;
  public final float sizeVariation;
  public final RectF screenBox;
  public final boolean clippedLeft, clippedTop, clippedRight, clippedBottom;
  public final long reverseElapsedMs;
  public final long reverseStartedAtMs;
  public final long reverseEndedAtMs;
  public final long fullBodyObservedAtMs;
  public final String reason;

  InitializationPositioningEvidence(
      Phase phase, int trackId, int stableFrames, long stableSpanMs, float centerSpanX,
      float centerSpanY, float sizeVariation, InitializationFraming.Result framing,
      long reverseElapsedMs, long reverseStartedAtMs, long reverseEndedAtMs,
      long fullBodyObservedAtMs, String reason) {
    this.phase = phase;
    this.trackId = trackId;
    this.stableFrames = stableFrames;
    this.stableSpanMs = stableSpanMs;
    this.centerSpanX = centerSpanX;
    this.centerSpanY = centerSpanY;
    this.sizeVariation = sizeVariation;
    screenBox = framing == null || framing.screenBox == null ? null : new RectF(framing.screenBox);
    clippedLeft = framing == null || framing.clippedLeft;
    clippedTop = framing == null || framing.clippedTop;
    clippedRight = framing == null || framing.clippedRight;
    clippedBottom = framing == null || framing.clippedBottom;
    this.reverseElapsedMs = reverseElapsedMs;
    this.reverseStartedAtMs = reverseStartedAtMs;
    this.reverseEndedAtMs = reverseEndedAtMs;
    this.fullBodyObservedAtMs = fullBodyObservedAtMs;
    this.reason = reason;
  }

  public boolean shouldReverse() { return phase == Phase.REVERSING; }
  public static InitializationPositioningEvidence stage(Phase phase, String reason) {
    return new InitializationPositioningEvidence(
        phase, -1, 0, 0, 0, 0, 0, null, 0, -1, -1, -1, reason);
  }
  public boolean fullBody() {
    return screenBox != null && !clippedLeft && !clippedTop && !clippedRight && !clippedBottom;
  }
}
