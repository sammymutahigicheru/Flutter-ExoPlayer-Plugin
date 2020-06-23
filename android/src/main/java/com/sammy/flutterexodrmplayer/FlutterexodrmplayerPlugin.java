package com.sammy.flutterexodrmplayer;

import androidx.annotation.NonNull;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.ViewGroup;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.drm.OfflineLicenseHelper;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;

import org.json.JSONArray;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.FlutterNativeView;
import io.flutter.view.TextureRegistry;

import static android.content.Context.MODE_PRIVATE;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

/** FlutterexodrmplayerPlugin */
public class FlutterexodrmplayerPlugin implements MethodCallHandler {
  private static final String TAG = "VideoPlayerPlugin";
  private static FrameworkMediaDrm mediaDrm;
  private static final String DOWNLOAD_CONTENT_DIRECTORY = "downloads";
  private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
  private final static String PREF_NAME = "MOVIDONE_EXOPLAYER";
  private final static String OFFLINE_KEY_ID = "OFFLINE_KEY_ID";
  private static OfflineLicenseHelper<FrameworkMediaCrypto> mOfflineLicenseHelper;
  private final static String DRM_LICENSE = "https://widevine-dash.ezdrm.com/proxy?pX=8C73DA";


  private static class VideoPlayer {

    private SimpleExoPlayer exoPlayer;

    private Surface surface;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;

    private QueuingEventSink eventSink = new QueuingEventSink();

    private final EventChannel eventChannel;

    private boolean isInitialized = false;
    private  DashMediaSource dashMediaSource;


    VideoPlayer(
            Context context,
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            MediaContent mediaContent,
            Result result) {
      this.eventChannel = eventChannel;
      this.textureEntry = textureEntry;

      Uri uri = Uri.parse(mediaContent.uri);
      //Add Custom DRM Management
      HttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSourceFactory(Util.getUserAgent(context, "ExoPlayerDemo"));

      // dash source
      dashMediaSource = new DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(uri);

      // DefaultLoadControl loadControl = new DefaultLoadControl.Builder().setBufferDurationsMs(32*1024, 64*1024, 1024, 1024).createDefaultLoadControl();

      // media factory
      RenderersFactory renderersFactory = new DefaultRenderersFactory(context);
      TrackSelector trackSelector = new DefaultTrackSelector();

      // Drm session manager is null if no DRM used
      DefaultDrmSessionManager<FrameworkMediaCrypto> drmSessionManager = null;


      String licenseUrl = DRM_LICENSE;

      try
      {
        // build a new drm session
        drmSessionManager = getDrmSessionManager(httpDataSourceFactory, uri, licenseUrl,context);
      }
      catch (Exception e)
      {
        // UnsupportedDrmException, IOException, InterruptedException, DrmSession.DrmSessionException
        e.printStackTrace();
        // finish();

        return;
      }


      LoadControl loadControl = new DefaultLoadControl.Builder()
              .setAllocator(new DefaultAllocator(true, 16))
              .setBufferDurationsMs(Config.MIN_BUFFER_DURATION,
                      Config.MAX_BUFFER_DURATION,
                      Config.MIN_PLAYBACK_START_BUFFER,
                      Config.MIN_PLAYBACK_RESUME_BUFFER)
              .setTargetBufferBytes(-1)
              .setPrioritizeTimeOverSizeThresholds(true).createDefaultLoadControl();

      //set up exoplayer
      exoPlayer = ExoPlayerFactory.newSimpleInstance(context, renderersFactory, trackSelector, loadControl, drmSessionManager);
      exoPlayer.prepare(dashMediaSource);
      exoPlayer.setPlayWhenReady(true);
      exoPlayer.getPlaybackState();
      setupVideoPlayer(eventChannel, textureEntry, result, context);
    }

