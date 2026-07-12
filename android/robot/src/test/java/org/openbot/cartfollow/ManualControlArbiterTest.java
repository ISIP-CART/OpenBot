package org.openbot.cartfollow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManualControlArbiterTest {
  @Test
  public void firstPressBecomesActiveWithoutReplacement() {
    ManualControlArbiter arbiter = new ManualControlArbiter();

    ManualControlArbiter.PressResult result =
        arbiter.press(ManualControlArbiter.Control.FORWARD, 0);

    assertFalse(result.replacedActiveControl);
    assertEquals(ManualControlArbiter.Control.FORWARD, arbiter.getActiveControl());
    assertEquals(1L, result.generation);
  }

  @Test
  public void differentPressReplacesThePreviousControl() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.FORWARD, 0);

    ManualControlArbiter.PressResult result = arbiter.press(ManualControlArbiter.Control.LEFT, 1);

    assertTrue(result.replacedActiveControl);
    assertEquals(ManualControlArbiter.Control.LEFT, arbiter.getActiveControl());
    assertEquals(2L, result.generation);
  }

  @Test
  public void delayedReleaseFromReplacedControlIsIgnored() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.FORWARD, 0);
    arbiter.press(ManualControlArbiter.Control.LEFT, 1);

    assertFalse(arbiter.release(ManualControlArbiter.Control.FORWARD, 0));
    assertEquals(ManualControlArbiter.Control.LEFT, arbiter.getActiveControl());
  }

  @Test
  public void activeReleaseStopsManualOwnership() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.RIGHT, 3);

    assertTrue(arbiter.release(ManualControlArbiter.Control.RIGHT, 3));
    assertEquals(null, arbiter.getActiveControl());
  }

  @Test
  public void clearInvalidatesAnyLaterRelease() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.BACKWARD, 2);

    assertTrue(arbiter.clear());
    assertFalse(arbiter.release(ManualControlArbiter.Control.BACKWARD, 2));
    assertEquals(null, arbiter.getActiveControl());
  }

  @Test
  public void releaseFromOldPointerOnSameButtonIsIgnored() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.FORWARD, 0);
    arbiter.press(ManualControlArbiter.Control.FORWARD, 1);

    assertFalse(arbiter.release(ManualControlArbiter.Control.FORWARD, 0));
    assertEquals(1, arbiter.getActivePointerId());
    assertEquals(ManualControlArbiter.Control.FORWARD, arbiter.getActiveControl());
  }
}
