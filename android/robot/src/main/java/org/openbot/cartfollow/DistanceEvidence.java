package org.openbot.cartfollow;

public class DistanceEvidence {
  public final DistanceState state;
  public final float confidence;
  public final String reason;

  public DistanceEvidence(DistanceState state, float confidence, String reason) {
    this.state = state == null ? DistanceState.UNKNOWN : state;
    this.confidence = confidence;
    this.reason = reason;
  }
}