    /*
     * Drm session manager
     *
     * */
    private DefaultDrmSessionManager<FrameworkMediaCrypto> getDrmSessionManager(HttpDataSource.Factory httpDataSourceFactory, Uri mpdUri, String licenseUrl,Context context) throws UnsupportedDrmException, IOException, InterruptedException, DrmSession.DrmSessionException
    {
      // Drm manager
      MediaDrmCallback drmCallback = new HttpMediaDrmCallback(licenseUrl, httpDataSourceFactory);
      DefaultDrmSessionManager<FrameworkMediaCrypto> drmSessionManager = DefaultDrmSessionManager.newWidevineInstance(drmCallback, null);
      // existing key set id
      byte[] offlineKeySetId = getStoredKeySetId(context);
      if (offlineKeySetId == null || !isLicenseValid(offlineKeySetId)){
        new Thread() {


          @Override
          public void run() {
            {
              try {
                DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory(
                        "ExoPlayer");
                mOfflineLicenseHelper = OfflineLicenseHelper
                        .newWidevineInstance(licenseUrl, httpDataSourceFactory);
                DataSource dataSource = httpDataSourceFactory.createDataSource();
                DashManifest dashManifest = DashUtil.loadManifest(dataSource,
                        mpdUri);
                DrmInitData drmInitData = DashUtil.loadDrmInitData(dataSource, dashManifest.getPeriod(0));
                byte[] offlineLicenseKeySetId = mOfflineLicenseHelper.downloadLicense(drmInitData);
                storeKeySetId(offlineLicenseKeySetId,context);
                // read license for logging purpose
                isLicenseValid(offlineLicenseKeySetId);

                Log.d("Sammy","Licence Download Successful: "+ offlineLicenseKeySetId);
              } catch (Exception e) {
                Log.e("Sammy", "license download failed", e);
              }
            }
          }
        }.start();
      }else
      {
        Log.d(TAG, "[LICENSE] Restore offline license");

        // Restores an offline license
        drmSessionManager.setMode(DefaultDrmSessionManager.MODE_PLAYBACK, offlineKeySetId);
      }

      return drmSessionManager;
    }

    private void setupVideoPlayer(
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            Result result, Context context) {

      eventChannel.setStreamHandler(
              new EventChannel.StreamHandler() {
                @Override
                public void onListen(Object o, EventChannel.EventSink sink) {
                  eventSink.setDelegate(sink);
                }

                @Override
                public void onCancel(Object o) {
                  eventSink.setDelegate(null);
                }
              });

      surface = new Surface(textureEntry.surfaceTexture());
      TextureView textureView = new TextureView(context);
      textureView.setLayoutParams(new ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT));
      SurfaceView view = new SurfaceView(context);
      view.setLayoutParams(new ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT));

