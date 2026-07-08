package org.openbot.cartfollow;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.openbot.tflite.Detector.Recognition;

public class IdentityBeliefAccumulator {
  private final Map<Integer, Float> beliefs = new HashMap<>();
  private final Map<Integer, Integer> stableFrames = new HashMap<>();
  private final Map<Integer, Integer> uncertainFrames = new HashMap<>();

  private int lockedTrackId = -1;
  private int lastSelectedTrackId = -1;
  private int candidateSwitchCount = 0;

  public void reset() {
    beliefs.clear();
    stableFrames.clear();
    uncertainFrames.clear();
    lockedTrackId = -1;
    lastSelectedTrackId = -1;
    candidateSwitchCount = 0;
  }

  public void lockTrack(int trackId) {
    lockedTrackId = trackId;
    if (trackId >= 0) {
      beliefs.put(trackId, IdentityBelief.BELIEF_CONFIRM);
      stableFrames.put(trackId, 1);
      uncertainFrames.put(trackId, 0);
      lastSelectedTrackId = trackId;
    }
  }

  public float getBeliefForTrack(TargetTrack track) {
    if (track == null) return 0f;
    Float value = beliefs.get(track.trackId);
    return value == null ? 0f : value;
  }

  public IdentityEvidence update(
      IdentityEvidence base,
      TargetTrackManager trackManager,
      TargetTrack reidCandidateTrack,
      TargetMemory memory,
      int frameW,
      int frameH) {
    if (trackManager == null) return base;
    int managerLockedId = trackManager.getLockedTrackId();
    if (managerLockedId != lockedTrackId) {
      lockedTrackId = managerLockedId;
      if (lockedTrackId >= 0 && !beliefs.containsKey(lockedTrackId)) {
        beliefs.put(lockedTrackId, IdentityBelief.BELIEF_CONFIRM);
        stableFrames.put(lockedTrackId, 1);
      }
    }

    List<TargetTrack> tracks = trackManager.getTracks();
    TargetTrack lockedTrack = trackManager.getLockedTrack();
    TargetTrack selectedTrack = null;
    IdentityBelief selectedBelief = null;
    BboxContinuityEvidence selectedBbox = null;

    for (TargetTrack track : tracks) {
      IdentityBelief belief =
          updateTrackBelief(base, track, reidCandidateTrack, lockedTrack, memory, frameW, frameH);
      BboxContinuityEvidence bbox = bboxEvidence(track, memory, frameW, frameH);
      if (!track.isVisible()) continue;
      if (selectedTrack == null || shouldSelect(track, belief, selectedTrack, selectedBelief)) {
        selectedTrack = track;
        selectedBelief = belief;
        selectedBbox = bbox;
      }
    }

    if (lockedTrack != null
        && lockedTrack.isVisible()
        && getBeliefForTrack(lockedTrack) >= IdentityBelief.BELIEF_LOST) {
      selectedTrack = lockedTrack;
      selectedBelief =
          new IdentityBelief(
              lockedTrack.trackId,
              getBeliefForTrack(lockedTrack),
              0f,
              0f,
              0f,
              0f,
              getStableFrames(lockedTrack.trackId),
              getUncertainFrames(lockedTrack.trackId),
              "locked_track_priority");
      selectedBbox = bboxEvidence(lockedTrack, memory, frameW, frameH);
    }

    if (selectedTrack == null || selectedBelief == null) {
      trackManager.setSuspectedTrackId(-1);
      return withBelief(
          base,
          null,
          null,
          null,
          -1,
          managerLockedId,
          -1,
          trackManager.getActiveTrackCount(),
          "no_visible_track");
    }

    if (selectedTrack.trackId != lastSelectedTrackId) {
      if (lastSelectedTrackId >= 0) candidateSwitchCount++;
      lastSelectedTrackId = selectedTrack.trackId;
    }

    int suspectedTrackId = -1;
    if (selectedTrack.trackId != managerLockedId
        && selectedBelief.targetBelief >= IdentityBelief.BELIEF_CAUTION) {
      suspectedTrackId = selectedTrack.trackId;
    }
    trackManager.setSuspectedTrackId(suspectedTrackId);

    return withBelief(
        base,
        selectedTrack,
        selectedBbox,
        selectedBelief,
        selectedTrack.trackId,
        managerLockedId,
        suspectedTrackId,
        trackManager.getActiveTrackCount(),
        selectedBelief.beliefReason);
  }

