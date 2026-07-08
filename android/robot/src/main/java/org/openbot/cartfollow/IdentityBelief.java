package org.openbot.cartfollow;

public class IdentityBelief {
  public static final float BELIEF_CONFIRM = 0.75f;
  public static final float BELIEF_CAUTION = 0.55f;
  public static final float BELIEF_LOST = 0.30f;

  public final int trackId;
  public final float targetBelief;
  public final float reidContribution;
  public final float bboxContribution;
  public final float predictionContribution;
  public final float switchPenalty;
  public final int stableFrames;
  public final int uncertainFrames;
  public final String beliefReason;

  public IdentityBelief(
      int trackId,
      float targetBelief,
      float reidContribution,
      float bboxContribution,
      float predictionContribution,
      float switchPenalty,
      int stableFrames,
      int uncertainFrames,
      String beliefReason) {
    this.trackId = trackId;
    this.targetBelief = targetBelief;
    this.reidContribution = reidContribution;
    this.bboxContribution = bboxContribution;
    this.predictionContribution = predictionContribution;
    this.switchPenalty = switchPenalty;
    this.stableFrames = stableFrames;
    this.uncertainFrames = uncertainFrames;
    this.beliefReason = beliefReason;
  }
}
