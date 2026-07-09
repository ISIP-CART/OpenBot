package org.openbot.cartfollow;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openbot.tflite.Detector.Recognition;

public class TargetTrackManager {
  private static final float CENTER_GATE_RATIO = 0.25f;
  private static final float AREA_RATIO_MIN = 0.50f;
  private static final float AREA_RATIO_MAX = 2.00f;
  private static final float IOU_SOFT_GATE = 0.10f;
  private static final int MAX_MISSED_FRAMES = 16;
  private static final long LOCKED_GHOST_TTL_MS = 3000L;
  private static final int SUSPECTED_MIN_DWELL_FRAMES = 3;
  private static final int SUSPECTED_REPLACEMENT_STABLE_FRAMES = 2;
  private static final float SWITCH_BELIEF_DELTA = 0.15f;

  private final List<TargetTrack> tracks = new ArrayList<>();
  private int nextTrackId = 1;
  private int lockedTrackId = -1;
  private int suspectedTrackId = -1;
  private int suspectedDwellFrames = 0;
  private float suspectedBelief = 0f;
  private String suspectedUpdateReason = "none";
  private RectF lockedGhostBbox = null;
  private float lockedGhostCenterX = 0f;
  private float lockedGhostCenterY = 0f;
  private float lockedGhostVelocityX = 0f;
  private float lockedGhostVelocityY = 0f;
  private long lockedGhostLastSeenMs = 0L;
  private String lockedGhostLostSide = "unknown";

  public void reset() {
    tracks.clear();
    nextTrackId = 1;
    lockedTrackId = -1;
    suspectedTrackId = -1;
    suspectedDwellFrames = 0;
    suspectedBelief = 0f;
    suspectedUpdateReason = "reset";
    lockedGhostBbox = null;
    lockedGhostCenterX = 0f;
    lockedGhostCenterY = 0f;
    lockedGhostVelocityX = 0f;
    lockedGhostVelocityY = 0f;
    lockedGhostLastSeenMs = 0L;
    lockedGhostLostSide = "unknown";
  }

  public void update(List<Recognition> detections, int frameW, int frameH, long timestampMs) {
    List<Recognition> safeDetections = detections == null ? new ArrayList<>() : detections;
    Set<TargetTrack> matchedTracks = new HashSet<>();
    Set<Recognition> matchedDetections = new HashSet<>();

    for (Recognition detection : safeDetections) {
      if (detection == null || detection.getLocation() == null) continue;
      Match best = null;
      for (TargetTrack track : tracks) {
        if (track.lastBbox == null || matchedTracks.contains(track)) continue;
        Match match = score(track, detection, frameW, frameH);
        if (!match.accepted) continue;
        if (best == null || match.score > best.score) best = match;
      }
      if (best != null) {
        best.track.update(detection, timestampMs, best.reason);
        matchedTracks.add(best.track);
        matchedDetections.add(detection);
      }
    }

    for (TargetTrack track : new ArrayList<>(tracks)) {
      if (!matchedTracks.contains(track)) track.markMissed();
      if (track.missedFrames > MAX_MISSED_FRAMES) tracks.remove(track);
    }

    for (Recognition detection : safeDetections) {
      if (detection == null || detection.getLocation() == null || matchedDetections.contains(detection)) {
        continue;
      }
      tracks.add(new TargetTrack(nextTrackId++, detection, timestampMs));
    }

    TargetTrack lockedTrack = getLockedTrack();
    if (lockedTrack != null && lockedTrack.isVisible()) {
      updateLockedGhost(lockedTrack, frameW, timestampMs);
    }
  }

  public List<TargetTrack> getTracks() {
    return new ArrayList<>(tracks);
  }

  public int getActiveTrackCount() {
    int count = 0;
    for (TargetTrack track : tracks) {
      if (track.isVisible()) count++;
    }
    return count;
  }

  public TargetTrack getTrackForRecognition(Recognition recognition) {
    if (recognition == null) return null;
    for (TargetTrack track : tracks) {
      if (track.recognition == recognition) return track;
    }
    return null;
  }

  public TargetTrack getLockedTrack() {
    return getTrackById(lockedTrackId);
  }

