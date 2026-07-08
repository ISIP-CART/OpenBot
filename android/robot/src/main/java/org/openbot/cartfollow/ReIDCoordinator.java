package org.openbot.cartfollow;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.SystemClock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.openbot.tflite.Detector.Recognition;

public class ReIDCoordinator {
  private static final int MAX_PENDING_GALLERY = 12;
  private static final int CONFIRMED_GALLERY_K = 8;
  private static final int MAX_CANDIDATES = 3;
  private static final long FOLLOW_INTERVAL_MS = 1000;
  private static final long HIGH_RISK_INTERVAL_MS = 300;

  private final ReIDFeatureExtractor extractor;
  private final String disabledReason;
  private final List<float[]> pendingGallery = new ArrayList<>();
  private final List<float[]> confirmedGallery = new ArrayList<>();

  private ReIDMatchResult lastResult = ReIDMatchResult.unavailable("not_started", 0);
  private Recognition lastBestCandidate = null;
  private BboxContinuityEvidence lastBboxEvidence =
      BboxContinuityEvidence.unavailable("not_started");
  private long lastRunTimeMs = 0L;
  private int stableMatchCount = 0;
  private int candidateSwitchCount = 0;
  private int lastBestIndex = -1;

  public ReIDCoordinator(Activity activity, int numThreads) {
    ReIDFeatureExtractor created = null;
    String reason = null;
    try {
      created =
          new TfliteReIDFeatureExtractor(
              activity, TfliteReIDFeatureExtractor.DEFAULT_ASSET_PATH, numThreads);
    } catch (IOException | IllegalArgumentException e) {
      reason = "reid_model_unavailable";
    }
    extractor = created;
    disabledReason = reason;
  }

  public void reset() {
    pendingGallery.clear();
    confirmedGallery.clear();
    lastResult = ReIDMatchResult.unavailable(isAvailable() ? "reset" : disabledReason, 0);
    lastBestCandidate = null;
    lastBboxEvidence = BboxContinuityEvidence.unavailable("reset");
    lastRunTimeMs = 0L;
    stableMatchCount = 0;
    candidateSwitchCount = 0;
    lastBestIndex = -1;
  }

  public boolean isAvailable() {
    return extractor != null;
  }

  public int getGallerySize() {
    return confirmedGallery.size();
  }

  public Recognition getLastBestCandidate() {
    return lastBestCandidate;
  }

  public BboxContinuityEvidence getLastBboxEvidence() {
    return lastBboxEvidence;
  }

  public ReIDMatchResult getLastResult() {
    return lastResult;
  }

  public void collectInitializationCandidate(
      Bitmap frame, Recognition candidate, int sensorOrientation) {
    if (!isAvailable() || frame == null || candidate == null || candidate.getLocation() == null) {
      return;
    }
    if (pendingGallery.size() >= MAX_PENDING_GALLERY) return;
    Bitmap crop = cropPerson(frame, candidate.getLocation(), 0.08f, sensorOrientation);
    float[] feature = extractor.extract(crop);
    crop.recycle();
    if (feature != null) pendingGallery.add(feature);
  }

  public void confirmGallery() {
    confirmedGallery.clear();
    if (pendingGallery.isEmpty()) return;
    for (float[] feature : selectDiverse(pendingGallery, CONFIRMED_GALLERY_K)) {
      confirmedGallery.add(feature);
    }
    lastResult =
        ReIDMatchResult.unavailable(
            isAvailable() ? "gallery_confirmed" : disabledReason, confirmedGallery.size());
  }

  public IdentityEvidence evaluate(
      List<Recognition> persons,
      Bitmap frame,
      TargetMemory memory,
      FollowState state,
      int frameW,
      int frameH,
      int sensorOrientation,
      float legacyScore,
      boolean legacyMatched,
      Recognition legacyBest) {
    ReIDMatchResult result =
        maybeRunReID(persons, frame, memory, state, frameW, frameH, sensorOrientation, legacyBest);
    Recognition best = lastBestCandidate != null ? lastBestCandidate : legacyBest;
    boolean matched = legacyMatched;
    float confidence = legacyScore;
    if (result.reidAvailable && result.weakOk) {
      matched = true;
      confidence = result.bestScore;
    }
    return new IdentityEvidence(
        matched ? confidence : 0f,
        confidence,
        matched,
        result.reason,
        result,
        lastBboxEvidence,
        stableMatchCount,
        candidateSwitchCount,
        best);
  }

