package org.openbot.cartfollow;

public class TraversabilityEvidence {
  public final float leftFreeScore;
  public final float centerFreeScore;
  public final float rightFreeScore;
  public final boolean centerBlocked;
  public final String reason;

  public TraversabilityEvidence(
      float leftFreeScore,
      float centerFreeScore,
      float rightFreeScore,
      boolean centerBlocked,
      String reason) {
    this.leftFreeScore = leftFreeScore;
    this.centerFreeScore = centerFreeScore;
    this.rightFreeScore = rightFreeScore;
    this.centerBlocked = centerBlocked;
    this.reason = reason;
  }
}