  public TargetTrack getTrackById(int trackId) {
    if (trackId < 0) return null;
    for (TargetTrack track : tracks) {
      if (track.trackId == trackId) return track;
    }
    return null;
  }

  public int getLockedTrackId() {
    return lockedTrackId;
  }

  public int getSuspectedTrackId() {
    return suspectedTrackId;
  }

  public void setSuspectedTrackId(int suspectedTrackId) {
    this.suspectedTrackId = suspectedTrackId;
    this.suspectedDwellFrames = suspectedTrackId < 0 ? 0 : 1;
    this.suspectedBelief = 0f;
    this.suspectedUpdateReason = "forced";
  }

  public int getSuspectedDwellFrames() {
    return suspectedDwellFrames;
  }

  public String getSuspectedUpdateReason() {
    return suspectedUpdateReason;
  }

  public boolean updateSuspectedTrack(int candidateTrackId, float candidateBelief) {
    if (candidateTrackId < 0) {
      suspectedTrackId = -1;
      suspectedDwellFrames = 0;
      suspectedBelief = 0f;
      suspectedUpdateReason = "clear";
      return true;
    }
    if (candidateTrackId == suspectedTrackId) {
      suspectedDwellFrames++;
      suspectedBelief = Math.max(suspectedBelief, candidateBelief);
      suspectedUpdateReason = "suspected_dwell_keep";
      return true;
    }
    TargetTrack candidate = getTrackById(candidateTrackId);
    TargetTrack current = getTrackById(suspectedTrackId);
    if (suspectedTrackId < 0 || current == null) {
      suspectedTrackId = candidateTrackId;
      suspectedDwellFrames = 1;
      suspectedBelief = candidateBelief;
      suspectedUpdateReason = "suspected_init";
      return true;
    }
    boolean candidateStable =
        candidate != null && candidate.isVisible() && candidate.stableFrames >= SUSPECTED_REPLACEMENT_STABLE_FRAMES;
    boolean clearlyBetter = candidateBelief >= suspectedBelief + SWITCH_BELIEF_DELTA;
    boolean currentWeak = !current.isVisible() || current.missedFrames > 0;
    boolean dwellSatisfied = suspectedDwellFrames >= SUSPECTED_MIN_DWELL_FRAMES;
    if (candidateStable && clearlyBetter && (currentWeak || dwellSatisfied)) {
      suspectedTrackId = candidateTrackId;
      suspectedDwellFrames = 1;
      suspectedBelief = candidateBelief;
      suspectedUpdateReason = "suspected_switch_clear_better";
      return true;
    }
    suspectedDwellFrames++;
    suspectedUpdateReason = "suspected_dwell_hold";
    return false;
  }

  public boolean hasLockedGhost(long nowMs) {
    return lockedGhostBbox != null && nowMs - lockedGhostLastSeenMs <= LOCKED_GHOST_TTL_MS;
  }

  public RectF getLockedGhostBbox(long nowMs) {
    return hasLockedGhost(nowMs) ? new RectF(lockedGhostBbox) : null;
  }

  public String getLockedGhostLostSide(long nowMs) {
    return hasLockedGhost(nowMs) ? lockedGhostLostSide : "unknown";
  }

  public boolean isNearLockedGhost(TargetTrack track, int frameW, int frameH, long nowMs) {
    if (track == null || track.lastBbox == null || !hasLockedGhost(nowMs)) return false;
    RectF ghost = predictedLockedGhost(nowMs);
    float diag = (float) Math.hypot(Math.max(1, frameW), Math.max(1, frameH));
    float centerJump = centerDistance(track.lastBbox, ghost) / Math.max(1f, diag);
    float areaRatio = area(track.lastBbox) / Math.max(1f, area(ghost));
    return centerJump <= BboxContinuityEvidence.LOOSE_CENTER_MAX
        && areaRatio >= BboxContinuityEvidence.LOOSE_AREA_MIN
        && areaRatio <= BboxContinuityEvidence.LOOSE_AREA_MAX;
  }

  public int lockClosest(RectF reference) {
    TargetTrack best = null;
    float bestScore = Float.MAX_VALUE;
    for (TargetTrack track : tracks) {
      if (!track.isVisible()) continue;
      float score = reference == null ? -track.area() : centerDistance(track.lastBbox, reference);
      if (best == null || score < bestScore) {
        best = track;
        bestScore = score;
      }
    }
    if (best == null) {
      clearLockedTrack("lock_missing");
      return lockedTrackId;
    }
    lockTrack(best.trackId, "lock");
    return lockedTrackId;
  }

