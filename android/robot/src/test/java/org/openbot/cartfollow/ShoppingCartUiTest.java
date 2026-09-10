package org.openbot.cartfollow;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.SavedStateHandle;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.R;
import org.openbot.databinding.FragmentHumanCartSimulatorBinding;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.openbot.tflite.Detector.Recognition;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ShoppingCartUiTest {
  public static class Screen extends ShoppingCartFragment {
    @Override public View onCreateView(LayoutInflater i, ViewGroup p, Bundle s) {
      binding=FragmentHumanCartSimulatorBinding.inflate(i,p,false); return binding.getRoot();
    }
    @Override public void onViewCreated(View v, Bundle s) {}
  }
  private Screen screen() {
    FragmentActivity a=Robolectric.buildActivity(FragmentActivity.class).create().start().get();
    a.setTheme(R.style.AppTheme); Screen f=new Screen();
    a.getSupportFragmentManager().beginTransaction().add(android.R.id.content,f).commitNow();
    f.applyReleasePresentation(); return f;
  }
  @Test public void releaseShowsOnlyThreePersistentOperationsAndDefaultsDiagnosticsOn() {
    Screen f=screen();
    assertEquals(View.VISIBLE,f.binding.shoppingReleasePanel.getVisibility());
    assertEquals(View.GONE,f.binding.realControlPanel.getVisibility());
    assertEquals(View.GONE,f.binding.bottomPanel.getVisibility());
    assertEquals(View.GONE,f.binding.debugPanel.getVisibility());
    assertEquals(View.GONE,f.binding.steeringPanel.getVisibility());
    assertEquals(View.GONE,f.binding.simulatorExperimentScroll.getVisibility());
    assertEquals(View.GONE,f.binding.btnCancel.getVisibility());
    assertEquals(View.GONE,f.binding.confirmPanel.getVisibility());
    assertEquals(View.GONE,f.binding.btnConfirm.getVisibility());
    assertEquals(View.GONE,f.binding.btnRetake.getVisibility());
    assertTrue(f.diagnosticsEnabledByDefault());
    assertTrue(f.autoConfirmCapturedTarget());
    assertEquals(21,f.releaseMaximumGear());
  }
  @Test public void largeStartAndEmergencyFitPortraitAndLandscape() {
    Screen f=screen();
    for(int[] size:new int[][]{{360,720},{720,360}}) {
      View root=f.binding.getRoot();
      root.measure(View.MeasureSpec.makeMeasureSpec(size[0],View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size[1],View.MeasureSpec.EXACTLY));
      root.layout(0,0,size[0],size[1]);
      assertTrue(f.binding.shoppingStartSwitch.getHeight()>=64);
      assertTrue(f.binding.shoppingEmergencyStop.getHeight()>=56);
      assertTrue(f.binding.shoppingStartCard.getRight()<=size[0]);
    }
  }

  @Test public void startEnablesOnlyWhenReleasePipelineIsReady() {
    Screen f=screen();
    ReleaseStartReadiness loading=ReleaseStartReadiness.evaluate(false,true,true,false,"",true);
    f.onRealUiRefreshed("BLE 已就绪 · CART_AT8236",false,loading);
    assertFalse(f.binding.shoppingStartSwitch.isEnabled());
    assertEquals("相机模型正在准备",f.binding.shoppingStatus.getText().toString());

    ReleaseStartReadiness ready=ReleaseStartReadiness.evaluate(false,true,true,true,"",true);
    f.onRealUiRefreshed("BLE 已就绪 · CART_AT8236",false,ready);
    assertTrue(f.binding.shoppingStartSwitch.isEnabled());
    assertEquals("已就绪，点击 Start 开始",f.binding.shoppingStatus.getText().toString());

    f.binding.shoppingStartSwitch.setChecked(true);
    f.onRealUiRefreshed(
        "BLE 未连接",false,
        ReleaseStartReadiness.evaluate(false,false,false,false,"",false));
    assertTrue(f.binding.shoppingStartSwitch.isEnabled());
  }

  @Test public void welcomeIsMarkedOnceForOneNavigationEntry() {
    SavedStateHandle state=new SavedStateHandle(new HashMap<>());
    assertTrue(ShoppingCartFragment.markWelcomeSpoken(state));
    assertFalse(ShoppingCartFragment.markWelcomeSpoken(state));
    assertTrue(ShoppingCartFragment.markWelcomeSpoken(new SavedStateHandle(new HashMap<>())));
  }

  @Test public void pendingAndTimeoutStatusNeverAskForManualConfirmation() {
    FollowStateMachine.FrameResult pending =
        new FollowStateMachine.FrameResult(
            FollowState.LOCKED_PENDING_CONFIRM,
            new org.openbot.vehicle.Control(0,0),null,null,
            java.util.Collections.emptyList(),false,false,null,-1);
    assertFalse(ShoppingCartFragment.releaseStatus(pending).contains("确认"));
    pending.initializationPositioningEvidence =
        InitializationPositioningEvidence.stage(
            InitializationPositioningEvidence.Phase.TIMEOUT,"reverse_timeout");
    String timeout = ShoppingCartFragment.releaseStatus(pending);
    assertTrue(timeout.contains("重新点击 Start"));
    assertFalse(timeout.contains("确认"));
  }

  @Test public void automaticConfirmationHasAnImmediateReleaseStatus() {
    FollowStateMachine.FrameResult confirmed =
        new FollowStateMachine.FrameResult(
            FollowState.AUTO_POSITIONING,
            new org.openbot.vehicle.Control(0,0),null,null,
            java.util.Collections.emptyList(),false,false,null,-1);
    confirmed.distanceDiagnosticText = "目标采集完成，请保持站立";
    assertEquals("目标采集完成，请保持站立",ShoppingCartFragment.releaseStatus(confirmed));
  }

  @Test public void releaseInitializationHidesLowConfidenceGrayBoxes() throws Exception {
    Screen f=screen();
    Recognition gray=new Recognition("partial","person",.30f,new RectF(120,80,230,360),0);
    FollowStateMachine.FrameResult frame=new FollowStateMachine.FrameResult(
        FollowState.AUTO_POSITIONING,new org.openbot.vehicle.Control(0,0),null,null,
        Collections.emptyList(),false,false,null,-1);
    frame.detectionTierEvidence=new DetectionTierEvidence(.50f,.25f,
        Collections.singletonList(gray),Collections.emptyList(),false);
    java.lang.reflect.Method build=BaseCartFollowFragment.class.getDeclaredMethod(
        "buildDrawBoxes",FollowStateMachine.FrameResult.class,int.class,int.class,int.class);
    build.setAccessible(true);
    assertTrue(((List<?>)build.invoke(f,frame,400,400,0)).isEmpty());
  }

  @Test public void releaseShowsUnverifiedIdentityCandidateAndUnstableDetection() throws Exception {
    Screen f=screen();
    Recognition candidate=new Recognition("return","person",.95f,new RectF(100,80,220,360),0);
    TargetTrackManager tracks=ReflectionHelpers.getField(f,"targetTrackManager");
    tracks.update(Collections.singletonList(candidate),400,400,1000);
    int trackId=tracks.getTrackForRecognition(candidate).trackId;
    FollowStateMachine.FrameResult frame=new FollowStateMachine.FrameResult(
        FollowState.IDENTITY_UNCERTAIN,new org.openbot.vehicle.Control(0,0),null,null,
        Collections.singletonList(candidate),false,false,null,-1);
    frame.simulatorIdentity=new SimulatorIdentityGuard.Decision(false,true,trackId,2,"global_fresh_reid_verification");
    assertEquals("正在确认目标",ShoppingCartFragment.releaseStatus(frame));
    java.lang.reflect.Method build=BaseCartFollowFragment.class.getDeclaredMethod(
        "buildDrawBoxes",FollowStateMachine.FrameResult.class,int.class,int.class,int.class);
    build.setAccessible(true);
    List<?> boxes=(List<?>)build.invoke(f,frame,400,400,0);
    assertEquals(1,boxes.size());
    java.lang.reflect.Field label=boxes.get(0).getClass().getDeclaredField("label");label.setAccessible(true);
    assertEquals("正在确认目标",label.get(boxes.get(0)));

    Recognition unstable=new Recognition("weak","person",.30f,new RectF(120,80,230,360),0);
    FollowStateMachine.FrameResult unstableFrame=new FollowStateMachine.FrameResult(
        FollowState.IDENTITY_UNCERTAIN,new org.openbot.vehicle.Control(0,0),null,null,
        Collections.emptyList(),false,false,null,-1);
    unstableFrame.detectionTierEvidence=new DetectionTierEvidence(.50f,.25f,
        Collections.singletonList(unstable),Collections.emptyList(),false);
    assertEquals("目标检测不稳定",ShoppingCartFragment.releaseStatus(unstableFrame));
    boxes=(List<?>)build.invoke(f,unstableFrame,400,400,0);
    assertEquals(1,boxes.size());
    assertEquals("目标检测不稳定",label.get(boxes.get(0)));

    FollowStateMachine.FrameResult initializing=new FollowStateMachine.FrameResult(
        FollowState.AUTO_POSITIONING,new org.openbot.vehicle.Control(0,0),null,null,
        Collections.emptyList(),false,false,null,-1);
    initializing.detectionTierEvidence=new DetectionTierEvidence(.50f,.25f,
        Collections.singletonList(unstable),Collections.emptyList(),false);
    boxes=(List<?>)build.invoke(f,initializing,400,400,0);
    assertTrue(boxes.isEmpty());
  }

  @Test public void followStateReportsIdentityCheckWhenMotionPermissionIsPaused() {
    FollowStateMachine.FrameResult frame=new FollowStateMachine.FrameResult(
        FollowState.FOLLOW,new org.openbot.vehicle.Control(0,0),null,null,
        Collections.emptyList(),false,false,null,-1);
    frame.simulatorIdentity=new SimulatorIdentityGuard.Decision(
        false,false,1,0,"global_reid_cached_hold");
    assertEquals("正在确认目标",ShoppingCartFragment.releaseStatus(frame));
  }
}