  private ReIDMatchResult maybeRunReID(
      List<Recognition> persons,
      Bitmap frame,
      TargetMemory memory,
      FollowState state,
      int frameW,
      int frameH,
      int sensorOrientation,
      Recognition legacyBest) {
    if (!isAvailable()) {
      lastResult = ReIDMatchResult.unavailable(disabledReason, 0);
      lastBestCandidate = legacyBest;
      lastBboxEvidence = bboxEvidence(legacyBest, memory, frameW, frameH);
      return lastResult;
    }
    if (confirmedGallery.isEmpty()) {
      lastResult = ReIDMatchResult.unavailable("gallery_empty", 0);
      lastBestCandidate = legacyBest;
      lastBboxEvidence = bboxEvidence(legacyBest, memory, frameW, frameH);
      return lastResult;
    }
    if (persons == null || persons.isEmpty() || frame == null) {
      lastResult = ReIDMatchResult.unavailable("no_candidates", confirmedGallery.size());
      lastBestCandidate = null;
      lastBboxEvidence = BboxContinuityEvidence.unavailable("no_candidates");
      return lastResult;
    }
    long now = SystemClock.elapsedRealtime();
    boolean highRisk =
        state != FollowState.FOLLOW || persons.size() > 1 || legacyBest == null || !same(lastBestCandidate, legacyBest);
    long interval = highRisk ? HIGH_RISK_INTERVAL_MS : FOLLOW_INTERVAL_MS;
    if (now - lastRunTimeMs < interval && lastResult.reidAvailable) {
      return lastResult;
    }

    long start = SystemClock.elapsedRealtime();
    List<CandidateScore> scores = new ArrayList<>();
    for (CandidateRef ref : candidateRefs(persons, legacyBest)) {
      Bitmap crop = cropPerson(frame, ref.recognition.getLocation(), 0.08f, sensorOrientation);
      float[] feature = extractor.extract(crop);
      crop.recycle();
      if (feature == null) continue;
      float score = maxSimilarity(feature, confirmedGallery);
      scores.add(new CandidateScore(ref.index, ref.recognition, score));
    }
    if (scores.isEmpty()) {
      lastResult = ReIDMatchResult.unavailable("feature_extract_failed", confirmedGallery.size());
      lastBestCandidate = legacyBest;
      lastBboxEvidence = bboxEvidence(legacyBest, memory, frameW, frameH);
      return lastResult;
    }
    scores.sort((a, b) -> Float.compare(b.score, a.score));
    CandidateScore best = scores.get(0);
    float second = scores.size() > 1 ? scores.get(1).score : 0f;
    if (best.index == lastBestIndex) {
      stableMatchCount++;
    } else {
      if (lastBestIndex >= 0) candidateSwitchCount++;
      stableMatchCount = 1;
      lastBestIndex = best.index;
    }
    lastBestCandidate = best.recognition;
    lastBboxEvidence = bboxEvidence(best.recognition, memory, frameW, frameH);
    lastRunTimeMs = now;
    lastResult =
        new ReIDMatchResult(
            best.score,
            second,
            best.index,
            confirmedGallery.size(),
            true,
            SystemClock.elapsedRealtime() - start,
            "fresh");
    return lastResult;
  }

  private static BboxContinuityEvidence bboxEvidence(
      Recognition recognition, TargetMemory memory, int frameW, int frameH) {
    if (recognition == null || memory == null || recognition.getLocation() == null) {
      return BboxContinuityEvidence.unavailable("candidate_not_available");
    }
    return BboxContinuityEvidence.from(
        recognition.getLocation(), memory.getLastBbox(), memory.getPreviousBbox(), frameW, frameH);
  }

