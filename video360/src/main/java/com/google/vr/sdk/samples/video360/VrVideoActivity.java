/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.vr.sdk.samples.video360;

import android.Manifest;
import android.Manifest.permission;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.annotation.MainThread;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import com.google.vr.ndk.base.DaydreamApi;
import com.google.vr.sdk.base.AndroidCompat;
import com.google.vr.sdk.base.Eye;
import com.google.vr.sdk.base.GvrActivity;
import com.google.vr.sdk.base.GvrView;
import com.google.vr.sdk.base.HeadTransform;
import com.google.vr.sdk.base.Viewport;
import com.google.vr.sdk.controller.Controller;
import com.google.vr.sdk.controller.ControllerManager;
import com.google.vr.sdk.samples.video360.rendering.SceneRenderer;
import javax.microedition.khronos.egl.EGLConfig;
/**
 * GVR Activity demonstrating a 360 video player.
 *
 * The default intent for this Activity will load a 360 placeholder panorama. For more options on
 * how to load other media using a custom Intent, see {@link MediaLoader}.
 */
public class VrVideoActivity extends GvrActivity {
  private static final String TAG = "VrVideoActivity";
  private static final int READ_EXTERNAL_STORAGE_PERMISSION_ID = 1;

  private static final int EXIT_FROM_VR_REQUEST_CODE = 42;

  private GvrView gvrView;
  private Renderer renderer;

  // Displays the controls for video playback.
  private VideoUiView uiView;

  // Given an intent with a media file and format, this will load the file and generate the mesh.
  private MediaLoader mediaLoader;

  // Interfaces with the Daydream controller.
  private ControllerManager controllerManager;
  private Controller controller;

  private Uri videoUri;
  public final float[] additionalTargetOrientation = {0,0,0}, additionalCurrentOrientation = {0,0,0};
  /**
   * Configures the VR system.
   *
   * @param savedInstanceState unused in this sample but it could be used to track video position
   */
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mediaLoader = new MediaLoader(this);

    videoUri = getIntent().getData();

    startService(new Intent(VrVideoActivity.this, UDPListenerService.class));
    registerReceiver(mMessageReceiver, new IntentFilter("UDPBroadcast"));

    gvrView = new GvrView(this);
    // Since the videos have fewer pixels per degree than the phones, reducing the render target
    // scaling factor reduces the work required to render the scene. This factor can be adjusted at
    // runtime depending on the resolution of the loaded video.
    // You can use Eye.getViewport() in the overridden onDrawEye() method to determine the current
    // render target size in pixels.
    gvrView.setRenderTargetScale(.5f);

    // Standard GvrView configuration
    renderer = new Renderer(gvrView);
    gvrView.setEGLConfigChooser(
        8, 8, 8, 8,  // RGBA bits.
        16,  // Depth bits.
        0);  // Stencil bits.
    gvrView.setRenderer(renderer);
    setContentView(gvrView);

    // Most Daydream phones can render a 4k video at 60fps in sustained performance mode. These
    // options can be tweaked along with the render target scale.
    if (gvrView.setAsyncReprojectionEnabled(true)) {
      AndroidCompat.setSustainedPerformanceMode(this, true);
    }

    // Handle the user clicking on the 'X' in the top left corner. Since this is done when the user
    // has taken the headset out of VR, it should launch the app's exit flow directly rather than
    // using the transition flow.
    gvrView.setOnCloseButtonListener(new Runnable() {
      @Override
      public void run() {
      }
    });

    // Configure Controller.
    ControllerEventListener listener = new ControllerEventListener();
    controllerManager = new ControllerManager(this, listener);
    controller = controllerManager.getController();
    controller.setEventListener(listener);
    // controller.start() is called in onResume().

