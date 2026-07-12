package org.openbot.cartfollow;

/** Routes pointer ownership and same-pointer slides into the manual control arbiter. */
final class ManualTouchRouter {
  private final ManualControlArbiter arbiter;

  ManualTouchRouter(ManualControlArbiter arbiter) {
    this.arbiter = arbiter;
  }

  ManualControlArbiter.PressResult press(
      ManualControlArbiter.Control control, int pointerId) {
    return arbiter.press(control, pointerId);
  }

  ManualControlArbiter.PressResult move(
      ManualControlArbiter.Control control, int pointerId) {
    if (arbiter.getActivePointerId() != pointerId
        || control == null
        || arbiter.getActiveControl() == control) {
      return null;
    }
    return arbiter.press(control, pointerId);
  }

  boolean release(int pointerId) {
    ManualControlArbiter.Control active = arbiter.getActiveControl();
    return active != null && arbiter.release(active, pointerId);
  }

  boolean clear() {
    return arbiter.clear();
  }

  ManualControlArbiter.Control getActiveControl() {
    return arbiter.getActiveControl();
  }

  int getActivePointerId() {
    return arbiter.getActivePointerId();
  }

  long getGeneration() {
    return arbiter.getGeneration();
  }
}
