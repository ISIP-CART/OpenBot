package org.openbot.vehicle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class BleSerialWriteQueueTest {
  @Test
  public void allowsOnlyOneGattWriteInFlight() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c14,14\n", 1);
    harness.queue.enqueue(BleSerialWriteQueue.Type.HEARTBEAT, "h750\n", 0);

    assertEquals(list("c14,14\n"), harness.sent);
    harness.queue.onWriteComplete(true);
    assertEquals(list("c14,14\n", "h750\n"), harness.sent);
  }

  @Test
  public void transitionKeepsStopAndNewDirectionAdjacent() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.QUERY, "f\n", 0);
    harness.queue.enqueue(BleSerialWriteQueue.Type.HEARTBEAT, "h750\n", 0);
    harness.queue.enqueueTransition("c0,0\n", "c-5,5\n", 2);

    harness.queue.onWriteComplete(true);
    harness.queue.onWriteComplete(true);
    harness.queue.onWriteComplete(true);
    assertEquals(list("f\n", "c0,0\n", "c-5,5\n", "h750\n"), harness.sent);
  }

  @Test
  public void periodicRepeatCannotSplitPendingTransition() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.QUERY, "f\n", 0);
    harness.queue.enqueue(BleSerialWriteQueue.Type.HEARTBEAT, "h750\n", 0);
    harness.queue.enqueueTransition("c0,0\n", "c-5,5\n", 2);
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c-5,5\n", 3);

    harness.queue.onWriteComplete(true);
    harness.queue.onWriteComplete(true);
    harness.queue.onWriteComplete(true);
    assertEquals(list("f\n", "c0,0\n", "c-5,5\n", "h750\n"), harness.sent);
  }

  @Test
  public void latestMotionReplacesPendingOldMotion() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.QUERY, "f\n", 0);
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c14,14\n", 1);
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c5,-5\n", 2);

    harness.queue.onWriteComplete(true);
    assertEquals(list("f\n", "c5,-5\n"), harness.sent);
  }

  @Test
  public void emergencyClearsPendingAndRunsNext() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.QUERY, "f\n", 0);
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c14,14\n", 1);
    harness.queue.enqueue(BleSerialWriteQueue.Type.EMERGENCY, "!S,1\n", 0);

    harness.queue.onWriteComplete(true);
    assertEquals(list("f\n", "!S,1\n"), harness.sent);
    assertEquals(0, harness.queue.getPendingCount());
  }

  @Test
  public void criticalFailureRetriesOnceThenClearsQueue() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.STOP, "c0,0\n", 1);
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c14,14\n", 2);

    harness.queue.onWriteComplete(false);
    assertEquals(list("c0,0\n", "c0,0\n"), harness.sent);
    harness.queue.onWriteComplete(false);

    assertEquals(1, harness.criticalFailures);
    assertEquals(0, harness.queue.getPendingCount());
    assertFalse(harness.queue.hasInFlight());
  }

  @Test
  public void clearDropsInFlightAndPendingState() {
    Harness harness = new Harness();
    harness.queue.enqueue(BleSerialWriteQueue.Type.MOTION, "c14,14\n", 1);
    harness.queue.enqueue(BleSerialWriteQueue.Type.HEARTBEAT, "h750\n", 0);
    harness.queue.clear();

    assertFalse(harness.queue.hasInFlight());
    assertEquals(0, harness.queue.getPendingCount());
    assertTrue(harness.queue.getStatus().contains("cleared"));
  }

  private static final class Harness {
    final List<String> sent = new ArrayList<>();
    int criticalFailures;
    final BleSerialWriteQueue queue =
        new BleSerialWriteQueue(sent::add, payload -> criticalFailures++);
  }

  private static List<String> list(String... values) {
    List<String> result = new ArrayList<>();
    for (String value : values) result.add(value);
    return result;
  }
}
