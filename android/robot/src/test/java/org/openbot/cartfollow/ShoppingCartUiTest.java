package org.openbot.cartfollow;

import static org.junit.Assert.*;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.SavedStateHandle;
import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.R;
import org.openbot.databinding.FragmentHumanCartSimulatorBinding;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

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
    assertTrue(f.diagnosticsEnabledByDefault());
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
}
