package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ManualControlMotionEventTest {
  @Test
  public void oldPointerUpCannotReleaseReplacementDirection() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    MotionEvent firstDown = singlePointerEvent(MotionEvent.ACTION_DOWN, 4);
    MotionEvent replacementDown = singlePointerEvent(MotionEvent.ACTION_DOWN, 9);
    MotionEvent oldUp = singlePointerEvent(MotionEvent.ACTION_UP, 4);

    arbiter.press(
        ManualControlArbiter.Control.FORWARD,
        firstDown.getPointerId(firstDown.getActionIndex()));
    arbiter.press(
        ManualControlArbiter.Control.LEFT,
        replacementDown.getPointerId(replacementDown.getActionIndex()));

    assertFalse(
        arbiter.release(
            ManualControlArbiter.Control.FORWARD, oldUp.getPointerId(oldUp.getActionIndex())));
    assertEquals(ManualControlArbiter.Control.LEFT, arbiter.getActiveControl());
    assertEquals(9, arbiter.getActivePointerId());

    firstDown.recycle();
    replacementDown.recycle();
    oldUp.recycle();
  }

  private static MotionEvent singlePointerEvent(int action, int pointerId) {
    MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
    properties.id = pointerId;
    properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
    coords.x = 10f;
    coords.y = 10f;
    return MotionEvent.obtain(
        0L,
        1L,
        action,
        1,
        new MotionEvent.PointerProperties[] {properties},
        new MotionEvent.PointerCoords[] {coords},
        0,
        0,
        1f,
        1f,
        0,
        0,
        0,
        0);
  }
}
