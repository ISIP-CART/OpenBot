package org.openbot.cartfollow;

public class SystemSafetyEvidence {
  public final boolean emergencyStop;
  public final boolean communicationOk;
  public final boolean detectorOk;
  public final String reason;

  public SystemSafetyEvidence(
      boolean emergencyStop, boolean communicationOk, boolean detectorOk, String reason) {
    this.emergencyStop = emergencyStop;
    this.communicationOk = communicationOk;
    this.detectorOk = detectorOk;
    this.reason = reason;
  }
}
