package org.openbot.cartfollow;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded controller that backs up only after the confirmed person is stable. */
public final class InitializationPositioningController {
  public static final int REVERSE_GEAR = 8;
  public static final long REVERSE_LIMIT_MS = 6000L;
  public static final long SETTLE_MS = 200L;
  static final int STABLE_FRAMES = 3;
  static final long STABLE_SPAN_MS = 500L;
  static final float MAX_CENTER_SPAN = .04f;
  static final float MAX_SIZE_VARIATION = .10f;

  private static final class Sample {
    final long at;
    final float cx, cy, width, height;
    Sample(long at, RectF box) {
      this.at = at; cx = box.centerX(); cy = box.centerY(); width = box.width(); height = box.height();
    }
  }

  private final List<Sample> samples = new ArrayList<>();
  private InitializationPositioningEvidence.Phase phase = InitializationPositioningEvidence.Phase.WAIT_STABLE;
  private int activeTrackId = -1;
  private long reverseSegmentStartedAtMs = -1L;
  private long reverseStartedAtMs = -1L;
  private long reverseEndedAtMs = -1L;
  private long accumulatedReverseMs;
  private long settleStartedAtMs = -1L;
  private long fullBodyObservedAtMs = -1L;

  public void reset() {
    samples.clear(); phase = InitializationPositioningEvidence.Phase.WAIT_STABLE;
    activeTrackId = -1; reverseSegmentStartedAtMs = -1L; accumulatedReverseMs = 0L;
    reverseStartedAtMs = -1L; reverseEndedAtMs = -1L;
    settleStartedAtMs = -1L; fullBodyObservedAtMs = -1L;
  }

  public InitializationPositioningEvidence update(
      RectF rawBox, int trackId, boolean unique, int confirmedTrackId,
      int width, int height, int orientation, long nowMs) {
    InitializationFraming.Result framing = InitializationFraming.evaluate(rawBox, width, height, orientation);
    boolean matching = confirmedTrackId < 0 || trackId >= 0 && trackId == confirmedTrackId;
    boolean usable = framing.valid && unique && matching;

    if (phase == InitializationPositioningEvidence.Phase.TIMEOUT)
      return evidence(framing, trackId, nowMs, "reverse_timeout");

    if (!usable) {
      endReverse(nowMs);
      phase = InitializationPositioningEvidence.Phase.WAIT_STABLE;
      settleStartedAtMs = -1L;
      samples.clear();
      String reason = !framing.valid ? "target_missing" : !matching ? "different_track" : "competing_person";
      return evidence(framing, trackId, nowMs, reason);
    }

    if (phase == InitializationPositioningEvidence.Phase.REVERSING) {
      if (reverseElapsed(nowMs) >= REVERSE_LIMIT_MS) {
        endReverse(nowMs); phase = InitializationPositioningEvidence.Phase.TIMEOUT;
        return evidence(framing, trackId, nowMs, "reverse_timeout");
      }
      if (framing.fullBody()) {
        fullBodyObservedAtMs = nowMs;
        endReverse(nowMs);
        phase = InitializationPositioningEvidence.Phase.SETTLING;
        settleStartedAtMs = nowMs;
        return evidence(framing, trackId, nowMs, "full_body_stop");
      }
      return evidence(framing, trackId, nowMs, "person_clipped_" + framing.clippedEdges());
    }

    if (phase == InitializationPositioningEvidence.Phase.SETTLING) {
      if (!framing.fullBody()) {
        phase = InitializationPositioningEvidence.Phase.WAIT_STABLE;
        settleStartedAtMs = -1L; samples.clear();
        return evidence(framing, trackId, nowMs, "clipped_during_settle");
      }
      if (nowMs - settleStartedAtMs >= SETTLE_MS) {
        phase = InitializationPositioningEvidence.Phase.READY;
        return evidence(framing, trackId, nowMs, "settled_ready");
      }
      return evidence(framing, trackId, nowMs, "settling");
    }

    if (activeTrackId != trackId) { samples.clear(); activeTrackId = trackId; }
    addStableSample(nowMs, framing.screenBox);
    if (!stable(nowMs)) return evidence(framing, trackId, nowMs, "waiting_stable");

    if (framing.fullBody()) {
      fullBodyObservedAtMs = nowMs;
      phase = InitializationPositioningEvidence.Phase.SETTLING;
      settleStartedAtMs = nowMs;
      return evidence(framing, trackId, nowMs, "full_body_already_visible");
    }
    phase = InitializationPositioningEvidence.Phase.REVERSING;
    reverseSegmentStartedAtMs = nowMs;
    if (reverseStartedAtMs < 0L) reverseStartedAtMs = nowMs;
    return evidence(framing, trackId, nowMs, "stable_start_reverse");
  }

  private void addStableSample(long nowMs, RectF box) {
    samples.add(new Sample(nowMs, box));
    while (samples.size() > 120) samples.remove(0);
    while (samples.size() > 1 && !withinLimits()) samples.remove(0);
  }

  private boolean stable(long nowMs) {
    return samples.size() >= STABLE_FRAMES
        && nowMs - samples.get(0).at >= STABLE_SPAN_MS && withinLimits();
  }

  private boolean withinLimits() {
    if (samples.isEmpty()) return false;
    return span(0) <= MAX_CENTER_SPAN && span(1) <= MAX_CENTER_SPAN
        && Math.max(relativeVariation(2), relativeVariation(3)) <= MAX_SIZE_VARIATION;
  }

  private float span(int field) {
    float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
    for (Sample s : samples) {
      float v = field == 0 ? s.cx : s.cy;
      min = Math.min(min, v); max = Math.max(max, v);
    }
    return max - min;
  }

  private float relativeVariation(int field) {
    List<Float> values = new ArrayList<>();
    float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
    for (Sample s : samples) {
      float value = field == 2 ? s.width : s.height;
      values.add(value); min = Math.min(min, value); max = Math.max(max, value);
    }
    Collections.sort(values);
    float median = values.get(values.size() / 2);
    return median <= 0f ? Float.MAX_VALUE : (max - min) / median;
  }

  private void endReverse(long nowMs) {
    if (reverseSegmentStartedAtMs >= 0L) {
      accumulatedReverseMs += Math.max(0L, nowMs - reverseSegmentStartedAtMs);
      reverseSegmentStartedAtMs = -1L;
      reverseEndedAtMs = nowMs;
    }
  }

  private long reverseElapsed(long nowMs) {
    return accumulatedReverseMs + (reverseSegmentStartedAtMs < 0L ? 0L : Math.max(0L, nowMs - reverseSegmentStartedAtMs));
  }

  private InitializationPositioningEvidence evidence(
      InitializationFraming.Result framing, int trackId, long nowMs, String reason) {
    long spanMs = samples.isEmpty() ? 0L : Math.max(0L, nowMs - samples.get(0).at);
    return new InitializationPositioningEvidence(
        phase, trackId, samples.size(), spanMs, samples.isEmpty() ? 0f : span(0),
        samples.isEmpty() ? 0f : span(1), samples.isEmpty() ? 0f : Math.max(relativeVariation(2), relativeVariation(3)),
        framing, reverseElapsed(nowMs), reverseStartedAtMs, reverseEndedAtMs,
        fullBodyObservedAtMs, reason);
  }
}
