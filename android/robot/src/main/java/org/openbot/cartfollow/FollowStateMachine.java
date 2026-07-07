package org.openbot.cartfollow;

import android.graphics.Bitmap;
import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import org.openbot.tflite.Detector.Recognition;
import org.openbot.vehicle.Control;

public class FollowStateMachine {

  public static class FrameResult {
    public final FollowState state;
    public final Control control;
    public final Recognition target;
    public final Recognition candidate;
    public final List<Recognition> persons;
    public final boolean matched;
    public final boolean tooClose;
    public final Bitmap snapshot;
    public final int countdownSec;
    public float matchScore;
    public ImageSetpointDistanceEstimator.DistanceEstimate distanceEstimate;
    public BehaviorDecisionResult behaviorDecision;

    public FrameResult(
        FollowState state,
        Control control,
        Recognition target,
        Recognition candidate,
        List<Recognition> persons,
        boolean matched,
        boolean tooClose,
        Bitmap snapshot,
        int countdownSec) {
      this.state = state;
      this.control = control;
      this.target = target;
      this.candidate = candidate;
      this.persons = persons;
      this.matched = matched;
      this.tooClose = tooClose;
      this.snapshot = snapshot;
      this.countdownSec = countdownSec;
      this.matchScore = 0f;
    }
  }

  public int CAPTURE_FRAMES = 15;
  public int REACQUIRE_MATCH_N = 8;
  public int FOLLOW_LOST_M = 10;
  public long LOST_TO_SEARCH_MS = 800;
  public long SEARCH_TIMEOUT_MS = 5000;
  public long COUNTDOWN_MS = 3000;

  private final TargetMatcher matcher;
  private final ControlGenerator controlGenerator;
  private final TargetMemory memory = new TargetMemory();

  private FollowState state = FollowState.IDLE;
  private int captureCount = 0;
  private int matchCount = 0;
  private int lostCount = 0;
  private long stateEnterTime = 0L;
  private Bitmap snapshot = null;

  public FollowStateMachine(TargetMatcher matcher, ControlGenerator controlGenerator) {
    this.matcher = matcher;
    this.controlGenerator = controlGenerator;
  }

  public FollowState getState() {
    return state;
  }

  public TargetMemory getMemory() {
    return memory;
  }

  public void startCapture() {
    if (state == FollowState.IDLE || state == FollowState.STOP) {
      memory.clear();
      snapshot = null;
      captureCount = 0;
      state = FollowState.CAPTURE_TARGET;
    }
  }

  public void confirm() {
    if (state == FollowState.LOCKED_PENDING_CONFIRM) {
      state = FollowState.CONFIRMED_ARMED;
      matchCount = 0;
    }
  }

  public void retake() {
    if (state == FollowState.LOCKED_PENDING_CONFIRM) {
      memory.clear();
      snapshot = null;
      captureCount = 0;
      state = FollowState.CAPTURE_TARGET;
    }
  }

  public void cancel() {
    memory.clear();
    snapshot = null;
    captureCount = 0;
    matchCount = 0;
    lostCount = 0;
    state = FollowState.IDLE;
  }

