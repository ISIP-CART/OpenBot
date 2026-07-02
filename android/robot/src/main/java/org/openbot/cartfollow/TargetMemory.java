package org.openbot.cartfollow;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import org.openbot.tflite.Detector.Recognition;

public class TargetMemory {
  private static final int H_BINS = 8;
  private static final int S_BINS = 4;
  private static final int HIST_SIZE = H_BINS * S_BINS;

  private RectF confirmedBbox;
  private float confirmedArea;
  private float confirmedAspectRatio;
  private float[] upperColorHist;
  private float[] lowerColorHist;

  private RectF lastBbox;
  private float lastCenterX;
  private float lastCenterY;
  private float lastArea;
  private long lastSeenTimeMs;

  public void captureFromBitmap(Bitmap bitmap, RectF bbox) {
    confirmedBbox = new RectF(bbox);
    confirmedArea = bbox.width() * bbox.height();
    confirmedAspectRatio = bbox.width() / Math.max(1f, bbox.height());
    upperColorHist = computeHsvHist(bitmap, upperHalf(bbox));
    lowerColorHist = computeHsvHist(bitmap, lowerHalf(bbox));
    lastBbox = new RectF(bbox);
    lastCenterX = bbox.centerX();
    lastCenterY = bbox.centerY();
    lastArea = confirmedArea;
    lastSeenTimeMs = System.currentTimeMillis();
  }

  public void updateDynamic(Recognition r) {
    if (r == null || r.getLocation() == null) return;
    RectF b = r.getLocation();
    lastBbox = new RectF(b);
    lastCenterX = b.centerX();
    lastCenterY = b.centerY();
    lastArea = b.width() * b.height();
    lastSeenTimeMs = System.currentTimeMillis();
  }

  public void clear() {
    confirmedBbox = null;
    confirmedArea = 0f;
    confirmedAspectRatio = 0f;
    upperColorHist = null;
    lowerColorHist = null;
    lastBbox = null;
    lastCenterX = 0f;
    lastCenterY = 0f;
    lastArea = 0f;
    lastSeenTimeMs = 0L;
  }

  public boolean isEmpty() {
    return confirmedBbox == null;
  }

  public RectF getLastBbox() {
    return lastBbox != null ? new RectF(lastBbox) : (confirmedBbox != null ? new RectF(confirmedBbox) : null);
  }

  public float getLastArea() {
    return lastArea > 0 ? lastArea : confirmedArea;
  }

  public float[] getUpperColorHist() {
    return upperColorHist;
  }

  public float[] getLowerColorHist() {
    return lowerColorHist;
  }

  public float getConfirmedArea() {
    return confirmedArea;
  }

  private static RectF upperHalf(RectF bbox) {
    return new RectF(bbox.left, bbox.top, bbox.right, bbox.top + bbox.height() / 2f);
  }

  private static RectF lowerHalf(RectF bbox) {
    return new RectF(bbox.left, bbox.top + bbox.height() / 2f, bbox.right, bbox.bottom);
  }

  public static float[] computeHsvHist(Bitmap bitmap, RectF rect) {
    float[] hist = new float[HIST_SIZE];
    if (bitmap == null || rect == null) return hist;
    int bw = bitmap.getWidth();
    int bh = bitmap.getHeight();
    int left = clamp((int) rect.left, 0, bw - 1);
    int top = clamp((int) rect.top, 0, bh - 1);
    int right = clamp((int) rect.right, 1, bw);
    int bottom = clamp((int) rect.bottom, 1, bh);
    int w = right - left;
    int h = bottom - top;
    if (w <= 0 || h <= 0) return hist;
    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, left, top, w, h);
    float[] hsv = new float[3];
    int count = 0;
    for (int px : pixels) {
      Color.RGBToHSV(Color.red(px), Color.green(px), Color.blue(px), hsv);
      if (hsv[1] < 0.1f) continue;
      int hb = (int) (hsv[0] / 360f * H_BINS) % H_BINS;
      if (hb < 0) hb += H_BINS;
      int sb = Math.min(S_BINS - 1, (int) (hsv[1] * S_BINS));
      hist[hb * S_BINS + sb]++;
      count++;
    }
    if (count > 0) {
      for (int i = 0; i < HIST_SIZE; i++) hist[i] /= count;
    }
    return hist;
  }

  public static float histIntersection(float[] a, float[] b) {
    if (a == null || b == null || a.length == 0 || b.length == 0) return 0f;
    float sum = 0f;
    int n = Math.min(a.length, b.length);
    for (int i = 0; i < n; i++) sum += Math.min(a[i], b[i]);
    return sum;
  }

  public float colorScore(Bitmap bitmap, RectF bbox) {
    float[] upper = computeHsvHist(bitmap, upperHalf(bbox));
    float[] lower = computeHsvHist(bitmap, lowerHalf(bbox));
    float upperScore = histIntersection(upper, upperColorHist);
    float lowerScore = histIntersection(lower, lowerColorHist);
    return (upperScore + lowerScore) / 2f;
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