  private IdentityBelief updateTrackBelief(
      IdentityEvidence base,
      TargetTrack track,
      TargetTrack reidCandidateTrack,
      TargetTrack lockedTrack,
      TargetMemory memory,
      int frameW,
      int frameH) {
    float old = getBeliefForTrack(track);
    if (old <= 0f) old = track.trackId == lockedTrackId ? IdentityBelief.BELIEF_CAUTION : 0.08f;

    boolean isLocked = track.trackId == lockedTrackId;
    boolean lockedVisible = lockedTrack != null && lockedTrack.isVisible();
    boolean isReidCandidate = reidCandidateTrack != null && reidCandidateTrack.trackId == track.trackId;

    BboxContinuityEvidence bbox = bboxEvidence(track, memory, frameW, frameH);
    float reidContribution = reidContribution(base, isReidCandidate);
    float bboxContribution = bbox.bboxStrictOk ? 0.15f : (bbox.bboxDefaultOk ? 0.10f : 0f);
    float predictionContribution = bbox.predictionOk ? 0.06f : 0f;
    float lockedContribution = isLocked && track.isVisible() ? 0.08f : 0f;
    float ageContribution = track.ageFrames >= 3 && track.isVisible() ? 0.04f : 0f;
    float switchPenalty =
        isReidCandidate && lastSelectedTrackId >= 0 && track.trackId != lastSelectedTrackId ? 0.12f : 0f;
    float lockedProtectionPenalty =
        !isLocked && lockedVisible && isReidCandidate ? 0.18f : 0f;
    float missedPenalty = Math.min(0.30f, track.missedFrames * 0.08f);
    float weakMarginPenalty =
        isReidCandidate && base != null && base.reidAvailable() && !base.weakOk() ? 0.08f : 0f;

    float decay = track.isVisible() ? 0.80f : 0.70f;
    float positive =
        reidContribution
            + bboxContribution
            + predictionContribution
            + lockedContribution
            + ageContribution;
    float penalty = switchPenalty + lockedProtectionPenalty + missedPenalty + weakMarginPenalty;
    float belief = clamp(old * decay + positive - penalty, 0f, 1f);

    int stable = belief >= IdentityBelief.BELIEF_CAUTION ? getStableFrames(track.trackId) + 1 : 0;
    int uncertain =
        belief < IdentityBelief.BELIEF_CAUTION ? getUncertainFrames(track.trackId) + 1 : 0;
    beliefs.put(track.trackId, belief);
    stableFrames.put(track.trackId, stable);
    uncertainFrames.put(track.trackId, uncertain);

    String reason =
        String.format(
            Locale.US,
            "belief=%.2f old=%.2f reid=%.2f bbox=%.2f pred=%.2f locked=%s switchPenalty=%.2f missed=%d",
            belief,
            old,
            reidContribution,
            bboxContribution,
            predictionContribution,
            isLocked,
            switchPenalty + lockedProtectionPenalty,
            track.missedFrames);
    return new IdentityBelief(
        track.trackId,
        belief,
        reidContribution,
        bboxContribution,
        predictionContribution,
        switchPenalty + lockedProtectionPenalty,
        stable,
        uncertain,
        reason);
  }

  private IdentityEvidence withBelief(
      IdentityEvidence base,
      TargetTrack selectedTrack,
      BboxContinuityEvidence bbox,
      IdentityBelief beliefDetails,
      int trackId,
      int lockedTrackId,
      int suspectedTrackId,
      int activeTrackCount,
      String reason) {
    ReIDMatchResult reid =
        base == null ? ReIDMatchResult.unavailable("identity_base_missing", 0) : base.reidMatch;
    Recognition candidate = selectedTrack == null ? null : selectedTrack.recognition;
    float belief = selectedTrack == null ? 0f : getBeliefForTrack(selectedTrack);
    boolean matched = selectedTrack != null && belief >= IdentityBelief.BELIEF_CAUTION;
    float confidence = Math.max(base == null ? 0f : base.confidence, belief);
    int stable = trackId >= 0 ? getStableFrames(trackId) : 0;
    int uncertain = trackId >= 0 ? getUncertainFrames(trackId) : 0;
    int rawSwitchCount = base == null ? 0 : base.candidateSwitchCount;
    return new IdentityEvidence(
        matched ? belief : 0f,
        confidence,
        matched,
        reason,
        reid,
        bbox,
        Math.max(base == null ? 0 : base.stableMatchCount, stable),
        rawSwitchCount + candidateSwitchCount,
        candidate,
        trackId,
        lockedTrackId,
        suspectedTrackId,
        activeTrackCount,
        selectedTrack == null ? 0 : selectedTrack.ageFrames,
        selectedTrack == null ? 0 : selectedTrack.missedFrames,
        belief,
        beliefDetails == null ? 0f : beliefDetails.reidContribution,
        beliefDetails == null ? 0f : beliefDetails.bboxContribution,
        beliefDetails == null ? 0f : beliefDetails.predictionContribution,
        beliefDetails == null ? 0f : beliefDetails.switchPenalty,
        stable,
        uncertain,
        reason);
  }

  private boolean shouldSelect(
      TargetTrack track,
      IdentityBelief belief,
      TargetTrack selectedTrack,
      IdentityBelief selectedBelief) {
    if (selectedTrack == null || selectedBelief == null) return true;
    if (track.trackId == lockedTrackId && belief.targetBelief >= IdentityBelief.BELIEF_LOST) {
      return true;
    }
    if (selectedTrack.trackId == lockedTrackId
        && selectedBelief.targetBelief >= IdentityBelief.BELIEF_LOST) {
      return false;
    }
    if (belief.targetBelief != selectedBelief.targetBelief) {
      return belief.targetBelief > selectedBelief.targetBelief;
    }
    return track.ageFrames > selectedTrack.ageFrames;
  }

  private static BboxContinuityEvidence bboxEvidence(
      TargetTrack track, TargetMemory memory, int frameW, int frameH) {
    if (track == null || memory == null || track.lastBbox == null) {
      return BboxContinuityEvidence.unavailable("track_bbox_not_available");
    }
    return BboxContinuityEvidence.from(
        track.lastBbox, memory.getLastBbox(), memory.getPreviousBbox(), frameW, frameH);
  }

  private static float reidContribution(IdentityEvidence base, boolean isReidCandidate) {
    if (!isReidCandidate || base == null) return 0f;
    if (base.strongOk()) return 0.25f;
    if (base.midOk()) return 0.18f;
    if (base.weakOk()) return 0.10f;
    return base.matched ? 0.05f : 0f;
  }

  private int getStableFrames(int trackId) {
    Integer value = stableFrames.get(trackId);
    return value == null ? 0 : value;
  }

  private int getUncertainFrames(int trackId) {
    Integer value = uncertainFrames.get(trackId);
    return value == null ? 0 : value;
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }
}
