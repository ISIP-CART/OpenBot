package org.openbot.vehicle;

import java.util.function.Consumer;

/** Telemetry negotiation only: never sends a drive, heartbeat, unlock or safety command. */
public final class R3TelemetrySession {
  public enum State { UNAVAILABLE, LEGACY, CONFIGURING, ACTIVE, DEGRADED }
  public static final class Status {
    public final State state;
    public final boolean advertised;
    public final String reason;
    public final R3Snapshot snapshot;
    private Status(State state, boolean advertised, String reason, R3Snapshot snapshot) {
      this.state = state; this.advertised = advertised; this.reason = reason; this.snapshot = snapshot;
    }
    public String display(long now) {
      return "R3 capability=" + advertised + " / config=" + state + " / " + reason
          + (snapshot == null ? "\n三路数据：不可用" : "\n" + snapshot.display(now));
    }
    public String diagnostic(long now) {
      return "capability=" + advertised + ";config=" + state + ";reason=" + reason + ";"
          + (snapshot == null ? "snapshot=unavailable" : snapshot.diagnostic(now));
    }
  }
  private State state = State.UNAVAILABLE;
  private boolean advertised, discovered;
  private String reason = "awaiting_capability";
  private long deadline;
  private int retries;
  private R3Snapshot snapshot;
  public synchronized void reset() {
    state = State.UNAVAILABLE; advertised = discovered = false;
    reason = "awaiting_capability"; snapshot = null; retries = 0; deadline = 0;
  }
  public synchronized Status status() { return new Status(state, advertised, reason, snapshot); }
  public synchronized void discover(boolean r3, boolean legacy, long now, Consumer<String> send) {
    if (discovered) return;
    discovered = true; advertised = r3;
    if (!r3) {
      state = legacy ? State.LEGACY : State.UNAVAILABLE;
      reason = legacy ? "legacy_s" : "no_range_capability";
      if (legacy) send.accept("s100\n");
      return;
    }
    state = State.CONFIGURING; reason = "awaiting_ack"; deadline = now + 1000;
    send.accept("s0\n"); send.accept("!R3,100\n");
  }
  private void degrade(String why, Consumer<String> send) {
    state = State.DEGRADED; reason = why; snapshot = null;
    send.accept("!R3,0\n"); send.accept("s100\n");
  }
  public synchronized void poll(long now, Consumer<String> send) {
    if (state != State.CONFIGURING || now < deadline) return;
    if (retries++ == 0) {
      reason = "retry_awaiting_ack"; deadline = now + 1000; send.accept("!R3,100\n");
    } else degrade("config_timeout", send);
  }
  public synchronized boolean accept(String line, long now, Consumer<String> send) {
    if (line == null) return false;
    if (line.equals("!ERR,bad_r3_config")) {
      if (state == State.CONFIGURING) degrade("bad_r3_config", send);
      return true;
    }
    if (line.startsWith("!R3,")) {
      if (state == State.CONFIGURING && line.equals("!R3,OK,100")) {
        state = State.ACTIVE; reason = "configured_waiting_data";
      }
      return true;
    }
    if (line.startsWith("!R3D")) {
      R3Snapshot candidate = R3Snapshot.parse(line, now);
      if (state == State.ACTIVE && candidate != null
          && (snapshot == null || candidate.newerThan(snapshot))) {
        snapshot = candidate; reason = "configured";
      }
      return true;
    }
    return false;
  }
}
