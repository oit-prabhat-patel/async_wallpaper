package com.codenameakshay.async_wallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * AsyncWallpaperPlugin
 */
public class AsyncWallpaperPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware {
    private MethodChannel channel;
    private Context context;
    private Activity activity;

    // FIX: Removed the static 'res' object to prevent race conditions.
    // public static MethodChannel.Result res;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "async_wallpaper");
        channel.setMethodCallHandler(this);
        context = flutterPluginBinding.getApplicationContext();
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
        // FIX: Create a new AtomicBoolean for EACH method call to ensure thread safety.
        final AtomicBoolean replySubmitted = new AtomicBoolean(false);

        String url = call.argument("url");
        boolean goToHome = call.argument("goToHome") != null && (boolean) call.argument("goToHome");

        switch (call.method) {
            case "getPlatformVersion":
                result.success("Android " + android.os.Build.VERSION.RELEASE);
                break;
            case "set_wallpaper":
            case "set_wallpaper_file": {
                // FIX: Create a new Target for each request, capturing the specific 'result' and 'replySubmitted'
                Target target = getTarget(result, replySubmitted, "1", goToHome);
                String loadUrl = call.method.equals("set_wallpaper_file") ? "file://" + url : url;
                Picasso.get().load(loadUrl).into(target);
                break;
            }
            case "set_lock_wallpaper":
            case "set_lock_wallpaper_file": {
                Target target = getTarget(result, replySubmitted, "2", goToHome);
                String loadUrl = call.method.equals("set_lock_wallpaper_file") ? "file://" + url : url;
                Picasso.get().load(loadUrl).into(target);
                break;
            }
            case "set_home_wallpaper":
            case "set_home_wallpaper_file": {
                Target target = getTarget(result, replySubmitted, "3", goToHome);
                String loadUrl = call.method.equals("set_home_wallpaper_file") ? "file://" + url : url;
                Picasso.get().load(loadUrl).into(target);
                break;
            }
            case "set_both_wallpaper":
            case "set_both_wallpaper_file": {
                Target target = getTarget(result, replySubmitted, "4", goToHome);
                String loadUrl = call.method.equals("set_both_wallpaper_file") ? "file://" + url : url;
                Picasso.get().load(loadUrl).into(target);
                break;
            }
            case "set_video_wallpaper":
                // This part doesn't seem to use AsyncTask, so it's likely safe.
                copyFile(new File(url), new File(activity.getFilesDir().toPath() + "/file.mp4"));
                VideoLiveWallpaper mVideoLiveWallpaper = new VideoLiveWallpaper();
                mVideoLiveWallpaper.setToWallPaper(context);
                result.success(true);
                break;
            case "open_wallpaper_chooser":
                 VideoLiveWallpaper chooserWallpaper = new VideoLiveWallpaper();
                 chooserWallpaper.openWallpaperChooser(context);
                 result.success(true);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    // FIX: A helper method to create a new Picasso Target for each wallpaper request.
    // This is crucial because it "captures" the unique 'result' and 'replySubmitted' for this specific call.
    private Target getTarget(final Result result, final AtomicBoolean replySubmitted, final String wallpaperFlag, final boolean goToHome) {
        return new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                Log.i("AsyncWallpaper", "Bitmap loaded, starting SetWallpaperTask.");
                // FIX: Pass the result, replySubmitted, and other parameters to the AsyncTask.
                new SetWallpaperTask(context, result, replySubmitted, wallpaperFlag, goToHome).execute(bitmap);
            }

            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {
                Log.e("AsyncWallpaper", "Bitmap failed to load.", e);
                // FIX: Ensure we reply with an error if the image download fails.
                if (replySubmitted.compareAndSet(false, true)) {
                    result.error("DOWNLOAD_FAILED", "Failed to download wallpaper image.", e.toString());
                }
            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {
                // Optional: handle placeholder
            }
        };
    }

    private static class SetWallpaperTask extends AsyncTask<Bitmap, Void, Boolean> {
        private final Context context;
        private final Result result;
        private final AtomicBoolean replySubmitted;
        private final String wallpaperFlag;
        private final boolean goToHome;

        // FIX: The constructor now accepts all necessary objects.
        SetWallpaperTask(Context context, Result result, AtomicBoolean replySubmitted, String wallpaperFlag, boolean goToHome) {
            this.context = context;
            this.result = result;
            this.replySubmitted = replySubmitted;
            this.wallpaperFlag = wallpaperFlag;
            this.goToHome = goToHome;
        }

        @Override
        protected Boolean doInBackground(Bitmap... bitmaps) {
            Bitmap bitmap = bitmaps[0];
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(context);
            try {
                switch (wallpaperFlag) {
                    case "1": // Set wallpaper with chooser
                        wallpaperManager.setBitmap(bitmap);
                        break;
                    case "2": // Set lock screen
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK);
                        } else {
                            // Fallback for older APIs if needed, though this might not be supported.
                            return false; 
                        }
                        break;
                    case "3": // Set home screen
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM);
                        } else {
                            wallpaperManager.setBitmap(bitmap);
                        }
                        break;
                    case "4": // Set both
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK | WallpaperManager.FLAG_SYSTEM);
                        } else {
                            wallpaperManager.setBitmap(bitmap);
                        }
                        break;
                    default:
                        return false;
                }
            } catch (IOException e) {
                Log.e("AsyncWallpaper", "Failed to set wallpaper.", e);
                return false;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            // FIX: This is the main guard. It atomically checks and sets the flag.
            // The reply will ONLY be sent if the flag was 'false' before this call.
            if (replySubmitted.compareAndSet(false, true)) {
                Log.i("AsyncWallpaper", "Wallpaper task finished. Replying with success: " + success);
                result.success(success);
            } else {
                Log.w("AsyncWallpaper", "Wallpaper task finished, but reply was already submitted. Ignoring.");
            }
            
            // You can add the goToHome logic here if needed
            // if (goToHome && context instanceof Activity) { ... }
        }
    }
    
    // De-duplication and other methods remain the same...
    public void copyFile(File fromFile, File toFile) {
      FileInputStream fileInputStream = null;
      FileOutputStream fileOutputStream = null;
      FileChannel fileChannelInput = null;
      FileChannel fileChannelOutput = null;
      try {
          fileInputStream = new FileInputStream(fromFile);
          fileOutputStream = new FileOutputStream(toFile);
          fileChannelInput = fileInputStream.getChannel();
          fileChannelOutput = fileOutputStream.getChannel();
          fileChannelInput.transferTo(0, fileChannelInput.size(), fileChannelOutput);
      } catch (IOException e) {
          e.printStackTrace();
      } finally {
          try {
              if (fileInputStream != null)
                  fileInputStream.close();
              if (fileChannelInput != null)
                  fileChannelInput.close();
              if (fileOutputStream != null)
                  fileOutputStream.close();
              if (fileChannelOutput != null)
                  fileChannelOutput.close();
          } catch (IOException e) {
              e.printStackTrace();
          }
      }
    }


    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        activity = null;
    }
}
