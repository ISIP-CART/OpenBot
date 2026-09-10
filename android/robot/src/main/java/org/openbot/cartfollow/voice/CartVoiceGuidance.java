package org.openbot.cartfollow.voice;

import android.content.Context;
import android.os.SystemClock;
import org.openbot.cartfollow.FollowStateMachine;

/** Shared non-blocking guidance. Speech never gates perception or motion. */
public final class CartVoiceGuidance {
  public enum Event { EMERGENCY, DISCONNECTED, USER_STOP }
  private final VoiceGuidancePlanner planner=new VoiceGuidancePlanner();
  private final SystemChineseSpeech speech=new SystemChineseSpeech();
  private boolean systemLatched;
  public void start(Context context){speech.start(context);}
  public void speakWelcome(int textRes){if(!systemLatched)speech.speak(new VoiceGuidancePlanner.Prompt(textRes,false));}
  public void newSession(){systemLatched=false;planner.reset();}
  public void onFrame(FollowStateMachine.FrameResult frame){if(!systemLatched)speech.speak(planner.onFrame(frame,SystemClock.elapsedRealtime()));}
  public void event(Event event){
    int text=event==Event.EMERGENCY?VoicePrompts.EMERGENCY:event==Event.DISCONNECTED?VoicePrompts.DISCONNECTED:VoicePrompts.USER_STOP;
    systemLatched=true; speech.speak(planner.system(text,"system_"+event.name()));
  }
  public void reset(){planner.reset();}
  public void stop(){speech.stop();planner.reset();}
  boolean systemLatchedForTest(){return systemLatched;}
}
