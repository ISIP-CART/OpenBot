package org.openbot.cartfollow;

import org.openbot.vehicle.R3Snapshot;
import org.openbot.vehicle.R3TelemetrySession;

/** Shared close-follow and finite shelf exploration policy; all distances are sensor-origin mm. */
public final class ShoppingFollowController {
  public static final String VERSION = "shopping-close-return-v4";
  public static final float WIDTH_START = .55f, WIDTH_STOP = .65f;
  public static final int FRONT_STOP = 300, FRONT_CLEAR = 400, SIDE_STOP = 250;
  public static final int SIDE_CLEAR = 350;
  public static final long EVIDENCE_TIMEOUT_MS = 400;
  public static final long SEGMENT_MS = 250, OBSERVE_MS = 150, CORNER_LIMIT_MS = 6000;
  public enum Phase { FOLLOW, BRAKE, VERIFY, ARC, WALL, SEARCH, PARKED }
  public static final class Output {
    public final int left, right;
    public final String reason;
    Output(int l, int r, String why) { left=l; right=r; reason=why; }
    boolean forward() { return left + right > 0; }
  }
  private R3TelemetrySession.Status range;
  private boolean enabled, angled, yawFresh, frontLatched, widthStopped, forward;
  private float heading, width, previousWidth, rawWidth, wallBaseline, wallHeading, startHeading;
  private long lastFrame=-1, lastSeen=-1, started=-1, deadline, nextAfter, lastRange=-1;
  private long wallAt=-1, widthHoldUntil, decisionRange=-1, candidateAt=-1;
  private int frontClearCount, wallSamples, outwardCount, turn, targetTrack=-1, seenTrack=-1, stableFrames;
  private int handoffTrack=-1, handoffFrames;
  private int strength=100, maximumGear=21, gear=0, upFrames, pendingGear;
  private boolean opening, inside, unknownEdge, hasAdvanced;
  private long visibleForwardAt=-1, lastCameraAt=-1, lastDecisionAt=-1;
  private int lastFrontCap=21;
  private int lastDesiredGear, lastIdentityCap=21, lastEdgeCap=21, lastCautionCap=21, lastFinalGear;
  private final boolean[] sideLatched = new boolean[2];
  private final int[] sideClearCount = new int[2];
  private String rangeMode="idle";
  private String lastGearResetReason="none";
  private Phase phase=Phase.FOLLOW;
  private Output output=new Output(0,0,"shopping_idle");
  private final TargetAimController aim = new TargetAimController();

