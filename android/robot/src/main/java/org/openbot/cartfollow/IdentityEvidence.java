package org.openbot.cartfollow;

import org.openbot.tflite.Detector.Recognition;

public class IdentityEvidence {
  public final float score;
  public final float confidence;
  public final boolean matched;
  public final String reason;
  public final ReIDMatchResult reidMatch;
  public final BboxContinuityEvidence bboxContinuity;
  public final int stableMatchCount;
  public final int candidateSwitchCount;
  public final Recognition bestCandidate;

  public IdentityEvidence(float score, float confidence, boolean matched, String reason) {
    this(score, confidence, matched, reason, null, null, 0, 0, null);
  }

  public IdentityEvidence(
      float score,
      float confidence,
      boolean matched,
      String reason,
      ReIDMatchResult reidMatch,
      BboxContinuityEvidence bboxContinuity,
      int stableMatchCount,
      int candidateSwitchCount,
      Recognition bestCandidate) {
    this.score = score;
    this.confidence = confidence;
    this.matched = matched;
    this.reason = reason;
    this.reidMatch = reidMatch;
    this.bboxContinuity = bboxContinuity;
    this.stableMatchCount = stableMatchCount;
    this.candidateSwitchCount = candidateSwitchCount;
    this.bestCandidate = bestCandidate;
  }

  public boolean reidAvailable() {
    return reidMatch != null && reidMatch.reidAvailable;
  }

  public boolean weakOk() {
    return reidAvailable() && reidMatch.weakOk;
  }

  public boolean midOk() {
    return reidAvailable() && reidMatch.midOk;
  }

  public boolean strongOk() {
    return reidAvailable() && reidMatch.strongOk;
  }

  public boolean bboxDefaultOk() {
    return bboxContinuity != null && bboxContinuity.bboxDefaultOk;
  }

  public boolean bboxStrictOk() {
    return bboxContinuity != null && bboxContinuity.bboxStrictOk;
  }

  public boolean predictionOk() {
    return bboxContinuity != null && bboxContinuity.predictionOk;
  }
}