  private static List<CandidateRef> candidateRefs(List<Recognition> persons, Recognition legacyBest) {
    List<CandidateRef> refs = new ArrayList<>();
    if (legacyBest != null) {
      int idx = persons.indexOf(legacyBest);
      if (idx >= 0) refs.add(new CandidateRef(idx, legacyBest));
    }
    List<CandidateRef> all = new ArrayList<>();
    for (int i = 0; i < persons.size(); i++) {
      Recognition r = persons.get(i);
      if (r != null && r.getLocation() != null) all.add(new CandidateRef(i, r));
    }
    all.sort(
        Comparator.comparingDouble(
                (CandidateRef ref) -> -area(ref.recognition.getLocation()))
            .thenComparingInt(ref -> ref.index));
    for (CandidateRef ref : all) {
      boolean exists = false;
      for (CandidateRef current : refs) {
        if (current.index == ref.index) {
          exists = true;
          break;
        }
      }
      if (!exists) refs.add(ref);
      if (refs.size() >= MAX_CANDIDATES) break;
    }
    return refs;
  }

  private static Bitmap cropPerson(
      Bitmap frame, RectF bbox, float paddingRatio, int sensorOrientation) {
    int fw = frame.getWidth();
    int fh = frame.getHeight();
    float padX = bbox.width() * paddingRatio;
    float padY = bbox.height() * paddingRatio;
    int left = clamp((int) (bbox.left - padX), 0, fw - 1);
    int top = clamp((int) (bbox.top - padY), 0, fh - 1);
    int right = clamp((int) (bbox.right + padX), 1, fw);
    int bottom = clamp((int) (bbox.bottom + padY), 1, fh);
    int width = Math.max(1, right - left);
    int height = Math.max(1, bottom - top);
    Bitmap rawCrop = Bitmap.createBitmap(frame, left, top, width, height);
    int rotation = ((sensorOrientation % 360) + 360) % 360;
    if (rotation == 0) return rawCrop;
    Matrix matrix = new Matrix();
    matrix.postRotate(rotation);
    Bitmap upright =
        Bitmap.createBitmap(rawCrop, 0, 0, rawCrop.getWidth(), rawCrop.getHeight(), matrix, true);
    rawCrop.recycle();
    return upright;
  }

  private static List<float[]> selectDiverse(List<float[]> features, int k) {
    List<float[]> selected = new ArrayList<>();
    if (features.isEmpty()) return selected;
    selected.add(features.get(0));
    while (selected.size() < k && selected.size() < features.size()) {
      float bestDistance = -1f;
      float[] best = null;
      for (float[] candidate : features) {
        if (selected.contains(candidate)) continue;
        float nearestSimilarity = -1f;
        for (float[] existing : selected) {
          nearestSimilarity = Math.max(nearestSimilarity, dot(candidate, existing));
        }
        float distance = 1f - nearestSimilarity;
        if (distance > bestDistance) {
          bestDistance = distance;
          best = candidate;
        }
      }
      if (best == null) break;
      selected.add(best);
    }
    return selected;
  }

  private static float maxSimilarity(float[] feature, List<float[]> gallery) {
    float best = -1f;
    for (float[] g : gallery) best = Math.max(best, dot(feature, g));
    return Math.max(0f, best);
  }

  private static float dot(float[] a, float[] b) {
    int n = Math.min(a.length, b.length);
    float sum = 0f;
    for (int i = 0; i < n; i++) sum += a[i] * b[i];
    return sum;
  }

  private static boolean same(Recognition a, Recognition b) {
    return a != null && b != null && a == b;
  }

  private static float area(RectF b) {
    return Math.max(0f, b.width()) * Math.max(0f, b.height());
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }

  private static class CandidateRef {
    final int index;
    final Recognition recognition;

    CandidateRef(int index, Recognition recognition) {
      this.index = index;
      this.recognition = recognition;
    }
  }

  private static class CandidateScore {
    final int index;
    final Recognition recognition;
    final float score;

    CandidateScore(int index, Recognition recognition, float score) {
      this.index = index;
      this.recognition = recognition;
      this.score = score;
    }
  }
}
