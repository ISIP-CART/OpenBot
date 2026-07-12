package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManualTouchRouterTest {
  @Test
  public void activePointerCanSlideToAnotherDirection() {
    ManualTouchRouter router = new ManualTouchRouter(new ManualControlArbiter());
    router.press(ManualControlArbiter.Control.FORWARD, 4);

    ManualControlArbiter.PressResult result =
        router.move(ManualControlArbiter.Control.LEFT, 4);

    assertNotNull(result);
    assertTrue(result.replacedActiveControl);
    assertEquals(ManualControlArbiter.Control.LEFT, router.getActiveControl());
  }

  @Test
  public void oldPointerMoveAndReleaseCannotReplaceNewPointer() {
    ManualTouchRouter router = new ManualTouchRouter(new ManualControlArbiter());
    router.press(ManualControlArbiter.Control.FORWARD, 4);
    router.press(ManualControlArbiter.Control.RIGHT, 9);

    assertNull(router.move(ManualControlArbiter.Control.LEFT, 4));
    assertFalse(router.release(4));
    assertEquals(ManualControlArbiter.Control.RIGHT, router.getActiveControl());
    assertEquals(9, router.getActivePointerId());
  }

  @Test
  public void activePointerReleaseStopsOwnership() {
    ManualTouchRouter router = new ManualTouchRouter(new ManualControlArbiter());
    router.press(ManualControlArbiter.Control.BACKWARD, 2);

    assertTrue(router.release(2));
    assertNull(router.getActiveControl());
  }
}