    checkPermissionAndInitialize();
  }

  /**
   * Normal apps don't need this. However, since we use adb to interact with this sample, we
   * want any new adb Intents to be routed to the existing Activity rather than launching a new
   * Activity.
   */
  @Override
  protected void onNewIntent(Intent intent) {
    // Save the new Intent which may contain a new Uri. Then tear down & recreate this Activity to
    // load that Uri.
    String message = intent.getStringExtra("message");

    //Message format:
    //some/path/to.file timeMs rotation

    if(message != null) { //It means udp message
      String[] data = message.split(" ");
      Uri uri = Uri.parse(data[0]);

      String s = uri.getPath();

      //---
      //set file if it new
      if(videoUri == null || !videoUri.getPath().equals(uri.getPath())) {
        intent.setData(uri); //means that one was from udp
        setIntent(intent);
        recreate();
        //for new request we don't need to set another data
        return;
      }

      //---
      //set time if it not correct
      int targetMs = Integer.parseInt(data[1]);
      int currentMs = mediaLoader.mediaPlayer.getCurrentPosition();

      //let little async
      if(Math.abs(targetMs - currentMs) > 100){
        mediaLoader.mediaPlayer.seekTo(targetMs);
      }

      //---
      //set orientation
      if(data.length > 2){
        additionalTargetOrientation[0] = Float.parseFloat(data[2]);
        additionalTargetOrientation[1] = Float.parseFloat(data[3]);
        additionalTargetOrientation[2] = Float.parseFloat(data[4]);
      }
    }
    else{ //just usual behavior
      setIntent(intent);
      recreate();
    }

  }

  // Our handler for received Intents. This will be called whenever an Intent
  // with an action named "custom-event-name" is broadcasted.
  // Simple launch:
  // echo "file:///storage/emulated/0/Movies/360-test1.mp4" | socat - UDP-DATAGRAM:192.168.0.255:11111,broadcast
  private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      // Get extra data included in the Intent
      String message = intent.getStringExtra("message");
      Log.d("receiver", "Got message: " + message);
      onNewIntent(intent);
    }
  };

  /**
   * Tries to exit gracefully from VR using a VR transition dialog.
   *
   * @return whether the exit request has started or whether the request failed due to the device
   *     not being Daydream Ready
   */
  private boolean exitFromVr() {
    // This needs to use GVR's exit transition to avoid disorienting the user.
    DaydreamApi api = DaydreamApi.create(this);
    if (api != null) {
      api.exitFromVr(this, EXIT_FROM_VR_REQUEST_CODE, null);
      // Eventually, the Activity's onActivityResult will be called.
      api.close();
      return true;
    }
    return false;
  }

  /** Initializes the Activity only if the permission has been granted. */
  private void checkPermissionAndInitialize() {

    if (ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
              VrVideoActivity.this,
              new String[] {Manifest.permission.READ_EXTERNAL_STORAGE},
              READ_EXTERNAL_STORAGE_PERMISSION_ID);
    }

    if (ContextCompat.checkSelfPermission(this, permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED) {
      mediaLoader.handleIntent(getIntent(), uiView);
    } else {
      exitFromVr();
      // This method will return false on Cardboard devices. This case isn't handled in this sample
      // but it should be handled for VR Activities that run on Cardboard devices.
    }
  }

  /**
   * Handles the result from {@link DaydreamApi#exitFromVr(Activity, int, Intent)}. This is called
   * via the uiView.setVrIconClickListener listener below.
   *
   * @param requestCode matches the parameter to exitFromVr()
   * @param resultCode whether the user accepted the exit request or canceled
   */
  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent unused) {
    if (requestCode == EXIT_FROM_VR_REQUEST_CODE && resultCode == RESULT_OK) {
    } else {
      // This should contain a VR UI to handle the user declining the exit request.
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    controllerManager.start();
    mediaLoader.resume();
  }

  @Override
  protected void onPause() {
    mediaLoader.pause();
    controllerManager.stop();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    mediaLoader.destroy();
    uiView.setMediaPlayer(null);
    super.onDestroy();
  }

  /**
   * Standard GVR renderer. Most of the real work is done by {@link SceneRenderer}.
   */
  private class Renderer implements GvrView.StereoRenderer {
    private static final float Z_NEAR = .1f;
    private static final float Z_FAR = 100;

    // Used by ControllerEventListener to manipulate the scene.
    public final SceneRenderer scene;

    private final float[] viewProjectionMatrix = new float[16];

    /**
     * Creates the Renderer and configures the VR exit button.
     *
     * @param parent Any View that is already attached to the Window. The uiView will secretly be
     *     attached to this View in order to properly handle UI events.
     */
    @MainThread
    public Renderer(ViewGroup parent) {
      Pair<SceneRenderer, VideoUiView> pair
          = SceneRenderer.createForVR(VrVideoActivity.this, parent);
      scene = pair.first;
      uiView = pair.second;
      uiView.setVrIconClickListener(
          new OnClickListener() {
            @Override
            public void onClick(View v) {
              if (!exitFromVr()) {
                // Directly exit Cardboard Activities.
                onActivityResult(EXIT_FROM_VR_REQUEST_CODE, RESULT_OK, null);
              }
            }
          });
    }

    @Override
    public void onNewFrame(HeadTransform headTransform) {}

    @Override
    public void onDrawEye(Eye eye) {

      LinearInterpolate(additionalCurrentOrientation, additionalTargetOrientation, 0.1f, additionalCurrentOrientation);

      Matrix.multiplyMM(
          viewProjectionMatrix, 0, eye.getPerspective(Z_NEAR, Z_FAR), 0, eye.getEyeView(), 0);
      Matrix.rotateM(viewProjectionMatrix, 0, additionalCurrentOrientation[1], 1, 0, 0 ); //roll
      Matrix.rotateM(viewProjectionMatrix, 0, additionalCurrentOrientation[2], 0, 1, 0 ); //pitch
      Matrix.rotateM(viewProjectionMatrix, 0, -additionalCurrentOrientation[0], 0, 0, 1 ); //yaw

      scene.glDrawFrame(viewProjectionMatrix, eye.getType());
    }

    @Override
    public void onFinishFrame(Viewport viewport) {}

    @Override
    public void onSurfaceCreated(EGLConfig config) {
      scene.glInit();
      mediaLoader.onGlSceneReady(scene);
    }

    @Override
    public void onSurfaceChanged(int width, int height) { }

    @Override
    public void onRendererShutdown() {
      scene.glShutdown();
    }

    float LinearInterpolate(
            float y1,float y2,
            float mu)
    {
      return(y1*(1-mu)+y2*mu);
    }

    void LinearInterpolate(
            float[] y1, float[] y2,
            float mu, float[] put)
    {
      for(int i = y1.length - 1;i >= 0; i--){
        put[i] = LinearInterpolate(y1[i], y2[i], mu);
      }
    }
  }

  /** Forwards Controller events to SceneRenderer. */
  private class ControllerEventListener extends Controller.EventListener
      implements ControllerManager.EventListener {
    private boolean touchpadDown = false;
    private boolean appButtonDown = false;

    @Override
    public void onApiStatusChanged(int status) {
      Log.i(TAG, ".onApiStatusChanged " + status);
    }

    @Override
    public void onRecentered() {}

    @Override
    public void onUpdate() {
      controller.update();
      renderer.scene.setControllerOrientation(controller.orientation);

      if (!touchpadDown && controller.clickButtonState) {
        renderer.scene.handleClick();
      }

      if (!appButtonDown && controller.appButtonState) {
        renderer.scene.toggleUi();
      }

      touchpadDown = controller.clickButtonState;
      appButtonDown = controller.appButtonState;
    }
  }
}
