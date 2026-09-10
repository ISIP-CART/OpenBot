package org.openbot.cartfollow;

/** Tracks detector startup without allowing a rejected task to leave loading stuck forever. */
final class InferenceStartupCoordinator {
  static final long LOAD_TIMEOUT_MS = 5_000L;
  static final int MAX_TIMEOUT_RETRIES = 1;

  enum TimeoutOutcome {
    NONE,
    RETRY,
    FAILED
  }

  private boolean pending;
  private long pendingSinceMs = -1L;
  private int timeoutRetries;

  synchronized boolean begin(long nowMs) {
    if (pending) return false;
    pending = true;
    pendingSinceMs = nowMs;
    return true;
  }

  synchronized void submissionRejected() {
    pending = false;
    pendingSinceMs = -1L;
  }

  synchronized void complete() {
    pending = false;
    pendingSinceMs = -1L;
  }

  synchronized TimeoutOutcome checkTimeout(long nowMs) {
    if (!pending || pendingSinceMs < 0L || nowMs < pendingSinceMs) {
      return TimeoutOutcome.NONE;
    }
    if (nowMs - pendingSinceMs < LOAD_TIMEOUT_MS) return TimeoutOutcome.NONE;
    pending = false;
    pendingSinceMs = -1L;
    if (timeoutRetries < MAX_TIMEOUT_RETRIES) {
      timeoutRetries++;
      return TimeoutOutcome.RETRY;
    }
    return TimeoutOutcome.FAILED;
  }

  synchronized boolean isPending() {
    return pending;
  }

  synchronized long pendingAgeMs(long nowMs) {
    if (!pending || pendingSinceMs < 0L || nowMs < pendingSinceMs) return -1L;
    return nowMs - pendingSinceMs;
  }

  synchronized int timeoutRetries() {
    return timeoutRetries;
  }

  synchronized void reset() {
    pending = false;
    pendingSinceMs = -1L;
    timeoutRetries = 0;
  }
}
