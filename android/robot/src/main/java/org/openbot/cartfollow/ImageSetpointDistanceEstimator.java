package org.openbot.cartfollow;

import android.graphics.RectF;
import org.openbot.tflite.Detector.Recognition;

/**
 * 基于初始化距离标定的图像伺服距离估计器。
 *
 * <p>核心思想：不恢复真实米制距离，而是比较当前目标图像尺度与初始化时记录的期望图像尺度，
 * 输出 {@link DistanceState}（TOO_FAR / OK / TOO_CLOSE / UNKNOWN）。
 *
 * <p>主信号：height_scale = current_height_ratio / desired_height_ratio。
 * 辅信号：area_scale = sqrt(current_area_ratio / desired_area_ratio)。
 * 辅信号：bottom_shift = current_bottom_ratio - desired_bottom_ratio（仅用于显示，不参与判态）。
 */
public class ImageSetpointDistanceEstimator {

  /** heightScale 低于此值判定 TOO_FAR。 */
  public float FAR_THRESHOLD = 0.85f;
  /** heightScale 高于此值判定 TOO_CLOSE。 */
  public float CLOSE_THRESHOLD = 1.15f;
  /** height_scale 与 area_scale 的对数差异超过此值时判 UNKNOWN。 */
  public float UNKNOWN_HEIGHT_DISAGREE = 0.3f;
  /** bbox 高度占比低于此值时判 UNKNOWN（目标过小，不可信）。 */
  public float MIN_BBOX_HEIGHT_RATIO = 0.1f;

  /** 期望图像尺度（初始化标定值）。 */
  public static class Setpoint {
    public final float desiredHeightRatio;
    public final float desiredAreaRatio;
    public final float desiredBottomRatio;

    public Setpoint(float desiredHeightRatio, float desiredAreaRatio, float desiredBottomRatio) {
      this.desiredHeightRatio = desiredHeightRatio;
      this.desiredAreaRatio = desiredAreaRatio;
      this.desiredBottomRatio = desiredBottomRatio;
    }
  }

  /** 距离估计结果。 */
  public static class DistanceEstimate {
    public final float heightScale;
    public final float areaScale;
    public final float bottomShift;
    public final DistanceState state;
    public final float confidence;
    public final String failureReason;

    public DistanceEstimate(
        float heightScale,
        float areaScale,
        float bottomShift,
        DistanceState state,
        float confidence,
        String failureReason) {
      this.heightScale = heightScale;
      this.areaScale = areaScale;
      this.bottomShift = bottomShift;
      this.state = state;
      this.confidence = confidence;
      this.failureReason = failureReason;
    }

    public static DistanceEstimate unknown(String reason) {
      return new DistanceEstimate(0f, 0f, 0f, DistanceState.UNKNOWN, 0f, reason);
    }
  }

  /**
   * 估计当前目标距离状态。
   *
   * @param target 当前匹配到的目标 Recognition
   * @param frameW 分析帧宽（getMaxAnalyseImageSize）
   * @param frameH 分析帧高
   * @param sensorOrientation 传感器方向
   * @param setpoint 初始化标定的期望图像尺度，可为 null
   */
  public DistanceEstimate estimate(
      Recognition target, int frameW, int frameH, int sensorOrientation, Setpoint setpoint) {
    if (target == null || target.getLocation() == null || frameW <= 0 || frameH <= 0) {
      return DistanceEstimate.unknown("invalid target or frame size");
    }
    if (setpoint == null
        || setpoint.desiredHeightRatio <= 0f
        || setpoint.desiredAreaRatio <= 0f) {
      return DistanceEstimate.unknown("setpoint not initialized");
    }

    RectF loc = target.getLocation();
    boolean rotated = sensorOrientation % 180 == 90;
    float imgWidth = rotated ? frameH : frameW;
    float imgHeight = rotated ? frameW : frameH;
    float boxHeight = rotated ? loc.width() : loc.height();
    float boxWidth = rotated ? loc.height() : loc.width();
    float boxBottom = rotated ? loc.right : loc.bottom;

    if (imgHeight <= 0 || imgWidth <= 0 || boxHeight <= 0) {
      return DistanceEstimate.unknown("degenerate bbox or image");
    }

    float currentHeightRatio = boxHeight / imgHeight;
    float currentAreaRatio = (boxWidth * boxHeight) / (imgWidth * imgHeight);
    float currentBottomRatio = boxBottom / imgHeight;

    if (currentHeightRatio < MIN_BBOX_HEIGHT_RATIO) {
      return DistanceEstimate.unknown("bbox too small: " + currentHeightRatio);
    }

    float heightScale = currentHeightRatio / setpoint.desiredHeightRatio;
    float areaScale =
        (float) Math.sqrt(currentAreaRatio / setpoint.desiredAreaRatio);
    float bottomShift = currentBottomRatio - setpoint.desiredBottomRatio;

    float logDiff =
        (float) Math.abs(Math.log(Math.max(1e-6f, heightScale) / Math.max(1e-6f, areaScale)));
    if (logDiff > UNKNOWN_HEIGHT_DISAGREE) {
      return new DistanceEstimate(
          heightScale,
          areaScale,
          bottomShift,
          DistanceState.UNKNOWN,
          0f,
          "height/area disagree: " + logDiff);
    }

    DistanceState state;
    if (heightScale < FAR_THRESHOLD) {
      state = DistanceState.TOO_FAR;
    } else if (heightScale > CLOSE_THRESHOLD) {
      state = DistanceState.TOO_CLOSE;
    } else {
      state = DistanceState.OK;
    }

    float consistency = 1f - Math.min(1f, Math.abs(heightScale - areaScale));
    return new DistanceEstimate(heightScale, areaScale, bottomShift, state, consistency, null);
  }
}
