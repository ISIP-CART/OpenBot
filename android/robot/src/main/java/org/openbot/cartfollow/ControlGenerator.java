package org.openbot.cartfollow;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import org.openbot.tflite.Detector.Recognition;
import org.openbot.vehicle.Control;

public class ControlGenerator {

  public static class Result {
    public final Control control;
    public final Recognition target;
    public final List<Recognition> persons;
    public final boolean tooClose;

    public Result(Control control, Recognition target, List<Recognition> persons, boolean tooClose) {
      this.control = control;
      this.target = target;
      this.persons = persons;
      this.tooClose = tooClose;
    }
  }

  public float K_TURN = 1.5f;
  public float K_DIST = 1.0f;
  public float TARGET_H_RATIO = 0.5f;
  public float MAX_FORWARD = 0.6f;
  public float MIN_CONFIDENCE = 0.5f;
  public float TOO_CLOSE_H_RATIO = 0.75f;
  public boolean FLIP_TURN = true;

  public Result generate(List<Recognition> results, int frameW, int frameH, int sensorOrientation) {
    List<Recognition> persons = new ArrayList<>();
    if (results != null) {
      for (Recognition r : results) {
        if (r == null || r.getLocation() == null) continue;
        if (r.getConfidence() == null || r.getConfidence() < MIN_CONFIDENCE) continue;
        if (!"person".equals(r.getTitle())) continue;
        persons.add(r);
      }
    }

    if (persons.isEmpty() || frameW <= 0 || frameH <= 0) {
      return new Result(new Control(0f, 0f), null, persons, false);
    }

    Recognition target = null;
    float maxArea = -1f;
    for (Recognition r : persons) {
      RectF loc = r.getLocation();
      float area = loc.width() * loc.height();
      if (area > maxArea) {
        maxArea = area;
        target = r;
      }
    }

    RectF loc = target.getLocation();
    boolean rotated = sensorOrientation % 180 == 90;
    float imgWidth = rotated ? frameH : frameW;
    float imgHeight = rotated ? frameW : frameH;
    float centerX = rotated ? loc.centerY() : loc.centerX();
    float boxHeight = rotated ? loc.width() : loc.height();
    centerX = Math.max(0f, Math.min(centerX, imgWidth));

    float xError = centerX / imgWidth - 0.5f;
    float heightRatio = boxHeight / imgHeight;
    float distError = TARGET_H_RATIO - heightRatio;
    boolean tooClose = heightRatio > TOO_CLOSE_H_RATIO;

    float turn = K_TURN * xError;
    if (FLIP_TURN) turn = -turn;

    float forward = K_DIST * distError;
    forward = Math.max(0f, Math.min(forward, MAX_FORWARD));
    if (tooClose) forward = 0f;

    float left = forward - turn;
    float right = forward + turn;
    return new Result(new Control(left, right), target, persons, tooClose);
  }
}
