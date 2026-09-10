package org.openbot.cartfollow;

import android.graphics.RectF;

/** Orientation-independent full-body framing used by positioning and calibration. */
public final class InitializationFraming {
  public static final float EDGE_MARGIN = .01f;

  public static final class Result {
    public final RectF screenBox;
    public final boolean valid;
    public final boolean clippedLeft;
    public final boolean clippedTop;
    public final boolean clippedRight;
    public final boolean clippedBottom;

    private Result(RectF screenBox, boolean valid) {
      this.screenBox = screenBox == null ? null : new RectF(screenBox);
      this.valid = valid;
      clippedLeft = !valid || screenBox.left < EDGE_MARGIN;
      clippedTop = !valid || screenBox.top < EDGE_MARGIN;
      clippedRight = !valid || screenBox.right > 1f - EDGE_MARGIN;
      clippedBottom = !valid || screenBox.bottom > 1f - EDGE_MARGIN;
    }

    public boolean fullBody() {
      return valid && !clippedLeft && !clippedTop && !clippedRight && !clippedBottom;
    }

    public String clippedEdges() {
      if (fullBody()) return "none";
      StringBuilder out = new StringBuilder();
      if (clippedLeft) out.append("left|");
      if (clippedTop) out.append("top|");
      if (clippedRight) out.append("right|");
      if (clippedBottom) out.append("bottom|");
      return out.length() == 0 ? "invalid" : out.substring(0, out.length() - 1);
    }
  }

  private InitializationFraming() {}

  public static Result evaluate(RectF box, int width, int height, int orientation) {
    if (box == null || width <= 0 || height <= 0 || box.width() <= 0f || box.height() <= 0f)
      return new Result(null, false);
    RectF screen = TargetObservationEvidence.toScreen(box, width, height, orientation);
    boolean valid = finite(screen.left) && finite(screen.top) && finite(screen.right)
        && finite(screen.bottom) && screen.width() > 0f && screen.height() > 0f;
    return new Result(screen, valid);
  }

  private static boolean finite(float value) {
    return !Float.isNaN(value) && !Float.isInfinite(value);
  }
}
