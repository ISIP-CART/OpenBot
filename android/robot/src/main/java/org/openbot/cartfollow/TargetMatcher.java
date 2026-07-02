package org.openbot.cartfollow;

import android.graphics.Bitmap;
import android.graphics.RectF;
import java.util.List;
import org.openbot.tflite.Detector.Recognition;

public class TargetMatcher {

  public static class MatchResult {
    public final Recognition best;
    public final float score;
    public final boolean matched;

    public MatchResult(Recognition best, float score, boolean matched) {
      this.best = best;
      this.score = score;
      this.matched = matched;
    }
  }

  public float W_POSITION = 0.40f;
  public float W_SIZE = 0.20f;
  public float W_COLOR = 0.30f;
  public float W_CONFIDENCE = 0.10f;
  public float MATCH_THRESHOLD = 0.5f;

  public MatchResult match(
      List<Recognition> persons, Bitmap frame, TargetMemory memory, int frameW, int frameH) {
    if (persons == null || persons.isEmpty() || memory == null || memory.isEmpty()) {
      return new MatchResult(null, 0f, false);
    }
    RectF refBbox = memory.getLastBbox();
    float refArea = memory.getLastArea();
    float imgDiag = (float) Math.sqrt((double) frameW * frameW + (double) frameH * frameH);

    Recognition best = null;
    float bestScore = -1f;
    for (Recognition r : persons) {
      if (r == null || r.getLocation() == null) continue;
      RectF b = r.getLocation();
      float score = scoreOne(b, r.getConfidence(), frame, memory, refBbox, refArea, imgDiag);
      if (score > bestScore) {
        bestScore = score;
        best = r;
      }
    }
    boolean matched = best != null && bestScore >= MATCH_THRESHOLD;
    return new MatchResult(best, Math.max(0f, bestScore), matched);
  }

  private float scoreOne(
      RectF b,
      Float confidence,
      Bitmap frame,
      TargetMemory memory,
      RectF refBbox,
      float refArea,
      float imgDiag) {
    float cx = b.centerX();
    float cy = b.centerY();
    float refCx = refBbox.centerX();
    float refCy = refBbox.centerY();
    float dist = (float) Math.sqrt((cx - refCx) * (cx - refCx) + (cy - refCy) * (cy - refCy));
    float positionScore = 1f - Math.min(1f, dist / Math.max(1f, imgDiag * 0.3f));

    float area = b.width() * b.height();
    float sizeScore = 1f - Math.min(1f, Math.abs(area - refArea) / Math.max(1f, refArea));

    float colorScore = memory.colorScore(frame, b);

    float confScore = confidence == null ? 0f : Math.min(1f, confidence);

    return W_POSITION * positionScore
        + W_SIZE * sizeScore
        + W_COLOR * colorScore
        + W_CONFIDENCE * confScore;
  }
}
