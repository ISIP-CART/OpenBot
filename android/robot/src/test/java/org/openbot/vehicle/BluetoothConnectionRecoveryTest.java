package org.openbot.vehicle;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BluetoothConnectionRecoveryTest {
  @Test
  public void onlyOneAutomaticRetryIsAllowed() {
    assertTrue(BluetoothManager.shouldAutoRetry(0));
    assertFalse(BluetoothManager.shouldAutoRetry(1));
  }

  @Test
  public void staleGenerationOrDifferentAddressIsRejected() {
    assertTrue(BluetoothManager.isMatchingAttempt(4L, 4L, "AA:BB", "AA:BB"));
    assertFalse(BluetoothManager.isMatchingAttempt(3L, 4L, "AA:BB", "AA:BB"));
    assertFalse(BluetoothManager.isMatchingAttempt(4L, 4L, "CC:DD", "AA:BB"));
    assertFalse(BluetoothManager.isMatchingAttempt(4L, 4L, null, "AA:BB"));
  }
}
