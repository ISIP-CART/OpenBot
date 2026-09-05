package org.openbot.vehicle;

/** Immutable R3-V1 snapshot. ESP32 emit time is diagnostic, never a phone-clock offset. */
public final class R3Snapshot {
  public final int sequence;
  public final long emitMs, receivedAtMs;
  public final Reading left, center, right;
  public static final class Reading {
    public final int mm, status, ageMs;
    private Reading(int mm, int status, int ageMs) {
      this.mm = mm; this.status = status; this.ageMs = ageMs;
    }
    public boolean usable(long receivedAtMs, long nowMs) {
      return status == 0 && mm >= 0 && mm <= 65535 && nowMs >= receivedAtMs
          && nowMs - receivedAtMs <= 200L - ageMs;
    }
    public String state(long receivedAtMs, long nowMs) {
      if (status == 0) return usable(receivedAtMs, nowMs) ? "VALID" : "STALE";
      return new String[] {"VALID", "RANGE_INVALID", "SIGNAL_INVALID", "STALE", "BUS_ERROR", "NOT_PRESENT"}[status];
    }
  }
  private R3Snapshot(int sequence, long emitMs, long receivedAtMs, Reading l, Reading c, Reading r) {
    this.sequence = sequence; this.emitMs = emitMs; this.receivedAtMs = receivedAtMs;
    left = l; center = c; right = r;
  }
  private static long number(String value, long max) {
    if (!value.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
    long parsed = Long.parseLong(value);
    if (parsed > max) throw new IllegalArgumentException();
    return parsed;
  }
  private static Reading reading(String[] f, int start) {
    int status = (int) number(f[start + 1], 5);
    int age = (int) number(f[start + 2], 65535);
    int mm;
    if (status == 0) {
      mm = (int) number(f[start], 65535);
      if (age > 200) throw new IllegalArgumentException();
    } else {
      if (!f[start].equals("-1")) throw new IllegalArgumentException();
      mm = -1;
    }
    return new Reading(mm, status, age);
  }
  public static R3Snapshot parse(String line, long receivedAtMs) {
    if (line == null || line.length() > 127 || receivedAtMs < 0) return null;
    String[] f = line.split(",", -1);
    if (f.length != 12 || !f[0].equals("!R3D")) return null;
    try {
      return new R3Snapshot((int) number(f[1], 65535), number(f[2], 4294967295L),
          receivedAtMs, reading(f, 3), reading(f, 6), reading(f, 9));
    } catch (IllegalArgumentException e) { return null; }
  }
  public boolean newerThan(R3Snapshot previous) {
    int delta = (sequence - previous.sequence) & 65535;
    return delta > 0 && delta < 32768;
  }
  private String describe(String name, Reading r, long now) {
    return name + ": " + (r.usable(receivedAtMs, now) ? r.mm + " mm" : "--")
        + " / " + r.state(receivedAtMs, now) + " / sampleAge=" + r.ageMs
        + " ms / receiveAge=" + Math.max(0L, now - receivedAtMs) + " ms";
  }
  public String display(long now) {
    return describe("左侧方", left, now) + "\n" + describe("中路", center, now)
        + "\n" + describe("右侧方", right, now);
  }
  private String fields(Reading r, long now) {
    return r.mm + "," + r.status + "," + r.ageMs + "," + r.usable(receivedAtMs, now);
  }
  public String diagnostic(long now) {
    return "seq=" + sequence + ";emit_ms=" + emitMs + ";received_ms=" + receivedAtMs
        + ";L_mm_status_age_usable=" + fields(left, now)
        + ";C_mm_status_age_usable=" + fields(center, now)
        + ";R_mm_status_age_usable=" + fields(right, now);
  }
}