  public boolean lockTrack(int trackId, String reason) {
    TargetTrack track = getTrackById(trackId);
    if (track == null || !track.isVisible()) return false;
    lockedTrackId = trackId;
    suspectedTrackId = -1;
    suspectedDwellFrames = 0;
    suspectedBelief = 0f;
    suspectedUpdateReason = reason == null ? "lock_track" : reason;
    updateLockedGhost(track, 0, track.lastSeenTimestampMs);
    return true;
  }

  private void clearLockedTrack(String reason) {
    lockedTrackId = -1;
    suspectedTrackId = -1;
    suspectedDwellFrames = 0;
    suspectedBelief = 0f;
    suspectedUpdateReason = reason == null ? "clear_lock" : reason;
  }

  public boolean isLockedTrack(TargetTrack track) {
    return track != null && track.trackId == lockedTrackId;
  }

  private static Match score(TargetTrack track, Recognition detection, int frameW, int frameH) {
    RectF current = detection.getLocation();
    RectF last = track.lastBbox;
    float diag = (float) Math.hypot(Math.max(1, frameW), Math.max(1, frameH));
    float centerJump = centerDistance(current, last) / Math.max(1f, diag);
    float areaRatio = area(current) / Math.max(1f, area(last));
    float iou = iou(current, last);
    boolean accepted =
        (centerJump <= CENTER_GATE_RATIO && areaRatio >= AREA_RATIO_MIN && areaRatio <= AREA_RATIO_MAX)
            || iou >= IOU_SOFT_GATE;
    float score = iou * 2.0f - centerJump - Math.abs(1f - areaRatio) * 0.25f;
    String reason =
        String.format(
            java.util.Locale.US, "iou=%.2f center=%.3f area=%.2f", iou, centerJump, areaRatio);
    return new Match(track, accepted, score, reason);
  }

  private static float centerDistance(RectF a, RectF b) {
    if (a == null || b == null) return Float.MAX_VALUE;
    return (float) Math.hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY());
  }

  private static float area(RectF b) {
    return b == null ? 0f : Math.max(0f, b.width()) * Math.max(0f, b.height());
  }

  private void updateLockedGhost(TargetTrack track, int frameW, long timestampMs) {
    if (track == null || track.lastBbox == null) return;
    if (lockedGhostBbox != null) {
      lockedGhostVelocityX = track.lastBbox.centerX() - lockedGhostCenterX;
      lockedGhostVelocityY = track.lastBbox.centerY() - lockedGhostCenterY;
    }
    lockedGhostBbox = new RectF(track.lastBbox);
    lockedGhostCenterX = track.lastBbox.centerX();
    lockedGhostCenterY = track.lastBbox.centerY();
    lockedGhostLastSeenMs = timestampMs;
    if (frameW > 0) {
      lockedGhostLostSide = lockedGhostCenterX < frameW / 2f ? "left" : "right";
    }
  }

  private RectF predictedLockedGhost(long nowMs) {
    RectF ghost = new RectF(lockedGhostBbox);
    float steps = Math.min(3f, Math.max(0f, (nowMs - lockedGhostLastSeenMs) / 250f));
    ghost.offset(lockedGhostVelocityX * steps, lockedGhostVelocityY * steps);
    return ghost;
  }

  private static float iou(RectF a, RectF b) {
    if (a == null || b == null) return 0f;
    float left = Math.max(a.left, b.left);
    float top = Math.max(a.top, b.top);
    float right = Math.min(a.right, b.right);
    float bottom = Math.min(a.bottom, b.bottom);
    float inter = Math.max(0f, right - left) * Math.max(0f, bottom - top);
    float union = area(a) + area(b) - inter;
    return union <= 0f ? 0f : inter / union;
  }

  private static class Match {
    final TargetTrack track;
    final boolean accepted;
    final float score;
    final String reason;

    Match(TargetTrack track, boolean accepted, float score, String reason) {
      this.track = track;
      this.accepted = accepted;
      this.score = score;
      this.reason = reason;
    }
  }
}
