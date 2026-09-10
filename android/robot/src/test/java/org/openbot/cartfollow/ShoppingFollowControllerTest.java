package org.openbot.cartfollow;

import static org.junit.Assert.*;
import android.graphics.RectF;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.vehicle.*;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ShoppingFollowControllerTest {
  private long sequence;
  private R3TelemetrySession session=new R3TelemetrySession();
  private ShoppingFollowController controller=new ShoppingFollowController();
  private R3TelemetrySession.Status ranges(long now,int left,int center,int right) {
    session.discover(true,true,0,s->{}); session.accept("!R3,OK,100",0,s->{});
    session.accept("!R3D,"+(sequence++ & 65535)+",0,"+reading(left)+","+reading(center)+","+reading(right),now,s->{});
    return session.status();
  }
  private String reading(int mm) { return mm<0?"-1,2,65535":mm+",0,0"; }
  private R3TelemetrySession.Status fieldLogRanges(long now,int center) {
    session.discover(true,true,0,s->{}); session.accept("!R3,OK,100",0,s->{});
    session.accept("!R3D,"+(sequence++ & 65535)+",0,-1,1,65535,"+center+",0,0,-1,1,65535",now,s->{});
    return session.status();
  }
  private R3TelemetrySession.Status statusRanges(long now,int leftStatus,int centerStatus,int rightStatus) {
    session.discover(true,true,0,s->{}); session.accept("!R3,OK,100",0,s->{});
    session.accept("!R3D,"+(sequence++ & 65535)+",0,-1,"+leftStatus+",65535,-1,"+centerStatus+",65535,-1,"+rightStatus+",65535",now,s->{});
    return session.status();
  }
  private FollowStateMachine.FrameResult frame(long now,float width,float error,float rate,boolean visible) {
    return frame(now,width,error,rate,visible,1);
  }
  private FollowStateMachine.FrameResult frame(long now,float width,float error,float rate,boolean visible,int trackId) {
    FollowStateMachine.FrameResult f=new FollowStateMachine.FrameResult(visible?FollowState.FOLLOW:FollowState.IDENTITY_UNCERTAIN,
        new Control(0,0),null,null,new ArrayList<>(visible?Collections.singletonList(null):Collections.emptyList()),visible,false,null,0);
    f.frameSequence=++sequence; f.frameTiming=new FrameTimingEvidence(now,0,0,0,0,0,0,0);
    f.behaviorDecision=new BehaviorDecisionResult(f.state,visible?BehaviorAction.FOLLOW_SLOW:BehaviorAction.MOTION_STOP,"test",null,1);
    f.simulatorIdentity=new SimulatorIdentityGuard.Decision(visible,false,trackId,3,"verified");
    if(visible) {
      f.targetObservation=new TargetObservationEvidence(new RectF(.5f+error/2-width/2,-.2f,.5f+error/2+width/2,1.2f),trackId,now,1,false,true,1,"test");
      f.steeringEvidence=new SteeringEvidence(true,"test",error,error,rate,error,0,20,
          Math.abs(error)<.06f?SteeringEvidence.Direction.NONE:error>0?SteeringEvidence.Direction.RIGHT:SteeringEvidence.Direction.LEFT,
          SteeringEvidence.Level.SLIGHT,0);
    }
    return f;
  }
  private ShoppingFollowController.Output step(long now,float width,float error,float rate,boolean visible,int l,int c,int r,float yaw,boolean angled) {
    controller.environment(ranges(now,l,c,r),yaw,true,angled);
    return controller.update(frame(now,width,error,rate,visible),now);
  }
  @Test public void widthCanAdvanceBeyondFullBodyReferenceAndStopsAtCloseWidth() {
    ShoppingFollowController.Output out=null;
    for(int i=0;i<6;i++) out=step(i*100,.5f,0,0,true,900,900,900,0,false);
    assertTrue(out.left>0 && out.right>0); assertTrue(out.left<=18);
    for(int i=6;i<24;i++) out=step(i*100,.70f,0,0,true,900,900,900,0,false);
    assertEquals("width_hold",out.reason); assertEquals(0,out.left);
    for(int i=24;i<31;i++) out=step(i*100,.60f,0,0,true,900,900,900,0,false);
    assertEquals(0,out.left);
    for(int i=31;i<44;i++) out=step(i*100,.50f,0,0,true,900,900,900,0,false);
    assertTrue(out.left>0);
    assertTrue(controller.diagnostic().contains("width_start=0.55"));
    assertTrue(controller.diagnostic().contains("width_stop=0.65"));
  }
  @Test public void closeFollowGearBandsAreReportedBeforeSafetyCaps() {
    float[] widths={.30f,.45f,.525f,.575f,.625f}; int[] gears={21,18,14,10,8};
    for(int j=0;j<widths.length;j++) {
      controller=new ShoppingFollowController(); session=new R3TelemetrySession(); sequence=0;
      for(int i=0;i<5;i++) step(i*100,widths[j],0,0,true,900,900,900,0,false);
      assertTrue(controller.diagnostic().contains("desired_gear="+gears[j]));
    }
  }
  @Test public void exactCloseStopAndResumeHysteresisRemainBounded() {
    ShoppingFollowController.Output out=null;
    for(int i=0;i<5;i++) out=step(i*100,.65f,0,0,true,900,900,900,0,false);
    assertEquals("width_hold",out.reason);assertEquals(0,out.left);
    for(int i=5;i<25;i++) out=step(i*100,.55f,0,0,true,900,900,900,0,false);
    assertEquals(0,out.left);
    for(int i=25;i<55;i++) out=step(i*100,.549f,0,0,true,900,900,900,0,false);
    assertTrue(out.left>0&&out.right>0);
  }
  @Test public void leftAndRightClippingNeverProduceForwardTranslation() {
    for(float error:new float[]{-.65f,.65f}) {
      controller=new ShoppingFollowController();session=new R3TelemetrySession();sequence=0;
      ShoppingFollowController.Output out=null;
      for(int i=0;i<5;i++) out=step(i*100,.45f,error,0,true,900,900,900,0,false);
      assertEquals(0,out.left+out.right);
      assertTrue(out.reason.startsWith("shopping_visible_aim")||out.reason.equals("width_unreliable"));
    }
  }
  @Test public void suddenBodyNarrowingDoesNotTriggerAcceleration() {
    for(int i=0;i<6;i++) step(i*100,.5f,0,0,true,900,900,900,0,false);
    ShoppingFollowController.Output out=step(600,.2f,0,0,true,900,900,900,0,false);
    assertEquals("width_unreliable",out.reason); assertEquals(0,out.left);
  }
  @Test public void frontStopNeedsThreeNewClearSamplesAndPollChecksStaleness() {
    for(int i=0;i<6;i++) step(i*100,.4f,0,0,true,900,900,900,0,false);
    assertEquals(0,step(600,.4f,0,0,true,900,300,900,0,false).left);
    assertEquals(0,step(700,.4f,0,0,true,900,450,900,0,false).left);
    assertEquals(0,controller.poll(720).left);
    assertEquals(0,step(800,.4f,0,0,true,900,450,900,0,false).left);
    assertTrue(step(900,.4f,0,0,true,900,450,900,0,false).left>0);
    assertTrue(controller.poll(1101).left>0);
    assertEquals(0,controller.poll(1301).left);
  }
  @Test public void invalidFrontEchoRestoresNormalVisibleSpeed() {
    ShoppingFollowController.Output out=null;
    for(int i=0;i<10;i++) out=step(i*100,.2f,0,0,true,900,350,900,0,false);
    assertTrue(out.left>0 && out.left<=6);
    for(int i=10;i<18;i++) out=step(i*100,.2f,0,0,true,-1,-1,-1,0,false);
    assertTrue(out.left>6);
    assertTrue(out.reason.endsWith("_range_assumed_clear"));
  }
  @Test public void latestFieldLogPatternDoesNotBlockVisibleFollow() {
    ShoppingFollowController.Output out=null;
    for(int i=0;i<8;i++) {
      long now=i*100; controller.environment(fieldLogRanges(now,1600),0,true,false);
      out=controller.update(frame(now,.2f,-.10f,0,true),now);
    }
    assertTrue(out.left>0 && out.right>0);
    assertTrue(out.reason.endsWith("_range_assumed_clear"));
  }
  @Test public void notPresentDoesNotBlockVisibleFollowButBusErrorDoes() {
    ShoppingFollowController.Output out=null;
    for(int i=0;i<6;i++) {
      long now=i*100; controller.environment(statusRanges(now,2,2,5),0,true,false);
      out=controller.update(frame(now,.2f,0,0,true),now);
    }
    assertTrue(out.left>0); assertTrue(out.reason.endsWith("_range_assumed_clear"));
    long now=700; controller.environment(statusRanges(now,2,4,5),0,true,false);
    out=controller.update(frame(now,.2f,0,0,true),now);
    assertEquals(0,out.left); assertEquals("range_sensor_fault",out.reason);
  }
  @Test public void invalidEchoCannotReleaseKnownNearObstacle() {
    for(int i=0;i<6;i++) step(i*100,.4f,0,0,true,900,900,900,0,false);
    assertEquals("range_front_near_latched",step(600,.4f,0,0,true,-1,250,-1,0,false).reason);
    for(int i=7;i<12;i++) {
      ShoppingFollowController.Output out=step(i*100,.4f,0,0,true,-1,-1,-1,0,false);
      assertEquals(0,out.left); assertEquals("range_front_near_latched",out.reason);
    }
  }
  private void corner(int sign,boolean angled) {
    for(int i=0;i<6;i++) step(i*100,.2f,.5f*sign,.2f*sign,true,sign<0?500:900,900,sign>0?500:900,0,angled);
    step(600,.2f,.55f*sign,.2f*sign,true,900,900,900,0,angled);
    assertEquals(0,step(700,0,0,0,false,900,900,900,0,angled).left);
  }
  @Test public void shelfOpeningProducesMirroredForwardArcsAndBoundedSegments() {
    corner(1,true);
    ShoppingFollowController.Output out=step(1400,0,0,0,false,900,900,900,0,true);
    assertEquals("corner_forward_arc",out.reason); assertTrue(out.left>out.right && out.right>0);
    controller.environment(ranges(1650,900,900,900),-5,true,true);
    assertEquals(0,controller.poll(1650).left);
    assertEquals(0,step(1700,0,0,0,false,900,900,900,-5,true).left);
    assertTrue(step(1800,0,0,0,false,900,900,900,-5,true).left>0);
    assertEquals(0,controller.poll(6800).left);
    controller=new ShoppingFollowController(); session=new R3TelemetrySession();
    corner(-1,true);
    out=step(1400,0,0,0,false,900,900,900,0,true);
    assertTrue(out.right>out.left && out.left>0);
  }
  @Test public void noInstallationConfirmationNeverAllowsOccludedForward() {
    corner(1,false);
    for(int i=14;i<30;i++) {
      ShoppingFollowController.Output out=step(i*100,0,0,0,false,900,900,900,0,false);
      assertTrue(out.left+out.right<=0);
    }
  }
  @Test public void lostWithoutCornerOrExitEvidenceNeverAdvances() {
    for(int i=0;i<6;i++) step(i*100,.4f,0,0,true,900,900,900,0,true);
    assertEquals(0,step(700,0,0,0,false,900,900,900,0,true).left);
  }
  @Test public void personAmbiguityAndGyroLossAbortExploration() {
    corner(1,true); step(1400,0,0,0,false,900,900,900,0,true);
    FollowStateMachine.FrameResult f=frame(1500,0,0,0,false);
    f.persons.add(null);
    controller.environment(ranges(1500,900,900,900),-5,true,true);
    assertEquals(0,controller.update(f,1500).left);
    assertTrue(controller.diagnostic().contains("PARKED"));
    controller=new ShoppingFollowController(); session=new R3TelemetrySession();corner(1,true);
    controller.environment(ranges(1400,900,900,900),0,false,true);
    assertEquals(0,controller.poll(1400).left);
  }
  @Test public void invalidSideDoesNotCountAsOpeningAndCannotAuthorizeForward() {
    for(int i=0;i<6;i++) step(i*100,.2f,.5f,.2f,true,900,900,500,0,true);
    step(600,.2f,.55f,.2f,true,900,900,-1,0,true);
    step(700,0,0,0,false,900,900,-1,0,true);
    for(int i=14;i<25;i++) {
      ShoppingFollowController.Output out=step(i*100,0,0,0,false,900,900,-1,0,true);
      assertTrue(out.left+out.right<=0);
    }
  }
  private RealCartSafetyController readySafety() {
    RealCartSafetyController s=new RealCartSafetyController();s.setForeground(true);s.setConnection(true,true);
    s.setMode(RealCartSafetyController.Mode.AUTO);assertTrue(s.unlockAuto());s.setAutoRunEnabled(true,0);return s;
  }
  @Test public void realAndSimulatorUseSamePolicyAndEmergencyWins() {
    RealCartSafetyController safety=readySafety(); SimulatorAutoDriveController sim=new SimulatorAutoDriveController();
    for(int i=0;i<6;i++) {
      long now=i*100; R3TelemetrySession.Status r=ranges(now,900,900,900);
      FollowStateMachine.FrameResult f=frame(now,.4f,0,0,true);
      f.r3Telemetry=r;f.shoppingGyroFresh=true;
      safety.setShoppingEnvironment(r,0,true,false);
      RealCartSafetyController.Output a=safety.auto(f,now);SimulatorAutoDriveController.Result b=sim.update(f,now);
      assertEquals(a.left,b.left);assertEquals(a.right,b.right);
    }
    safety.latchEmergency();assertTrue(safety.refresh(550,null).isStop());
  }
  @Test public void globalIdentityEvidenceHandsAuthorizedNewTrackToRealMotionAfterThreeFrames() {
    RealCartSafetyController safety=readySafety();
    for(int i=0;i<5;i++) {
      long now=i*100;safety.setShoppingEnvironment(ranges(now,900,900,900),0,true,false);
      safety.auto(frame(now,.35f,0,0,true,1),now);
    }
    SimulatorIdentityGuard guard=new SimulatorIdentityGuard();guard.begin(7);
    SimulatorIdentityGuard.Decision permit=null;
    for(int i=1;i<=5;i++) {
      long now=500+i*300; long observation=100+i;
      ReIDMatchResult score=new ReIDMatchResult(.97f,.10f,0,8,true,30,"fresh",.80f,.97f,observation)
          .withBinding(2,now,i,true,0,false);
      permit=guard.update(7,i,now,now,2,1,true,false,score,1,false,true,score,null,true);
      assertEquals(i==5,permit.authorized);
      FollowStateMachine.FrameResult candidate=frame(now,.35f,0,0,true,2);
      candidate.simulatorIdentity=permit;
      safety.setShoppingEnvironment(ranges(now,900,900,900),0,true,false);
      assertTrue(safety.auto(candidate,now).isStop());
    }
    for(int i=0;i<2;i++) {
      long now=2100+i*100; FollowStateMachine.FrameResult authorized=frame(now,.35f,0,0,true,2);
      authorized.simulatorIdentity=permit;safety.setShoppingEnvironment(ranges(now,900,900,900),0,true,false);
      RealCartSafetyController.Output out=safety.auto(authorized,now);
      if(i==0) assertTrue(out.isStop()); else assertTrue(out.left>0&&out.right>0);
    }
  }
  @Test public void schedulerCameraWatchdogStopsCornerException() {
    RealCartSafetyController safety=readySafety();
    for(int i=0;i<6;i++) {
      long now=i*100;safety.setShoppingEnvironment(ranges(now,900,900,900),0,true,true);
      safety.auto(frame(now,.4f,0,0,true),now);
    }
    safety.setShoppingEnvironment(ranges(1000,900,900,900),0,true,true);
    assertTrue(safety.refresh(1000,null).isStop());assertFalse(safety.isAutoUnlocked());
  }
  @Test public void originalTargetNeedsThreeFreshObservationsToEndCorner() {
    corner(1,true); step(1400,0,0,0,false,900,900,900,0,true);
    assertEquals(0,step(1500,.4f,0,0,true,900,900,900,-10,true).left);
    assertEquals(0,step(1600,.4f,0,0,true,900,900,900,-10,true).left);
    assertTrue(step(1700,.4f,0,0,true,900,900,900,-10,true).left>0);
    assertFalse(controller.exploring());
  }
  @Test public void newIdentityCannotUseOriginalCornerPermission() {
    corner(1,true); step(1400,0,0,0,false,900,900,900,0,true);
    FollowStateMachine.FrameResult f=frame(1500,.4f,0,0,true,2);
    controller.environment(ranges(1500,900,900,900),-10,true,true);
    assertEquals(0,controller.update(f,1500).left);
    assertFalse(controller.exploring());
    f=frame(1600,.4f,0,0,true,2); controller.environment(ranges(1600,900,900,900),-10,true,true);
    assertEquals(0,controller.update(f,1600).left);
    f=frame(1700,.4f,0,0,true,2); controller.environment(ranges(1700,900,900,900),-10,true,true);
    assertTrue(controller.update(f,1700).left>0);
    assertFalse(controller.exploring());
    assertTrue(controller.diagnostic().contains("gear_reset_reason=authorized_track_handoff"));
  }
  @Test public void shelfEvidenceExpiresBeforeAnUnrelatedLoss() {
    corner(1,true);
    // Recover original target, then wait with no fresh corner evidence.
    for(int i=15;i<30;i++) step(i*100,.2f,.5f,.2f,true,900,900,900,0,true);
    ShoppingFollowController.Output out=step(3000,0,0,0,false,900,900,900,0,true);
    for(int i=37;i<45;i++) {
      out=step(i*100,0,0,0,false,900,900,900,0,true);
      assertTrue(out.left+out.right<=0);
    }
  }
  @Test public void safetySchedulerAllowsOnlyBoundedCornerException() {
    RealCartSafetyController safety=readySafety();
    for(int i=0;i<7;i++) {
      long now=i*100;
      safety.setShoppingEnvironment(ranges(now,900,900,i==6?900:500),0,true,true);
      safety.auto(frame(now,.2f,i==6?.55f:.5f,.2f,true),now);
    }
    safety.setShoppingEnvironment(ranges(700,900,900,900),0,true,true);
    safety.auto(frame(700,0,0,0,false),700);
    safety.setShoppingEnvironment(ranges(1400,900,900,900),0,true,true);
    assertTrue(safety.auto(frame(1400,0,0,0,false),1400).left>0);
    assertTrue(safety.refresh(1450,null).left>0);
    safety.setShoppingEnvironment(ranges(1650,900,900,900),-5,true,true);
    assertTrue(safety.refresh(1650,null).isStop());
    safety.setConnection(false,false);
    assertTrue(safety.refresh(1700,null).isStop());
  }
}
