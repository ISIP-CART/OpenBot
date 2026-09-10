package org.openbot.cartfollow.voice;
import static org.junit.Assert.*;
import java.util.ArrayList;
import org.junit.Test;
import org.openbot.cartfollow.*;
import org.openbot.vehicle.Control;
import android.speech.tts.TextToSpeech;
public class VoiceGuidancePlannerTest {
  private FollowStateMachine.FrameResult frame(FollowState state) {
    return new FollowStateMachine.FrameResult(state,new Control(0,0),null,null,new ArrayList<>(),false,false,null,0);
  }
  @Test public void speaksOnlyOnceWhenStateIsStable() {
    VoiceGuidancePlanner p=new VoiceGuidancePlanner();
    assertEquals(VoicePrompts.CAPTURE,p.onFrame(frame(FollowState.CAPTURE_TARGET),0).textRes);
    assertNull(p.onFrame(frame(FollowState.CAPTURE_TARGET),100));
  }
  @Test public void structuredReverseAndInterruptionReplaceEachOther() {
    VoiceGuidancePlanner p=new VoiceGuidancePlanner(); FollowStateMachine.FrameResult f=frame(FollowState.AUTO_POSITIONING);
    f.initializationPositioningEvidence=InitializationPositioningEvidence.stage(InitializationPositioningEvidence.Phase.WAIT_STABLE,"waiting");
    assertEquals(VoicePrompts.POSITIONING,p.onFrame(f,0).textRes);
    f.initializationPositioningEvidence=InitializationPositioningEvidence.stage(InitializationPositioningEvidence.Phase.REVERSING,"reverse");
    assertEquals(VoicePrompts.POSITIONING_REVERSE,p.onFrame(f,100).textRes);
    f.initializationPositioningEvidence=InitializationPositioningEvidence.stage(InitializationPositioningEvidence.Phase.WAIT_STABLE,"target_missing");
    assertEquals(VoicePrompts.POSITIONING_INTERRUPTED,p.onFrame(f,200).textRes);
  }
  @Test public void fullBodyAndCalibrationShareOnePrompt() {
    VoiceGuidancePlanner p=new VoiceGuidancePlanner(); FollowStateMachine.FrameResult f=frame(FollowState.AUTO_POSITIONING);
    f.initializationPositioningEvidence=InitializationPositioningEvidence.stage(InitializationPositioningEvidence.Phase.SETTLING,"full_body");
    assertEquals(VoicePrompts.CALIBRATION,p.onFrame(f,0).textRes);
    assertNull(p.onFrame(frame(FollowState.DISTANCE_CALIBRATION),100));
  }
  @Test public void systemReasonsAreExplicitAndUrgent() {
    VoiceGuidancePlanner.Prompt e=new VoiceGuidancePlanner().system(VoicePrompts.EMERGENCY,"emergency");
    assertTrue(e.urgent); assertEquals(VoicePrompts.EMERGENCY,e.textRes);
  }
  @Test public void everyPromptUsesImmediateReplacementQueue() {
    assertEquals(TextToSpeech.QUEUE_FLUSH,SystemChineseSpeech.queueMode());
  }
}
