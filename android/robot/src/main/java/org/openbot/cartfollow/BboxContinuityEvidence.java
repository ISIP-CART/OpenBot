package org.openbot.cartfollow;

import android.graphics.RectF;

public class BboxContinuityEvidence {
  public static final float DEFAULT_CENTER_MAX = 0.25f;
  public static final float DEFAULT_X_MAX = 0.25f;
  public static final float DEFAULT_AREA_MIN = 0.50f;
  public static final float DEFAULT_AREA_MAX = 2.00f;
  public static final float STRICT_CENTER_MAX = 0.18f;
  public static final float STRICT_X_MAX = 0.18f;
  public static final float STRICT_AREA_MIN = 0.60f;
  public static final float STRICT_AREA_MAX = 1.67f;

  public final float centerJumpRatio;
  public final float xJumpRatio;
  public final float areaRatio;
  public final float predictionError;
  public final boolean bboxDefaultOk;
  public final boolean bboxStrictOk;
  public final boolean predictionOk;
  public final String reason;

  public BboxContinuityEvidence(
      float centerJumpRatio,
      float xJumpRatio,
      float areaRatio,
      float predictionError,
      String reason) {
    this.centerJumpRatio = centerJumpRatio;
    this.xJumpRatio = xJumpRatio;
    this.areaRatio = areaRatio;
    this.predictionError = predictionError;
    this.bboxDefaultOk =
        centerJumpRatio <= DEFAULT_CENTER_MAX
            && xJumpRatio <= DEFAULT_X_MAX
            && areaRatio >= DEFAULT_AREA_MIN
            && areaRatio <= DEFAULT_AREA_MAX;
    this.bboxStrictOk =
        centerJumpRatio <= STRICT_CENTER_MAX
            && xJumpRatio <= STRICT_X_MAX
            && areaRatio >= STRICT_AREA_MIN
            && areaRatio <= STRICT_AREA_MAX;
    this.predictionOk = predictionError >= 0f && predictionError <= STRICT_CENTER_MAX;
    this.reason = reason;
  }

  public static BboxContinuityEvidence unavailable(String reason) {
    return new BboxContinuityEvidence(1f, 1f, 0f, -1f, reason);
  }

  public static BboxContinuityEvidence from(
      RectF candidate, RectF last, RectF previous, int frameW, int frameH) {
    if (candidate == null || last == null || frameW <= 0 || frameH <= 0) {
      return unavailable("bbox_reference_not_available");
    }
    float diag = (float) Math.hypot(frameW, frameH);
    float centerJump =
        (float)
            (Math.hypot(candidate.centerX() - last.centerX(), candidate.centerY() - last.centerY())
                / Math.max(1f, diag));
    float xJump = Math.abs(candidate.centerX() - last.centerX()) / Math.max(1f, frameW);
    float areaRatio = area(candidate) / Math.max(1f, area(last));
    float predictionError = -1f;
    if (previous != null) {
      float predX = last.centerX() + (last.centerX() - previous.centerX());
      float predY = last.centerY() + (last.centerY() - previous.centerY());
      predictionError =
          (float)
              (Math.hypot(candidate.centerX() - predX, candidate.centerY() - predY)
                  / Math.max(1f, diag));
    }
    return new BboxContinuityEvidence(centerJump, xJump, areaRatio, predictionError, "ok");
  }

  private static float area(RectF b) {
    return Math.max(0f, b.width()) * Math.max(0f, b.height());
  }
}
