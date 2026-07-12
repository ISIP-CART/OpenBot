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
        arbiter.press(ManualControlArbiter.Control.FORWARD);

    assertFalse(result.replacedActiveControl);
    assertEquals(ManualControlArbiter.Control.FORWARD, arbiter.getActiveControl());
    assertEquals(1L, result.generation);
  }

  @Test
  public void differentPressReplacesThePreviousControl() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.FORWARD);

    ManualControlArbiter.PressResult result = arbiter.press(ManualControlArbiter.Control.LEFT);

    assertTrue(result.replacedActiveControl);
    assertEquals(ManualControlArbiter.Control.LEFT, arbiter.getActiveControl());
    assertEquals(2L, result.generation);
  }

  @Test
  public void delayedReleaseFromReplacedControlIsIgnored() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.FORWARD);
    arbiter.press(ManualControlArbiter.Control.LEFT);

    assertFalse(arbiter.release(ManualControlArbiter.Control.FORWARD));
    assertEquals(ManualControlArbiter.Control.LEFT, arbiter.getActiveControl());
  }

  @Test
  public void activeReleaseStopsManualOwnership() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.RIGHT);

    assertTrue(arbiter.release(ManualControlArbiter.Control.RIGHT));
    assertEquals(null, arbiter.getActiveControl());
  }

  @Test
  public void clearInvalidatesAnyLaterRelease() {
    ManualControlArbiter arbiter = new ManualControlArbiter();
    arbiter.press(ManualControlArbiter.Control.BACKWARD);

    assertTrue(arbiter.clear());
    assertFalse(arbiter.release(ManualControlArbiter.Control.BACKWARD));
    assertEquals(null, arbiter.getActiveControl());
  }
}