  public FrameResult onFrame(
      List<Recognition> persons, Bitmap frame, int frameW, int frameH, int sensorOrientation) {
    List<Recognition> safePersons = persons == null ? new ArrayList<>() : persons;
    long now = System.currentTimeMillis();

    switch (state) {
      case IDLE:
        return new FrameResult(
            FollowState.IDLE, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);

      case CAPTURE_TARGET: {
        Recognition cand = selectLargest(safePersons);
        if (cand == null || cand.getLocation() == null) {
          captureCount = 0;
          return new FrameResult(
              FollowState.CAPTURE_TARGET, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);
        }
        captureCount++;
        if (captureCount >= CAPTURE_FRAMES) {
          memory.captureFromBitmap(frame, cand.getLocation(), frameW, frameH, sensorOrientation);
          snapshot = cropSnapshot(frame, cand.getLocation());
          captureCount = 0;
          state = FollowState.LOCKED_PENDING_CONFIRM;
          return new FrameResult(
              FollowState.LOCKED_PENDING_CONFIRM, new Control(0f, 0f), null, null, safePersons, false, false, snapshot, -1);
        }
        return new FrameResult(
            FollowState.CAPTURE_TARGET, new Control(0f, 0f), null, cand, safePersons, false, false, null, -1);
      }

      case LOCKED_PENDING_CONFIRM:
        return new FrameResult(
            FollowState.LOCKED_PENDING_CONFIRM, new Control(0f, 0f), null, null, safePersons, false, false, snapshot, -1);

      case CONFIRMED_ARMED:
        if (!safePersons.isEmpty()) {
          state = FollowState.REACQUIRE_TARGET;
          matchCount = 0;
        }
        return new FrameResult(
            state, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);

      case REACQUIRE_TARGET: {
        TargetMatcher.MatchResult m = matcher.match(safePersons, frame, memory, frameW, frameH);
        if (m.matched) matchCount++;
        else matchCount = 0;
        if (matchCount >= REACQUIRE_MATCH_N) {
          state = FollowState.READY_TO_FOLLOW;
          stateEnterTime = now;
          memory.updateDynamic(m.best);
        }
        FrameResult fr =
            new FrameResult(
                state, new Control(0f, 0f), m.best, null, safePersons, m.matched, false, null, -1);
        fr.matchScore = m.score;
        return fr;
      }

      case READY_TO_FOLLOW: {
        int cd = (int) Math.ceil((COUNTDOWN_MS - (now - stateEnterTime)) / 1000.0);
        if (now - stateEnterTime >= COUNTDOWN_MS) {
          state = FollowState.FOLLOW;
          lostCount = 0;
          return new FrameResult(
              FollowState.FOLLOW, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);
        }
        TargetMatcher.MatchResult m = matcher.match(safePersons, frame, memory, frameW, frameH);
        FrameResult fr =
            new FrameResult(
                FollowState.READY_TO_FOLLOW,
                new Control(0f, 0f),
                m.best,
                null,
                safePersons,
                m.matched,
                false,
                null,
                Math.max(0, cd));
        fr.matchScore = m.score;
        return fr;
      }

      case FOLLOW: {
        TargetMatcher.MatchResult m = matcher.match(safePersons, frame, memory, frameW, frameH);
        if (m.matched) {
          memory.updateDynamic(m.best);
          lostCount = 0;
          ControlGenerator.Result res =
              controlGenerator.generateFromTarget(
                  m.best, safePersons, frameW, frameH, sensorOrientation, memory);
          FrameResult fr =
              new FrameResult(
                  FollowState.FOLLOW, res.control, m.best, null, safePersons, true, res.tooClose, null, -1);
          fr.matchScore = m.score;
          fr.distanceEstimate = res.distanceEstimate;
          return fr;
        }
        lostCount++;
        if (lostCount >= FOLLOW_LOST_M) {
          state = FollowState.LOST;
          stateEnterTime = now;
        }
        FrameResult fr =
            new FrameResult(
                state, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);
        fr.matchScore = m.score;
        return fr;
      }

      case LOST: {
        TargetMatcher.MatchResult m = matcher.match(safePersons, frame, memory, frameW, frameH);
        if (m.matched) {
          state = FollowState.FOLLOW;
          lostCount = 0;
          memory.updateDynamic(m.best);
          ControlGenerator.Result res =
              controlGenerator.generateFromTarget(
                  m.best, safePersons, frameW, frameH, sensorOrientation, memory);
          FrameResult fr =
              new FrameResult(
                  FollowState.FOLLOW, res.control, m.best, null, safePersons, true, res.tooClose, null, -1);
          fr.matchScore = m.score;
          fr.distanceEstimate = res.distanceEstimate;
          return fr;
        }
        if (now - stateEnterTime >= LOST_TO_SEARCH_MS) {
          state = FollowState.SEARCH;
          stateEnterTime = now;
        }
        FrameResult fr =
            new FrameResult(
                state, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);
        fr.matchScore = m.score;
        return fr;
      }

      case SEARCH: {
        TargetMatcher.MatchResult m = matcher.match(safePersons, frame, memory, frameW, frameH);
        if (m.matched) {
          state = FollowState.FOLLOW;
          lostCount = 0;
          memory.updateDynamic(m.best);
          ControlGenerator.Result res =
              controlGenerator.generateFromTarget(
                  m.best, safePersons, frameW, frameH, sensorOrientation, memory);
          FrameResult fr =
              new FrameResult(
                  FollowState.FOLLOW, res.control, m.best, null, safePersons, true, res.tooClose, null, -1);
          fr.matchScore = m.score;
          fr.distanceEstimate = res.distanceEstimate;
          return fr;
        }
        if (now - stateEnterTime >= SEARCH_TIMEOUT_MS) {
          state = FollowState.STOP;
        }
        FrameResult fr =
            new FrameResult(
                state, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);
        fr.matchScore = m.score;
        return fr;
      }

      case STOP:
      default:
        return new FrameResult(
            FollowState.STOP, new Control(0f, 0f), null, null, safePersons, false, false, null, -1);
    }
  }

  private static Recognition selectLargest(List<Recognition> persons) {
    Recognition target = null;
    float maxArea = -1f;
    for (Recognition r : persons) {
      if (r == null || r.getLocation() == null) continue;
      RectF loc = r.getLocation();
      float area = loc.width() * loc.height();
      if (area > maxArea) {
        maxArea = area;
        target = r;
      }
    }
    return target;
  }

  private static Bitmap cropSnapshot(Bitmap frame, RectF bbox) {
    if (frame == null || bbox == null) return null;
    int fw = frame.getWidth();
    int fh = frame.getHeight();
    int l = clamp((int) bbox.left, 0, fw - 1);
    int t = clamp((int) bbox.top, 0, fh - 1);
    int r = clamp((int) bbox.right, 1, fw);
    int b = clamp((int) bbox.bottom, 1, fh);
    int w = r - l;
    int h = b - t;
    if (w <= 0 || h <= 0) return null;
    try {
      return Bitmap.createBitmap(frame, l, t, w, h);
    } catch (Exception e) {
      return null;
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
  }
}
