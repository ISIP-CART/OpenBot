package org.openbot.cropcollector;

public class PersonCropCaptureConfig {
  public String personId = "";
  public long intervalMs = 500;
  public float minConfidence = 0.5f;
  public boolean singlePersonOnly = true;
  public float paddingRatio = 0.08f;
  public int maxCrops = 120;
  public int jpegQuality = 95;
}
