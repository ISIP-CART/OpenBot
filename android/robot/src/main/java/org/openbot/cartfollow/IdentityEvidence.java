package org.openbot.cartfollow;

public class IdentityEvidence {
  public final float score;
  public final float confidence;
  public final boolean matched;
  public final String reason;

  public IdentityEvidence(float score, float confidence, boolean matched, String reason) {
    this.score = score;
    this.confidence = confidence;
    this.matched = matched;
    this.reason = reason;
  }
}
