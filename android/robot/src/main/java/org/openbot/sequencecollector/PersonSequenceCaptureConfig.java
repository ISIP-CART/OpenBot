package org.openbot.sequencecollector;

public class PersonSequenceCaptureConfig {
  public String personId = "";
  public long frameLogIntervalMs = 200;
  public long cropIntervalMs = 500;
  public long overlayIntervalMs = 1000;
  public float minConfidence = 0.5f;
  public boolean saveCrops = true;
  public boolean saveOverlays = false;
  public float paddingRatio = 0.08f;
  public int jpegQuality = 90;
}
