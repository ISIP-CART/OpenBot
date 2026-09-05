package org.openbot.vehicle;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class R3TelemetryTest {
  private static final String GOLD = "!R3D,0,15340,640,0,22,1350,0,18,610,0,25";
  @Test public void goldenLinesKeepFixedSidesAndIndependentValidity() {
    R3Snapshot s = R3Snapshot.parse(GOLD, 1000);
    assertNotNull(s); assertEquals(640, s.left.mm); assertEquals(1350, s.center.mm); assertEquals(610, s.right.mm);
    assertTrue(s.left.usable(1000, 1178)); assertFalse(s.left.usable(1000, 1179));
    assertTrue(s.center.usable(1000, 1182)); assertFalse(s.center.usable(1000, 1183));
    assertFalse(s.left.usable(1000, 999));
    s = R3Snapshot.parse("!R3D,1,15440,-1,2,122,1340,0,18,605,0,25", 1100);
    assertFalse(s.left.usable(1100,1100)); assertTrue(s.center.usable(1100,1100));
    assertEquals("SIGNAL_INVALID", s.left.state(1100,1100));
    s = R3Snapshot.parse("!R3D,2,15540,-1,3,222,1330,0,18,-1,5,65535",1200);
    assertEquals("NOT_PRESENT", s.right.state(1200,1200));
    assertFalse(s.right.usable(1200,1200));
  }
  @Test public void rejectsMalformedFieldsAndInconsistentStatus() {
    for (String bad : new String[] {GOLD + ",0", GOLD.substring(0,GOLD.lastIndexOf(',')),
        GOLD.replace("15340","4294967296"), GOLD.replace("!R3D,0,","!R3D,65536,"),
        GOLD.replace("640,0,22","-1,0,22"), GOLD.replace("640,0,22","640,2,22"),
        GOLD.replace("640,0,22","640,0,201"), GOLD.replace("640,0,22","65536,0,22"),
        GOLD.replace("640,0,22","-1,6,22"), GOLD.replace("640,0,22","-1,5,65536"),
        GOLD.replace("640","6.4"), GOLD.replace("640","+640"), GOLD.replace("640"," 640"),
        GOLD.replace("15340","-1")}) assertNull(bad, R3Snapshot.parse(bad,1000));
    assertEquals(0, R3Snapshot.parse(GOLD.replace("640,0,22","0,0,200"),1000).left.mm);
  }
  @Test public void defaultMtuChunksAndCoalescedSnapshotsWork() {
    SerialLineAccumulator a = new SerialLineAccumulator();
    String stream = "!R3,OK,100\n" + GOLD + "\n" + GOLD.replace("!R3D,0,","!R3D,1,") + "\n";
    List<String> out = new ArrayList<>();
    for (int i=0;i<stream.length();i+=20) out.addAll(a.accept(stream.substring(i,Math.min(i+20,stream.length()))));
    assertEquals(3,out.size()); assertNotNull(R3Snapshot.parse(out.get(1),100));
    a.accept("!R3D,99,"); a.clear(); assertEquals(Collections.singletonList(GOLD),a.accept(GOLD+"\n"));
  }
  private R3TelemetrySession active(List<String> sent) {
    R3TelemetrySession s = new R3TelemetrySession();
    s.discover(true,true,0,sent::add);
    s.accept("!R3,OK,100",10,sent::add); return s;
  }
  @Test public void negotiationIsIdempotentAndOnlyUsesTelemetryCommands() {
    List<String> sent = new ArrayList<>(); R3TelemetrySession s = active(sent);
    assertEquals(Arrays.asList("s0\n","!R3,100\n"),sent);
    s.discover(true,true,50,sent::add); s.poll(9000,sent::add);
    assertEquals(2,sent.size()); assertEquals(R3TelemetrySession.State.ACTIVE,s.status().state);
    assertNull(s.status().snapshot);
  }
  @Test public void retryOnceThenDegradeAndIgnoreLateReply() {
    List<String> sent = new ArrayList<>(); R3TelemetrySession s = new R3TelemetrySession();
    s.discover(true,true,0,sent::add); s.poll(999,sent::add); assertEquals(2,sent.size());
    s.poll(1000,sent::add); s.poll(1999,sent::add); assertEquals(3,sent.size());
    s.poll(2000,sent::add); s.poll(9999,sent::add);
    assertEquals(Arrays.asList("s0\n","!R3,100\n","!R3,100\n","!R3,0\n","s100\n"),sent);
    assertEquals("config_timeout",s.status().reason);
    s.accept("!R3,OK,100",2100,sent::add); s.accept(GOLD,2200,sent::add);
    assertEquals(R3TelemetrySession.State.DEGRADED,s.status().state); assertNull(s.status().snapshot);
  }
  @Test public void configErrorFallsBackButMotorErrorIsNotAConfigFailure() {
    List<String> sent = new ArrayList<>(); R3TelemetrySession s = new R3TelemetrySession();
    s.discover(true,true,0,sent::add);
    assertFalse(s.accept("!ERR,settling",1,sent::add));
    assertEquals(R3TelemetrySession.State.CONFIGURING,s.status().state);
    s.accept("!R3,OK,100,extra",2,sent::add);
    assertEquals(R3TelemetrySession.State.CONFIGURING,s.status().state);
    s.accept("!ERR,bad_r3_config",3,sent::add);
    assertEquals("bad_r3_config",s.status().reason); assertEquals("s100\n",sent.get(sent.size()-1));
  }
  @Test public void oldFirmwareGetsNoExtensionAndReconnectResetsSequence() {
    List<String> sent = new ArrayList<>(); R3TelemetrySession s = new R3TelemetrySession();
    s.discover(false,true,0,sent::add); assertEquals(Collections.singletonList("s100\n"),sent);
    s.reset(); s.discover(true,true,100,sent::add); s.accept("!R3,OK,100",110,sent::add);
    s.accept(GOLD.replace("!R3D,0,","!R3D,100,"),120,sent::add);
    s.reset(); assertNull(s.status().snapshot); assertFalse(s.status().advertised);
    s.discover(true,true,200,sent::add); s.accept("!R3,OK,100",210,sent::add); s.accept(GOLD,220,sent::add);
    assertEquals(0,s.status().snapshot.sequence);
  }
  @Test public void oldAndDuplicateSnapshotsCannotRefreshFreshnessAndClockWrapIsAllowed() {
    List<String> sent = new ArrayList<>(); R3TelemetrySession s = active(sent);
    s.accept(GOLD.replace("!R3D,0,15340", "!R3D,65535,4294967295"),100,sent::add);
    s.accept(GOLD.replace("15340","0"),200,sent::add);
    R3Snapshot accepted = s.status().snapshot; assertEquals(0,accepted.sequence); assertEquals(0,accepted.emitMs);
    s.accept(GOLD,300,sent::add);
    s.accept(GOLD.replace("!R3D,0,","!R3D,65535,"),400,sent::add);
    s.accept(GOLD.replace("!R3D,0,","!R3D,32768,"),450,sent::add);
    assertSame(accepted,s.status().snapshot); assertFalse(accepted.left.usable(accepted.receivedAtMs,450));
    assertTrue(s.status().display(450).contains("STALE"));
    assertTrue(s.status().diagnostic(450).contains("L_mm_status_age_usable=640,0,22,false"));
  }
}