  public synchronized void environment(R3TelemetrySession.Status r, float yaw, boolean healthy, boolean side45) {
    range=r; heading=yaw; yawFresh=healthy; angled=side45;
    if (r != null && r.advertised) enabled=true;
  }
  public synchronized boolean enabled() { return enabled; }
  public synchronized boolean learningRisk() { return exploring() || width>=WIDTH_STOP; }
  public synchronized boolean exploring() { return phase != Phase.FOLLOW && phase != Phase.PARKED; }
  public synchronized String diagnostic() {
    R3Snapshot s=snapshot();
    String sensors=s==null ? ";r3_snapshot=none" : ";r3_seq="+s.sequence+";r3_received_ms="+s.receivedAtMs
        +readingDiagnostic("L",s.left,s,lastDecisionAt)+readingDiagnostic("C",s.center,s,lastDecisionAt)
        +readingDiagnostic("R",s.right,s,lastDecisionAt);
    return "strategy="+VERSION+";phase="+phase+";raw_width="+rawWidth+";smooth_width="+width
        +";width_start="+WIDTH_START+";width_stop="+WIDTH_STOP+";width_stopped="+widthStopped
        +";desired_gear="+lastDesiredGear+";edge_cap="+lastEdgeCap+";identity_cap="+lastIdentityCap
        +";caution_cap="+lastCautionCap+";front_cap="+lastFrontCap+";configured_cap="+maximumGear+";final_gear="+lastFinalGear
        +";gear_reset_reason="+lastGearResetReason+";handoff_track="+handoffTrack
        +";handoff_frames="+handoffFrames+";side45="+angled
        +";wall="+wallBaseline+";opening="+opening+";inside="+inside+";direction="+turn
        +";heading="+heading+";range_mode="+rangeMode+";front_latched="+frontLatched
        +";front_clear_samples="+frontClearCount+";last_front_cap="+lastFrontCap
        +";left_latched="+sideLatched[0]+";right_latched="+sideLatched[1]
        +";visible_forward_received_ms="+visibleForwardAt+";decision_ms="+lastDecisionAt+sensors
        +";reason="+output.reason+";output="+output.left+","+output.right;
  }
  private String readingDiagnostic(String name, R3Snapshot.Reading r, R3Snapshot s, long at) {
    boolean available=at>=0 && r.usable(s.receivedAtMs,at);
    return ";"+name+"_mm="+r.mm+";"+name+"_status="+r.status+";"+name+"_age_ms="+r.ageMs
        +";"+name+"_usable_at_decision="+available;
  }
  public synchronized void tuning(int savedStrength, int maxGear) { strength=savedStrength; maximumGear=maxGear; }
  public synchronized void reset() {
    enabled=false; hasAdvanced=false; range=null; phase=Phase.FOLLOW; frontLatched=widthStopped=forward=false;
    width=previousWidth=rawWidth=wallBaseline=0; lastFrame=lastSeen=lastRange=wallAt=started=-1;
    frontClearCount=wallSamples=outwardCount=turn=stableFrames=gear=upFrames=pendingGear=0;
    targetTrack=seenTrack=handoffTrack=-1; handoffFrames=0; decisionRange=candidateAt=-1; opening=inside=unknownEdge=false; widthHoldUntil=0; aim.reset();
    visibleForwardAt=lastCameraAt=lastDecisionAt=-1; lastFrontCap=lastIdentityCap=lastEdgeCap=lastCautionCap=21;
    lastDesiredGear=lastFinalGear=0; lastGearResetReason="reset"; rangeMode="idle";
    sideLatched[0]=sideLatched[1]=false; sideClearCount[0]=sideClearCount[1]=0;
    output=new Output(0,0,"shopping_reset");
  }
  private Output stop(String why) {
    if (gear != 0 || upFrames != 0 || pendingGear != 0) lastGearResetReason=why;
    forward=false; gear=upFrames=pendingGear=lastFinalGear=0; visibleForwardAt=-1; rangeMode="stopped";
    return output=new Output(0,0,why);
  }
  private Output park(String why) { phase=Phase.PARKED; return stop(why); }
  private R3Snapshot snapshot() { return range == null ? null : range.snapshot; }
  private boolean usable(R3Snapshot.Reading r, long now) {
    R3Snapshot s=snapshot(); return s!=null && r!=null && r.usable(s.receivedAtMs,now);
  }
  private R3Snapshot.Reading side(int direction) {
    R3Snapshot s=snapshot(); return s==null ? null : direction>0 ? s.right : s.left;
  }
  private boolean newRange(long now) {
    R3Snapshot s=snapshot();
    if (s==null || s.receivedAtMs==lastRange) return false;
    lastRange=s.receivedAtMs;
    if (usable(s.center,now)) {
      lastFrontCap=s.center.mm<400 ? 6 : s.center.mm<500 ? 10 : maximumGear;
      if (s.center.mm<=FRONT_STOP) { frontLatched=true; frontClearCount=0; }
      else if (s.center.mm>FRONT_CLEAR) { if (++frontClearCount>=3) frontLatched=false; }
      else frontClearCount=0;
    } else frontClearCount=0;
    updateSideLatch(s.left,0,now);
    updateSideLatch(s.right,1,now);
    return true;
  }
  private void updateSideLatch(R3Snapshot.Reading reading, int index, long now) {
    if (!usable(reading,now)) { sideClearCount[index]=0; return; }
    if (reading.mm<SIDE_STOP) { sideLatched[index]=true; sideClearCount[index]=0; }
    else if (reading.mm>=SIDE_CLEAR) {
      if (++sideClearCount[index]>=3) sideLatched[index]=false;
    } else sideClearCount[index]=0;
  }
  private boolean recentTelemetry(long now) {
    R3Snapshot s=snapshot();
    return range!=null && range.state==R3TelemetrySession.State.ACTIVE && s!=null
        && now>=s.receivedAtMs && now-s.receivedAtMs<=EVIDENCE_TIMEOUT_MS;
  }
  private boolean sensorFault() {
    R3Snapshot s=snapshot();
    // Only an explicit bus/acquisition failure is a hard sensor fault. Signal/range invalid,
    // stale-with-a-fresh-snapshot, and not-present provide no obstacle evidence. Visible target
    // following may continue under the existing near-obstacle latch; blind corner exploration
    // still requires usable readings below.
    return s!=null && (s.left.status==4 || s.center.status==4 || s.right.status==4);
  }
  private boolean visibleForward(long now) {
    return phase==Phase.FOLLOW && visibleForwardAt>=0 && now>=visibleForwardAt
        && now-visibleForwardAt<=EVIDENCE_TIMEOUT_MS;
  }
  private boolean incompleteRange(long now) {
    R3Snapshot s=snapshot();
    return s!=null && (!usable(s.left,now) || !usable(s.center,now) || !usable(s.right,now));
  }
  private String frontBlockReason(long now) {
    if (!recentTelemetry(now)) return "range_telemetry_unavailable";
    if (sensorFault()) return "range_sensor_fault";
    if (frontLatched) return "range_front_near_latched";
    return "range_center_unavailable";
  }
  private int frontCap(long now) {
    R3Snapshot s=snapshot();
    if (!recentTelemetry(now) || sensorFault()) { lastFrontCap=0; return 0; }
    if (usable(s.center,now) && s.center.mm<=FRONT_STOP) { frontLatched=true; frontClearCount=0; }
    if (frontLatched) { lastFrontCap=0; return 0; }
    // In visible follow, range/signal-invalid is treated as no obvious obstacle. A
    // previously observed near obstacle remains latched; blind exploration cannot use this assumption.
    if (!usable(s.center,now)) {
      lastFrontCap=visibleForward(now)?maximumGear:0;
      return lastFrontCap;
    }
    int cap=s.center.mm<400 ? 6 : s.center.mm<500 ? 10 : maximumGear;
    lastFrontCap=cap; return cap;
  }
  private Output gated(int l, int r, String why, long now) {
    if (l==0 && r==0) return stop(why);
    R3Snapshot s=snapshot();
    boolean visualGuided=visibleForward(now);
    boolean visualArc=l>0 && r>0 && visualGuided;
    if (!recentTelemetry(now)) return stop("range_telemetry_unavailable");
    if (sensorFault()) return stop("range_sensor_fault");
    if (!usable(s.center,now) && !visualGuided) return stop("range_center_unavailable");
    rangeMode=visualGuided && incompleteRange(now) ? "visible_invalid_assumed_clear" : "range_required";
    if (l+r>0) {
      int cap=frontCap(now); if(cap==0) return stop(frontBlockReason(now));
      int peak=Math.max(l,r); if(peak>cap) { l=Math.round(l*(float)cap/peak); r=Math.round(r*(float)cap/peak); }
    }
    if(l!=r) {
      int dir=l>r?1:-1; R3Snapshot.Reading inner=side(dir);
      boolean boundedUnknownScan=phase==Phase.VERIFY && unknownEdge && now-wallAt<=1000
          && wallBaseline>=450 && s.center.mm>=500 && usable(side(-dir),now) && side(-dir).mm>=350;
      if (sideLatched[dir>0?1:0] || usable(inner,now) && inner.mm<SIDE_STOP)
        return stop("range_side_near_latched");
      if (!usable(inner,now) && !boundedUnknownScan && !visualGuided) return stop("range_turn_unavailable");
      if (usable(s.center,now) && s.center.mm<=200) return stop("range_turn_front_near");
    }
    String suffix="_range_assumed_clear";
    if(why.endsWith(suffix)) why=why.substring(0,why.length()-suffix.length());
    if(visualGuided && incompleteRange(now)) why+=suffix;
    forward=l+r>0; if(forward) hasAdvanced=true; return output=new Output(l,r,why);
  }
  public synchronized Output update(FollowStateMachine.FrameResult f, long now) {
    lastDecisionAt=now;
    if (!enabled) return null;
    if (f==null || f.frameTiming==null || now<f.frameTiming.receivedAtMs || now-f.frameTiming.receivedAtMs>400)
      return park("shopping_camera_stale");
    if(f.frameSequence<=lastFrame) return poll(now);
    lastFrame=f.frameSequence;
    lastCameraAt=f.frameTiming.receivedAtMs; visibleForwardAt=-1;
    newRange(now);
    boolean freshRange=snapshot()!=null && snapshot().receivedAtMs!=decisionRange;
    if(freshRange) decisionRange=snapshot().receivedAtMs;
    if(f.state!=FollowState.FOLLOW && f.state!=FollowState.FOLLOW_CAUTION
        && f.state!=FollowState.IDENTITY_UNCERTAIN && f.state!=FollowState.LOST
        && f.state!=FollowState.SEARCH && f.state!=FollowState.DIRECTED_REACQUIRE)
      return stop("shopping_initialization");
    TargetObservationEvidence o=f.targetObservation;
    boolean present=o!=null && o.current && now>=o.observedAtMs && now-o.observedAtMs<=400;
    boolean trusted=present && !o.lowConfidence && f.simulatorIdentity!=null && o.trackId==f.simulatorIdentity.trackId && f.simulatorIdentity.allowsForward(now)
        && (f.simulatorIdentity.tracking==null || f.simulatorIdentity.tracking.matchesFrame(f));
    int persons=Math.max(f.persons==null?0:f.persons.size(),o==null?0:o.personCount);
    if(f.detectionTierEvidence!=null) persons=Math.max(persons,f.detectionTierEvidence.lowConfidencePersons.size());
    if (trusted) {
      if (seenTrack>=0 && o.trackId!=seenTrack) {
        if (handoffTrack!=o.trackId) {
          resetFollowContextForHandoff();
          handoffTrack=o.trackId;
          handoffFrames=0;
        }
        if (++handoffFrames<3) return stop("shopping_track_handoff_"+handoffFrames+"_of_3");
        seenTrack=targetTrack=o.trackId;
        handoffTrack=-1; handoffFrames=0; stableFrames=2;
      } else {
        handoffTrack=-1; handoffFrames=0;
      }
      if(phase!=Phase.FOLLOW) {
        if(++stableFrames<3) return stop("corner_target_verifying");
        phase=Phase.FOLLOW; started=-1; opening=inside=unknownEdge=false; outwardCount=0; aim.reset();
      }
      seenTrack=o.trackId; lastSeen=now;
      if(targetTrack!=o.trackId) { targetTrack=o.trackId; width=0; stableFrames=0; }
      return visible(f,o,now,freshRange,persons);
    }
    handoffTrack=-1; handoffFrames=0;
    stableFrames=0;
    if(present || persons>0) {
      if(phase!=Phase.FOLLOW) return park("corner_person_ambiguity");
      return stop("shopping_identity_wait");
    }
    if(phase==Phase.PARKED) return stop("corner_finished_wait_target");
    if(phase==Phase.FOLLOW) {
      if(!hasAdvanced || lastSeen<0 || now-lastSeen>500 || outwardCount<3 || turn==0) return stop("loss_without_exit_context");
      if(!yawFresh) return park("corner_gyro_unavailable");
      started=now; startHeading=heading; phase=Phase.BRAKE; deadline=now+650;
      // A search without a shelf hypothesis may rotate, but never translate.
      if(!angled || candidateAt<0 || now-candidateAt>500 || !(opening||inside||unknownEdge)) { phase=Phase.SEARCH; deadline=now+650; }
      aim.reset(); return stop("corner_entry_brake");
    }
    if(!yawFresh || now-started>=CORNER_LIMIT_MS || Math.abs(heading-startHeading)>=100)
      return park("corner_limit_or_gyro");
    if(phase==Phase.BRAKE) {
      if(now<deadline || f.frameTiming.receivedAtMs<deadline) return stop("corner_settling");
      phase=unknownEdge?Phase.VERIFY:Phase.ARC; deadline=0; nextAfter=now;
    }
    if(phase==Phase.VERIFY) {
      R3Snapshot.Reading inner=side(turn);
      if(freshRange && usable(inner,now) && inner.mm>wallBaseline+250) {
        unknownEdge=false; opening=true; phase=Phase.ARC; deadline=0; nextAfter=now;
      } else if(now-started>1400 || Math.abs(heading-startHeading)>=10) return park("corner_opening_unconfirmed");
    }
    if(phase==Phase.SEARCH && (now-started>2500 || Math.abs(heading-startHeading)>=35)) return park("search_limit");
    if(deadline>0 && now<deadline) return gated(output.left,output.right,output.reason,now);
    if(deadline>0) { nextAfter=deadline+OBSERVE_MS; deadline=0; return stop("corner_observe"); }
    if(now<nextAfter || f.frameTiming.receivedAtMs<nextAfter || !freshRange || snapshot().receivedAtMs<nextAfter)
      return stop("corner_wait_fresh_observation");
    if(phase==Phase.VERIFY || phase==Phase.SEARCH) {
      deadline=now+SEGMENT_MS; return gated(turn*5,-turn*5,"corner_scan",now);
    }
    R3Snapshot s=snapshot();
    if(s==null || !usable(side(turn),now) || !usable(side(-turn),now)) return park("corner_range_unavailable");
    if(frontCap(now)==0) return park("corner_front_blocked");
    if(-turn*(heading-startHeading)<-8) return park("corner_wrong_direction");
    float progress=-turn*(heading-startHeading);
    int delta=4;
    if(side(turn).mm<350) delta=-1;
    else if(side(turn).mm>650) delta=6;
    if(s.center.mm<500) delta=Math.max(delta,6);
    if(progress>60 && side(turn).mm>=350 && side(turn).mm<=650) { phase=Phase.WALL; delta=0; }
    deadline=now+SEGMENT_MS;
    int left=8,right=8;
    if(delta>=0) { if(turn>0) right-=delta; else left-=delta; }
    else { if(turn>0) left--; else right--; }
    return gated(left,right,phase==Phase.WALL?"corner_wall_follow":"corner_forward_arc",now);
  }
  private void resetFollowContextForHandoff() {
    phase=Phase.FOLLOW; started=deadline=nextAfter=wallAt=candidateAt=-1;
    opening=inside=unknownEdge=hasAdvanced=forward=false;
    outwardCount=turn=wallSamples=stableFrames=0;
    wallBaseline=width=previousWidth=rawWidth=0;
    widthStopped=false; widthHoldUntil=0; visibleForwardAt=-1;
    lastGearResetReason="authorized_track_handoff";
    gear=upFrames=pendingGear=lastFinalGear=0;
    aim.reset();
  }
  private Output visible(FollowStateMachine.FrameResult f, TargetObservationEvidence o, long now, boolean freshRange, int persons) {
    SteeringEvidence e=f.steeringEvidence;
    if(e==null || !e.valid) return stop("shopping_steering_missing");
    float w=o.screenBox.width(); rawWidth=w;
    boolean clipped=o.screenBox.left<=.01f || o.screenBox.right>=.99f;
    if(!Float.isFinite(w)||w<=0||w>1) return stop("shopping_box_invalid");
    if(previousWidth>0 && w<previousWidth*.75f) widthHoldUntil=now+500;
    previousWidth=w;
    if(width==0) width=w;
    if(!clipped && !f.simulatorIdentity.isAppearanceTransition() && now>=widthHoldUntil) width+=.35f*(w-width);
    float margin=Math.min(o.screenBox.left,1-o.screenBox.right);
    int dir=e.rawError>0?1:e.rawError<0?-1:0;
    if(persons==1 && Math.abs(e.rawError)>.15f && dir*e.lateralRatePerSec>.03f && margin<.20f) {
      if(turn!=dir) { outwardCount=0; wallSamples=0; wallBaseline=0; opening=inside=unknownEdge=false; }
      turn=dir; outwardCount++;
    }
    if(persons>1 || Math.abs(e.rawError)<.06f) { outwardCount=0; opening=inside=unknownEdge=false; }
    if(angled && freshRange && dir!=0) {
      R3Snapshot.Reading side=side(dir); R3Snapshot s=snapshot();
      if(usable(side,now)) {
        if(wallSamples>=3 && now-wallAt<1000 && Math.abs(heading-wallHeading)<15 && side.mm>wallBaseline+250) { opening=true; candidateAt=now; }
        if(side.mm>=350 && side.mm<=900 && (!opening)) {
          if(wallSamples==0 || Math.abs(side.mm-wallBaseline)>100) { wallSamples=1; wallBaseline=side.mm; }
          else { wallSamples++; wallBaseline=.8f*wallBaseline+.2f*side.mm; }
          wallAt=now; wallHeading=heading;
        }
        inside=usable(s.center,now)&&s.center.mm<650&&side.mm>s.center.mm+150&&side.mm>450;
        if(inside) candidateAt=now;
      } else if(wallSamples>=3 && now-wallAt<500 && wallBaseline>=450) { unknownEdge=true; candidateAt=now; }
    }
    if(++stableFrames<3) return stop("shopping_target_stabilizing");
    if(width>=WIDTH_STOP) widthStopped=true;
    else if(width<WIDTH_START) widthStopped=false;
    boolean widthUnknown=clipped || now<widthHoldUntil || f.simulatorIdentity.isAppearanceTransition();
    boolean pivot=widthStopped || widthUnknown || margin<.05f;
    visibleForwardAt=f.frameTiming.receivedAtMs;
    if(pivot) {
      AimDecision a=aim.update(e,false,forward,f.frameTiming.receivedAtMs,now);
      if(a.pivots()) return gated(a.mode==AimDecision.Mode.PIVOT_LEFT?-a.speed:a.speed,
          a.mode==AimDecision.Mode.PIVOT_LEFT?a.speed:-a.speed,"shopping_visible_aim",now);
      return stop(widthStopped?"width_hold":widthUnknown?"width_unreliable":a.reason);
    }
    aim.reset();
    int desired=width<.40f?21:width<.50f?18:width<.55f?14:width<.60f?10:8;
    lastDesiredGear=desired;
    lastEdgeCap=margin<.12f?10:21;
    if(margin<.12f) desired=Math.min(desired,lastEdgeCap);
    lastCautionCap=f.state==FollowState.FOLLOW_CAUTION || f.behaviorDecision.selectedAction==BehaviorAction.FOLLOW_CAUTION?14:21;
    desired=Math.min(desired,lastCautionCap);
    lastIdentityCap=f.simulatorIdentity.tracking==null?21:f.simulatorIdentity.tracking.maximumGear;
    if(f.simulatorIdentity.tracking!=null) desired=Math.min(desired,lastIdentityCap);
    desired=Math.min(desired,Math.min(maximumGear,frontCap(now)));
    if(desired==0) return stop(frontBlockReason(now));
    if(gear==0) gear=Math.min(desired,8);
    else if(desired<=gear) { gear=desired; upFrames=0; }
    else if(pendingGear!=desired) { pendingGear=desired; upFrames=1; }
    else if(++upFrames>=3) { gear=Math.min(desired,gear+2); upFrames=0; }
    int inner=Math.min(gear,RealCartAutoDriveController.innerSpeedForError(gear,e.rawError,e.lateralRatePerSec,strength));
    int l=gear,r=gear;
    if(e.direction==SteeringEvidence.Direction.LEFT) l=inner;
    else if(e.direction==SteeringEvidence.Direction.RIGHT) r=inner;
    // Small wall correction only while already tracking a chosen side; never alternate walls.
    if(angled && turn!=0 && outwardCount>=3 && usable(side(turn),now) && side(turn).mm<350) {
      if(turn>0) { r=gear; l=Math.max(6,gear-1); } else { l=gear; r=Math.max(6,gear-1); }
    }
    Output result=gated(l,r,margin<.12f?"visible_edge_arc":"width_follow",now);
    lastFinalGear=Math.max(Math.abs(result.left),Math.abs(result.right));
    return result;
  }
  public synchronized Output poll(long now) {
    lastDecisionAt=now;
    if(!enabled) return null;
    if(lastCameraAt>=0 && (now<lastCameraAt || now-lastCameraAt>EVIDENCE_TIMEOUT_MS))
      return park("shopping_camera_stale");
    newRange(now);
    if(exploring()) {
      if(!yawFresh || now-started>=CORNER_LIMIT_MS || Math.abs(heading-startHeading)>=100) return park("corner_limit_or_gyro");
      if(deadline>0 && now>=deadline) return stop("corner_segment_timeout");
    } else if(aim.expire(now)) return stop("shopping_aim_timeout");
    return gated(output.left,output.right,output.reason,now);
  }
}