      PlayerView player = new PlayerView(context);
      player.setLayoutParams(new ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT));
      player.setPlayer(exoPlayer);
      player.getSubtitleView().setBackgroundColor(0xFFFFFFFF);
      exoPlayer.setVideoSurfaceView(view);
      exoPlayer.setVideoTextureView(textureView);
      exoPlayer.setVideoSurfaceHolder(view.getHolder());
      exoPlayer.setVideoSurface(surface);

      exoPlayer.addListener(
              new EventListener() {

                @Override
                public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
                  if (playbackState == Player.STATE_BUFFERING) {
                    sendBufferingUpdate();
                  } else if (playbackState == Player.STATE_READY) {
                    if (!isInitialized) {
                      isInitialized = true;
                      sendInitialized();
                    }
                  } else if (playbackState == Player.STATE_ENDED) {
                    Map<String, Object> event = new HashMap<>();
                    event.put("event", "completed");
                    eventSink.success(event);
                    exoPlayer.release();
                  }
                }

                @Override
                public void onPlayerError(final ExoPlaybackException error) {
                  if (eventSink != null) {
                    eventSink.error("VideoError", "Video player had error " + error, null);
                  }
                }
              });

      Map<String, Object> reply = new HashMap<>();
      reply.put("textureId", textureEntry.id());
      result.success(reply);
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void sendInitialized() {
      if (isInitialized) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "initialized");
        event.put("duration", exoPlayer.getDuration());

        if (exoPlayer.getVideoFormat() != null) {
          Format videoFormat = exoPlayer.getVideoFormat();
          int width = videoFormat.width;
          int height = videoFormat.height;
          int rotationDegrees = videoFormat.rotationDegrees;
          // Switch the width/height if video was taken in portrait mode
          if (rotationDegrees == 90 || rotationDegrees == 270) {
            width = exoPlayer.getVideoFormat().height;
            height = exoPlayer.getVideoFormat().width;
          }
          event.put("width", width);
          event.put("height", height);
        }
        eventSink.success(event);
      }
    }

    private void sendBufferingUpdate() {
      Map<String, Object> event = new HashMap<>();
      event.put("event", "bufferingUpdate");
      List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
      // iOS supports a list of buffered ranges, so here is a list with a single range.
      event.put("values", Collections.singletonList(range));
      eventSink.success(event);
    }


    void pause() {
      exoPlayer.setPlayWhenReady(false);
    }


    void setSpeed(double speed) {
      PlaybackParameters param = new PlaybackParameters((float) speed);
      exoPlayer.setPlaybackParameters(param);
    }

    void play() {
      exoPlayer.setPlayWhenReady(true);
    }

    void setLooping(boolean value) {
      exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
      float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
      exoPlayer.setVolume(bracketedValue);
    }

    void seekTo(int location) {
      exoPlayer.seekTo(location);
    }

    long getPosition() {
      return exoPlayer.getCurrentPosition();
    }

    private void setEvent(ArrayList<String> value, String type, Map<String, Object> event) {
      JSONArray array = new JSONArray();
      if (value.size() > 0) {
        for (int i = 0; i < value.size(); i++) {
          array.put(value.get(i));
        }
      } else {
        array.put("NO_VALUE");
      }
      event.put(type, array.toString());
    }


    void dispose() {
      if (isInitialized) {
        exoPlayer.stop();
      }
      textureEntry.release();
      eventChannel.setStreamHandler(null);
      if (surface != null) {
        surface.release();
      }
      if (exoPlayer != null) {
        exoPlayer.release();
      }
    }
  }

  public static void registerWith(Registrar registrar) {
    final FlutterexodrmplayerPlugin plugin = new FlutterexodrmplayerPlugin(registrar);
    final MethodChannel channel =
            new MethodChannel(registrar.messenger(), "flutter.io/videoPlayer");
    channel.setMethodCallHandler(plugin);
    registrar.addViewDestroyListener(
            new PluginRegistry.ViewDestroyListener() {
              @Override
              public boolean onViewDestroy(FlutterNativeView view) {
                plugin.onDestroy();
                return false; // We are not interested in assuming ownership of the NativeView.
              }
            });
  }

  private FlutterexodrmplayerPlugin(Registrar registrar) {
    this.registrar = registrar;
    this.videoPlayers = new LongSparseArray<>();
  }

  private final LongSparseArray<VideoPlayer> videoPlayers;

  private final Registrar registrar;

  private void disposeAllPlayers() {
    for (int i = 0; i < videoPlayers.size(); i++) {
      videoPlayers.valueAt(i).dispose();
    }
    videoPlayers.clear();
  }

  private void onDestroy() {
    // The whole FlutterView is being destroyed. Here we release resources acquired for all instances
    // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
    // be replaced with just asserting that videoPlayers.isEmpty().
    // https://github.com/flutter/flutter/issues/20989 tracks this.
    disposeAllPlayers();
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    TextureRegistry textures = registrar.textures();
    if (textures == null) {
      result.error("no_activity", "video_player plugin requires a foreground activity", null);
      return;
    }
    switch (call.method) {
      case "init":
        disposeAllPlayers();
        break;
      case "create": {
        TextureRegistry.SurfaceTextureEntry handle = textures.createSurfaceTexture();
        EventChannel eventChannel =
                new EventChannel(
                        registrar.messenger(), "flutter.io/videoPlayer/videoEvents" + handle.id());

        VideoPlayer player;
        if (call.argument("sourcetype") != null) {

          MediaContent mediaContent = new MediaContent(
                  call.argument("uri"));
          player =
                  new VideoPlayer(
                          registrar.context(), eventChannel, handle, mediaContent, result);
//                        Log.e("DATA_RETRIVAL", "_____________SOURCETYPE EXOMEDIA____________");
        } else {
          player =
                  new VideoPlayer(
                          registrar.context(), eventChannel, handle,
                          new MediaContent(
                                  call.argument("uri")), result);
        }
        videoPlayers.put(handle.id(), player);
//                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("name"));
//                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("drm_scheme"));
//                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("uri"));
//                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("sourcetype"));
//                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("extension"));
//                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("drm_license_url"));
        break;
      }
      default: {
        long textureId = ((Number) call.argument("textureId")).longValue();
        VideoPlayer player = videoPlayers.get(textureId);
        if (player == null) {
          result.error(
                  "Unknown textureId",
                  "No video player associated with texture id " + textureId,
                  null);
          return;
        }
        onMethodCall(call, result, textureId, player);
        break;
      }
    }
  }

  private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
    switch (call.method) {
      case "setLooping":
        player.setLooping(call.argument("looping"));
        result.success(null);
        break;
      case "setVolume":
        player.setVolume(call.argument("volume"));
        result.success(null);
        break;
      case "play":
        player.play();
        result.success(null);
        break;
      case "pause":
        player.pause();
        result.success(null);
        break;
      case "seekTo":
        int location = ((Number) call.argument("location")).intValue();
        player.seekTo(location);
        result.success(null);
        break;
      case "position":
        result.success(player.getPosition());
        player.sendBufferingUpdate();
        break;
      case "dispose":
        player.dispose();
        videoPlayers.remove(textureId);
        result.success(null);
        break;
      case "speed":
        player.setSpeed(call.argument("speed"));
        result.success(null);
        break;
      default:
        result.notImplemented();
        break;
    }
  }


  private static void storeKeySetId(byte[] keySetId,Context context)
  {
    Log.d(TAG, "[LICENSE] Storing key set id value ... " + keySetId);

    if (keySetId != null)
    {
      SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
      SharedPreferences.Editor editor = sharedPreferences.edit();

      String keySetIdB64 = Base64.encodeToString(keySetId, Base64.DEFAULT);

      // encode in b64 to be able to save byte array
      editor.putString(OFFLINE_KEY_ID, keySetIdB64);
      editor.apply();

      Log.d(TAG, "[LICENSE] Stored keySetId in B64 value :" + keySetIdB64);
    }
  }

  private static byte[] getStoredKeySetId(Context context)
  {
    SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
    String keySetIdB64 = sharedPreferences.getString(OFFLINE_KEY_ID, null);

    if (keySetIdB64 != null)
    {
      byte[] keysetId =  Base64.decode(keySetIdB64, Base64.DEFAULT);
      Log.d(TAG, "[LICENSE] Stored keySetId in B64 value :" + keySetIdB64);

      return keysetId;
    }

    return null;
  }

  /**
   * Check license validity
   * @param keySetId byte[]
   * @return boolean
   * */
  private static boolean isLicenseValid(byte[] keySetId)
  {
    if (mOfflineLicenseHelper != null && keySetId != null)
    {
      try
      {
        // get license duration
        Pair<Long, Long> licenseDurationRemainingSec = mOfflineLicenseHelper.getLicenseDurationRemainingSec(keySetId);
        long licenseDuration = licenseDurationRemainingSec.first;

        Log.d(TAG, "[LICENSE] Time remaining " + licenseDuration + " sec");
        return licenseDuration > 0;
      }
      catch (DrmSession.DrmSessionException e)
      {
        e.printStackTrace();
        return false;
      }
    }

    return false;
  }
}
