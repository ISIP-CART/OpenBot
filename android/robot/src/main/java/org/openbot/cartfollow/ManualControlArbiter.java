package org.openbot.cartfollow;

/**
 * Owns the single active manual direction control for the real-cart screen.
 *
 * <p>A newly pressed direction replaces the preceding one. Releases are accepted only from the
 * currently active direction, so a delayed release from an old button cannot stop or overwrite a
 * newer command.
 */
final class ManualControlArbiter {
  enum Control {
    FORWARD,
    BACKWARD,
    LEFT,
    RIGHT
  }

  static final class PressResult {
    final boolean replacedActiveControl;
    final long generation;

    private PressResult(boolean replacedActiveControl, long generation) {
      this.replacedActiveControl = replacedActiveControl;
      this.generation = generation;
    }
  }

  private Control activeControl;
  private int activePointerId = -1;
  private long generation;

  synchronized PressResult press(Control control, int pointerId) {
    if (control == null) throw new IllegalArgumentException("control must not be null");
    if (pointerId < 0) throw new IllegalArgumentException("pointerId must be non-negative");

    boolean replaced = activeControl != null && (activeControl != control || activePointerId != pointerId);
    if (activeControl != control || activePointerId != pointerId) generation++;
    activeControl = control;
    activePointerId = pointerId;
    return new PressResult(replaced, generation);
  }

  synchronized boolean release(Control control, int pointerId) {
    if (control == null || activeControl != control || activePointerId != pointerId) return false;
    activeControl = null;
    activePointerId = -1;
    generation++;
    return true;
  }

  synchronized boolean clear() {
    if (activeControl == null) return false;
    activeControl = null;
    activePointerId = -1;
    generation++;
    return true;
  }

  synchronized Control getActiveControl() {
    return activeControl;
  }

  synchronized int getActivePointerId() {
    return activePointerId;
  }

  synchronized long getGeneration() {
    return generation;
  }
}
