package org.openbot.vehicle;

import static org.junit.Assert.*;
import android.content.*;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.felhr.usbserial.UsbSerialInterface;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openbot.utils.Constants;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class R3VehicleIntegrationTest {
  @Test public void vehicleRoutesR3WithoutReplacingLegacyReadingOrRecordingAckAsError() {
    Vehicle v = new Vehicle(RuntimeEnvironment.getApplication(), 115200);
    v.processVehicleConfig("CART_AT8236:s:r3v1:");
    assertEquals(R3TelemetrySession.State.CONFIGURING, v.getR3Telemetry().state);
    assertTrue(v.processRangeExtension("!R3,OK,100"));
    assertTrue(v.processRangeExtension("!R3D,0,100,0,0,0,1350,0,18,-1,5,65535"));
    assertNotNull(v.getR3Telemetry().snapshot);
    assertFalse(v.getRangeTelemetry().hasReading);
    assertEquals("", v.getRangeTelemetry().lastFirmwareError);
    assertFalse(v.processRangeExtension("!ERR,settling"));
    v.recordFirmwareError("!ERR,settling");
    assertEquals("!ERR,settling",v.getRangeTelemetry().lastFirmwareError);
    assertFalse(v.isReady()); // A telemetry ACK never unlocks motion readiness.
  }
  @Test public void legacyCapabilityDoesNotActivateR3() {
    Vehicle v = new Vehicle(RuntimeEnvironment.getApplication(),115200);
    v.processVehicleConfig("CART_AT8236:s:");
    assertEquals(R3TelemetrySession.State.LEGACY,v.getR3Telemetry().state);
    assertTrue(v.processSonarMessage("100"));
    assertEquals(1000,v.getRangeTelemetry().minimumDistanceMm);
    v.processRangeExtension("!R3,OK,100");
    assertEquals(R3TelemetrySession.State.LEGACY,v.getR3Telemetry().state);
  }
  @Test public void usbCallbackPreservesNewlinesAndOrderAcrossChunks() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    LocalBroadcastManager manager = LocalBroadcastManager.getInstance(context);
    List<String> received = new ArrayList<>();
    BroadcastReceiver receiver = new BroadcastReceiver() {
      @Override public void onReceive(Context c, Intent i) { received.add(i.getStringExtra("data")); }
    };
    manager.registerReceiver(receiver,new IntentFilter(Constants.DEVICE_ACTION_DATA_RECEIVED));
    try {
      UsbConnection usb = new UsbConnection(context,115200);
      Field field = UsbConnection.class.getDeclaredField("callback"); field.setAccessible(true);
      UsbSerialInterface.UsbReadCallback callback = (UsbSerialInterface.UsbReadCallback)field.get(usb);
      callback.onReceivedData("!R3,OK,".getBytes(StandardCharsets.US_ASCII));
      callback.onReceivedData("100\ns100\n!ERR,bad_r3_config\n".getBytes(StandardCharsets.US_ASCII));
      ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
      assertEquals(Arrays.asList("!R3,OK,100","s100","!ERR,bad_r3_config"),received);
    } finally { manager.unregisterReceiver(receiver); }
  }
}
