package org.openbot.cartfollow;

public class ReIDMatchResult {
  public static final float BEST_WEAK = 0.75f;
  public static final float MARGIN_WEAK = 0.03f;
  public static final float BEST_MID = 0.80f;
  public static final float MARGIN_MID = 0.05f;
  public static final float BEST_STRONG = 0.85f;
  public static final float MARGIN_STRONG = 0.05f;

  public final float bestScore;
  public final float secondScore;
  public final float margin;
  public final int bestCandidateIndex;
  public final int gallerySize;
  public final boolean reidAvailable;
  public final boolean weakOk;
  public final boolean midOk;
  public final boolean strongOk;
  public final long latencyMs;
  public final String reason;

  public ReIDMatchResult(
      float bestScore,
      float secondScore,
      int bestCandidateIndex,
      int gallerySize,
      boolean reidAvailable,
      long latencyMs,
      String reason) {
    this.bestScore = bestScore;
    this.secondScore = secondScore;
    this.margin = bestScore - secondScore;
    this.bestCandidateIndex = bestCandidateIndex;
    this.gallerySize = gallerySize;
    this.reidAvailable = reidAvailable;
    this.weakOk = reidAvailable && bestScore >= BEST_WEAK && margin >= MARGIN_WEAK;
    this.midOk = reidAvailable && bestScore >= BEST_MID && margin >= MARGIN_MID;
    this.strongOk = reidAvailable && bestScore >= BEST_STRONG && margin >= MARGIN_STRONG;
    this.latencyMs = latencyMs;
    this.reason = reason;
  }

  public static ReIDMatchResult unavailable(String reason, int gallerySize) {
    return new ReIDMatchResult(0f, 0f, -1, gallerySize, false, 0L, reason);
  }
}
