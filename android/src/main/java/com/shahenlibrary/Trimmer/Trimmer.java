package com.shahenlibrary.Trimmer;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.coremedia.iso.boxes.Container;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;
import com.googlecode.mp4parser.authoring.tracks.AppendTrack;
import com.googlecode.mp4parser.authoring.tracks.CroppedTrack;
import com.shahenlibrary.Events.Events;
import com.shahenlibrary.interfaces.OnCompressVideoListener;
import com.shahenlibrary.utils.VideoEdit;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import wseemann.media.FFmpegMediaMetadataRetriever;


public class Trimmer {

    private static final String LOG_TAG = "Trimmer";
    private static final String FFMPEG_FILE_NAME = "libffmpeg.so";

    private static final int DEFAULT_BUFFER_SIZE = 4096;
    private static final int END_OF_FILE = -1;

    private static class FfmpegCmdAsyncTaskParams {
        ArrayList<String> cmd;
        final String pathToProcessingFile;
        Context ctx;
        final Promise promise;
        final String errorMessageTitle;
        final OnCompressVideoListener cb;

        FfmpegCmdAsyncTaskParams(ArrayList<String> cmd, final String pathToProcessingFile, Context ctx, final Promise promise, final String errorMessageTitle, final OnCompressVideoListener cb) {
            this.cmd = cmd;
            this.pathToProcessingFile = pathToProcessingFile;
            this.ctx = ctx;
            this.promise = promise;
            this.errorMessageTitle = errorMessageTitle;
            this.cb = cb;
        }
    }

    private static class FfmpegCmdAsyncTask extends AsyncTask<FfmpegCmdAsyncTaskParams, Void, Void> {

        @Override
        protected Void doInBackground(FfmpegCmdAsyncTaskParams... params) {
            ArrayList<String> cmd = params[0].cmd;
            final String pathToProcessingFile = params[0].pathToProcessingFile;
            Context ctx = params[0].ctx;
            final Promise promise = params[0].promise;
            final String errorMessageTitle = params[0].errorMessageTitle;
            final OnCompressVideoListener cb = params[0].cb;


            String errorMessageFromCmd = null;

            try {
                // NOTE: 3. EXECUTE "ffmpeg" COMMAND
                String ffmpegInDir = getFfmpegAbsolutePath(ctx);
                cmd.add(0, ffmpegInDir);
                Process p = new ProcessBuilder(cmd).start();

                BufferedReader input = getOutputFromProcess(p);
                String line = null;

                StringBuilder sInput = new StringBuilder();

                while ((line = input.readLine()) != null) {
                    Log.d(LOG_TAG, "processing ffmpeg");
                    System.out.println(sInput);
                    sInput.append(line);
                }
                input.close();

                int errorCode = p.waitFor();
                Log.d(LOG_TAG, "ffmpeg processing completed");

                if (errorCode != 0) {
                    BufferedReader error = getErrorFromProcess(p);
                    StringBuilder sError = new StringBuilder();

                    Log.d(LOG_TAG, "ffmpeg error code: " + errorCode);
                    while ((line = error.readLine()) != null) {
                        System.out.println(sError);
                        sError.append(line);
                    }
                    error.close();

                    errorMessageFromCmd = sError.toString();
                }
            } catch (Exception e) {
                errorMessageFromCmd = e.toString();
            }

            if (errorMessageFromCmd != null) {
                String errorMessage = errorMessageTitle + ": failed. " + errorMessageFromCmd;
                if (cb != null) {
                    cb.onError(errorMessage);
                } else if (promise != null) {
                    promise.reject(errorMessage);
                }
            } else {
                String filePath = "file://" + pathToProcessingFile;
                if (cb != null) {
                    cb.onSuccess(filePath);
                } else if (promise != null) {
                    WritableMap event = Arguments.createMap();
                    event.putString("source", filePath);
                    promise.resolve(event);
                }
            }

            return null;
        }

    }

    public static void getPreviewImages(String path, Promise promise, ReactApplicationContext ctx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (VideoEdit.shouldUseURI(path)) {
                retriever.setDataSource(ctx, Uri.parse(path));
            } else {
                retriever.setDataSource(path);
            }

            WritableArray images = Arguments.createArray();
            int duration = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int orientation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));

            float aspectRatio = (float) width / (float) height;

            int resizeWidth = 300;
            int resizeHeight = Math.round(resizeWidth / aspectRatio);

            float scaleWidth = ((float) resizeWidth) / width;
            float scaleHeight = ((float) resizeHeight) / height;

            Log.d(TrimmerManager.REACT_PACKAGE, "getPreviewImages: \n\tduration: " + duration +
                    "\n\twidth: " + width +
                    "\n\theight: " + height +
                    "\n\torientation: " + orientation +
                    "\n\taspectRatio: " + aspectRatio +
                    "\n\tresizeWidth: " + resizeWidth +
                    "\n\tresizeHeight: " + resizeHeight
            );

            Matrix mx = new Matrix();

            mx.postScale(scaleWidth, scaleHeight);
            mx.postRotate(orientation - 360);

            for (int i = 0; i < duration; i += duration / 10) {
                Bitmap frame = retriever.getFrameAtTime(i * 1000L);

                Log.e(LOG_TAG, "frame: " + i + " - " + frame);
                if (frame == null) {
                    continue;
                }
                Bitmap currBmp = Bitmap.createScaledBitmap(frame, resizeWidth, resizeHeight, false);

//                 Bitmap normalizedBmp = Bitmap.createBitmap(currBmp, 0, 0, resizeWidth, resizeHeight, mx, true);
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                currBmp.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                byte[] byteArray = byteArrayOutputStream.toByteArray();
                String encoded = "data:image/png;base64," + Base64.encodeToString(byteArray, Base64.DEFAULT);
                images.pushString(encoded);
                Log.e(LOG_TAG, "encoded: " + i + " - " + encoded.substring(20, 40));
            }

            WritableMap event = Arguments.createMap();

            event.putArray("images", images);

            promise.resolve(event);
        } finally {
            try {
                retriever.release();
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public static void getVideoInfo(String path, Promise promise, ReactApplicationContext ctx) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (VideoEdit.shouldUseURI(path)) {
                mmr.setDataSource(ctx, Uri.parse(path));
            } else {
                mmr.setDataSource(path);
            }
            int duration = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int orientation = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            // METADATA_KEY_FRAMERATE returns a float or int or might not exist

//            Integer frameRate = VideoEdit.getIntFromString(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_FRAMERATE));
//            // METADATA_KEY_VARIANT_BITRATE returns a int or might not exist
//            Integer bitrate = VideoEdit.getIntFromString(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VARIANT_BITRATE));
            if (orientation == 90 || orientation == 270) {
                width = width + height;
                height = width - height;
                width = width - height;
            }

            WritableMap event = Arguments.createMap();
            WritableMap size = Arguments.createMap();

            size.putInt(Events.WIDTH, width);
            size.putInt(Events.HEIGHT, height);

            event.putMap(Events.SIZE, size);
            event.putInt(Events.DURATION, duration / 1000);
            event.putInt(Events.ORIENTATION, orientation);
//            if (frameRate != null) {
//                event.putInt(Events.FRAMERATE, frameRate);
//            } else {
//                event.putNull(Events.FRAMERATE);
//            }
//            if (bitrate != null) {
//                event.putInt(Events.BITRATE, bitrate);
//            } else {
//                event.putNull(Events.BITRATE);
//            }

            promise.resolve(event);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                mmr.release();
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    static void trim(ReadableMap options, final Promise promise, ReactApplicationContext ctx) {
        String source = options.getString("source");
        String startTime = options.getString("startTime");
        String endTime = options.getString("endTime");
        int start = getTimeFromString(startTime);
        int end = getTimeFromString(endTime);
        try {
            trim(source.replace("file://", ""), start, end, ctx, promise);
        } catch (IOException e) {
            promise.reject("errorMessage");
            throw new RuntimeException(e);
        }

//        final File tempFile = createTempFile("mp4", promise, ctx);
//
//        ArrayList<String> cmd = new ArrayList<String>();
//        cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE
//
//        // NOTE: INPUT FILE
//        cmd.add("-i");
//        cmd.add(source);
//
//        // NOTE: PLACE ARGUMENTS FOR FFMPEG IN THIS ORDER:
//        // 1. "-i" (INPUT FILE)
//        // 2. "-ss" (START TIME)
//        // 3. "-to" (END TIME) or "-t" (TRIM TIME)
//        // OTHERWISE WE WILL LOSE ACCURACY AND WILL GET WRONG CLIPPED VIDEO
//
//        cmd.add("-ss");
//        cmd.add(startTime);
//
//        cmd.add("-t");
//        cmd.add(endTime);
//
////        cmd.add("-preset");
////        cmd.add("ultrafast");
//        // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
////        cmd.add("-c:v");
////        cmd.add("copy");
////        cmd.add("-c:a");
//        cmd.add("-c");
//        cmd.add("copy");
//        // NOTE: FLAG TO CONVER "AAC" AUDIO CODEC
//        // cmd.add("-strict");
//        // cmd.add("-2");
//        // NOTE: OUTPUT FILE
//        cmd.add(tempFile.getPath());
//
//        executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Trim error", null);
    }

    private static ReadableMap formatWidthAndHeightForFfmpeg(int width, int height, int availableVideoWidth, int availableVideoHeight) {
        // NOTE: WIDTH/HEIGHT FOR FFMpeg NEED TO BE DEVIDED BY 2.
        // OR YOU WILL SEE BLANK WHITE LINES FROM LEFT/RIGHT (FOR CROP), OR CRASH FOR OTHER COMMANDS
        while (width % 2 > 0 && width < availableVideoWidth) {
            width += 1;
        }
        while (width % 2 > 0 && width > 0) {
            width -= 1;
        }
        while (height % 2 > 0 && height < availableVideoHeight) {
            height += 1;
        }
        while (height % 2 > 0 && height > 0) {
            height -= 1;
        }

        WritableMap sizes = Arguments.createMap();
        sizes.putInt("width", width);
        sizes.putInt("height", height);
        return sizes;
    }

    private static ReadableMap getVideoRequiredMetadata(String source, Context ctx) {
        Log.d(LOG_TAG, "getVideoRequiredMetadata: " + source);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (VideoEdit.shouldUseURI(source)) {
                retriever.setDataSource(ctx, Uri.parse(source));
            } else {
                retriever.setDataSource(source);
            }

            int width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int bitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));

            Log.d(LOG_TAG, "getVideoRequiredMetadata: " + Integer.toString(width));
            Log.d(LOG_TAG, "getVideoRequiredMetadata: " + Integer.toString(height));
            Log.d(LOG_TAG, "getVideoRequiredMetadata: " + Integer.toString(bitrate));

            WritableMap videoMetadata = Arguments.createMap();
            videoMetadata.putInt("width", width);
            videoMetadata.putInt("height", height);
            videoMetadata.putInt("bitrate", bitrate);
            return videoMetadata;
        } finally {
            try {
                retriever.release();
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void compress(String source, ReadableMap options, final Promise promise, final OnCompressVideoListener cb, ThemedReactContext tctx, ReactApplicationContext rctx) {
        Log.d(LOG_TAG, "OPTIONS: " + options.toString());

        Context ctx = tctx != null ? tctx : rctx;

        ReadableMap videoMetadata = getVideoRequiredMetadata(source, ctx);
        int videoWidth = videoMetadata.getInt("width");
        int videoHeight = videoMetadata.getInt("height");
        int videoBitrate = videoMetadata.getInt("bitrate");

        int width = options.hasKey("width") ? (int) (options.getDouble("width")) : 0;
        int height = options.hasKey("height") ? (int) (options.getDouble("height")) : 0;

        if (width != 0 && height != 0 && videoWidth != 0 && videoHeight != 0) {
            ReadableMap sizes = formatWidthAndHeightForFfmpeg(
                    width,
                    height,
                    videoWidth,
                    videoHeight
            );
            width = sizes.getInt("width");
            height = sizes.getInt("height");
        }

        Double minimumBitrate = options.hasKey("minimumBitrate") ? options.getDouble("minimumBitrate") : null;
        Double bitrateMultiplier = options.hasKey("bitrateMultiplier") ? options.getDouble("bitrateMultiplier") : 1.0;
        Boolean removeAudio = options.hasKey("removeAudio") ? options.getBoolean("removeAudio") : false;
        Integer crf = options.hasKey("crf") ? options.getInt("crf") : null;
        Double averageBitrate = videoBitrate / bitrateMultiplier;

        if (minimumBitrate != null) {
            if (averageBitrate < minimumBitrate) {
                averageBitrate = minimumBitrate;
            }
            if (videoBitrate < minimumBitrate) {
                averageBitrate = videoBitrate * 1.0;
            }
        }

        Log.d(LOG_TAG, "getVideoRequiredMetadata: averageBitrate - " + Double.toString(averageBitrate));

        final File tempFile = createTempFile("mp4", promise, ctx);

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(source);
        cmd.add("-vcodec");
        cmd.add("libx264");
        if (crf != null) {
            cmd.add("-crf");
            cmd.add(Integer.toString(crf));
        } else {
            cmd.add("-b:v");
            cmd.add(Double.toString(averageBitrate / 1000) + "K");
            cmd.add("-bufsize");
            cmd.add(Double.toString(averageBitrate / 2000) + "K");
        }
        if (width != 0 && height != 0) {
            cmd.add("-vf");
            cmd.add("scale=" + Integer.toString(width) + ":" + Integer.toString(height));
        }

        cmd.add("-preset");
        cmd.add("ultrafast");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");

        if (removeAudio) {
            cmd.add("-an");
        }
        cmd.add(tempFile.getPath());

        executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "compress error", cb);
    }

    static File createTempFile(String extension, final Promise promise, Context ctx) {
        UUID uuid = UUID.randomUUID();
        String imageName = uuid.toString() + "-screenshot";

        File cacheDir = ctx.getCacheDir();
        File tempFile = null;
        try {
            tempFile = File.createTempFile(imageName, "." + extension, cacheDir);
        } catch (IOException e) {
            promise.reject("Failed to create temp file", e.toString());
            return null;
        }

        if (tempFile.exists()) {
            tempFile.delete();
        }

        return tempFile;
    }

    static void getPreviewImageAtPosition(String source, double sec, String format, final Promise promise, ReactApplicationContext ctx) {
        Bitmap bmp = null;
        int orientation = 0;
        FFmpegMediaMetadataRetriever metadataRetriever = new FFmpegMediaMetadataRetriever();
        try {
            FFmpegMediaMetadataRetriever.IN_PREFERRED_CONFIG = Bitmap.Config.ARGB_8888;
            metadataRetriever.setDataSource(source);

            bmp = metadataRetriever.getFrameAtTime((long) (sec * 1000000), FFmpegMediaMetadataRetriever.OPTION_CLOSEST);
            if (bmp == null) {
                promise.reject("Failed to get preview at requested position.");
                return;
            }

            // NOTE: FIX ROTATED BITMAP
            orientation = Integer.parseInt(metadataRetriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        } finally {
            metadataRetriever.release();
        }

        if (orientation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(orientation);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        }

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        WritableMap event = Arguments.createMap();

        if (format == null || (format != null && format.equals("base64"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            String encoded = Base64.encodeToString(byteArray, Base64.DEFAULT);

            event.putString("image", encoded);
        } else if (format.equals("JPEG")) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();

            File tempFile = createTempFile("jpeg", promise, ctx);

            try {
                FileOutputStream fos = new FileOutputStream(tempFile.getPath());

                fos.write(byteArray);
                fos.close();
            } catch (java.io.IOException e) {
                promise.reject("Failed to save image", e.toString());
                return;
            }

            WritableMap imageMap = Arguments.createMap();
            imageMap.putString("uri", "file://" + tempFile.getPath());

            event.putMap("image", imageMap);
        } else {
            promise.reject("Wrong format error", "Wrong 'format'. Expected one of 'base64' or 'JPEG'.");
            return;
        }

        promise.resolve(event);
    }

    static void getTrimmerPreviewImages(String source, double startTime, double endTime, int step, String format, final Promise promise, ReactApplicationContext ctx) {
        FFmpegMediaMetadataRetriever retriever = new FFmpegMediaMetadataRetriever();
        try {
            FFmpegMediaMetadataRetriever.IN_PREFERRED_CONFIG = Bitmap.Config.ARGB_8888;
            retriever.setDataSource(source);

            WritableArray images = Arguments.createArray();
            int duration = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_DURATION));
            int width = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int height = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int orientation = Integer.parseInt(retriever.extractMetadata(FFmpegMediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));

            float aspectRatio = (float) width / (float) height;

            int resizeHeight = 100;
            int resizeWidth = Math.round(resizeHeight * aspectRatio);

            float scaleWidth = ((float) resizeWidth) / width;
            float scaleHeight = ((float) resizeHeight) / height;

            Log.d(TrimmerManager.REACT_PACKAGE, "getPreviewImages: \n\tduration: " + duration +
                    "\n\twidth: " + width +
                    "\n\theight: " + height +
                    "\n\torientation: " + orientation +
                    "\n\taspectRatio: " + aspectRatio +
                    "\n\tresizeWidth: " + resizeWidth +
                    "\n\tresizeHeight: " + resizeHeight
            );

            Matrix mx = new Matrix();

            mx.postScale(scaleWidth, scaleHeight);
            mx.postRotate(orientation - 360);

            for (int i = (int) startTime; i < (int) endTime; i += step) {
                Bitmap frame = retriever.getScaledFrameAtTime((long) i * 1000000, resizeWidth, resizeHeight);

                if (frame == null) {
                    continue;
                }
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                byte[] byteArray = byteArrayOutputStream.toByteArray();
                String encoded = Base64.encodeToString(byteArray, Base64.DEFAULT);
                images.pushString(encoded);
            }


            WritableMap event = Arguments.createMap();

            event.putArray("images", images);

            promise.resolve(event);
        } finally {
            retriever.release();
        }
    }

    private static BufferedReader getOutputFromProcess(Process p) {
        return new BufferedReader(new InputStreamReader(p.getInputStream()));
    }

    private static BufferedReader getErrorFromProcess(Process p) {
        return new BufferedReader(new InputStreamReader(p.getErrorStream()));
    }

    static void crop(String source, ReadableMap options, final Promise promise, ReactApplicationContext ctx) {
        int cropWidth = (int) (options.getDouble("cropWidth"));
        int cropHeight = (int) (options.getDouble("cropHeight"));
        int cropOffsetX = (int) (options.getDouble("cropOffsetX"));
        int cropOffsetY = (int) (options.getDouble("cropOffsetY"));

        ReadableMap videoSizes = getVideoRequiredMetadata(source, ctx);
        int videoWidth = videoSizes.getInt("width");
        int videoHeight = videoSizes.getInt("height");

        ReadableMap sizes = formatWidthAndHeightForFfmpeg(
                cropWidth,
                cropHeight,
                // NOTE: MUST CHECK AGAINST "CROPPABLE" WIDTH/HEIGHT. NOT FULL WIDTH/HEIGHT
                videoWidth - cropOffsetX,
                videoHeight - cropOffsetY
        );
        cropWidth = sizes.getInt("width");
        cropHeight = sizes.getInt("height");

        // TODO: 1) ADD METHOD TO CHECK "IS FFMPEG LOADED".
        // 2) CHECK IT HERE
        // 3) EXPORT THAT METHOD TO "JS"

        final File tempFile = createTempFile("mp4", promise, ctx);

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE

        // NOTE: INPUT FILE
        cmd.add("-i");
        cmd.add(source);

        // NOTE: PLACE ARGUMENTS FOR FFMPEG IN THIS ORDER:
        // 1. "-i" (INPUT FILE)
        // 2. "-ss" (START TIME)
        // 3. "-to" (END TIME) or "-t" (TRIM TIME)
        // OTHERWISE WE WILL LOSE ACCURACY AND WILL GET WRONG CLIPPED VIDEO

        String startTime = options.hasKey("startTime") ? options.getString("startTime") : null;
        if (startTime != null) {
            cmd.add("-ss");
            cmd.add(startTime);
        }

        String endTime = options.hasKey("endTime") ? options.getString("endTime") : null;
        if (endTime != null) {
            cmd.add("-to");
            cmd.add(endTime);
        }

        cmd.add("-vf");
        cmd.add("crop=" + Integer.toString(cropWidth) + ":" + Integer.toString(cropHeight) + ":" + Integer.toString(cropOffsetX) + ":" + Integer.toString(cropOffsetY));

        cmd.add("-preset");
        cmd.add("ultrafast");
        // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
        cmd.add("-c:a");
        cmd.add("copy");
        // NOTE: FLAG TO CONVER "AAC" AUDIO CODEC
        cmd.add("-strict");
        cmd.add("-2");
        // NOTE: OUTPUT FILE
        cmd.add(tempFile.getPath());

        executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Crop error", null);
    }

    static void boomerang(String source, final Promise promise, ReactApplicationContext ctx) {

        final File tempFile = createTempFile("mp4", promise, ctx);

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE

        // NOTE: INPUT FILE
        cmd.add("-i");
        cmd.add(source);

        // NOTE: DO THE REVERSAL (credit: https://stackoverflow.com/a/42257863/6894670)
        cmd.add("-filter_complex");
        cmd.add("[0:v]reverse,fifo[r];[0:v][r] concat=n=2:v=1 [v]");

        cmd.add("-map");
        cmd.add("[v]");

        cmd.add("-preset");
        cmd.add("ultrafast");
        // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
        cmd.add("-c:a");
        cmd.add("copy");
        // NOTE: FLAG TO CONVER "AAC" AUDIO CODEC
        cmd.add("-strict");
        cmd.add("-2");
        // NOTE: OUTPUT FILE
        cmd.add(tempFile.getPath());

        executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Boomerang error", null);
    }

    static void reverse(String source, final Promise promise, ReactApplicationContext ctx) {

        final File tempFile = createTempFile("mp4", promise, ctx);

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE

        // NOTE: INPUT FILE
        cmd.add("-i");
        cmd.add(source);

        // NOTE: DO THE REVERSAL (credit: https://video.stackexchange.com/a/17739)
        cmd.add("-vf");
        cmd.add("reverse");

        cmd.add("-preset");
        cmd.add("ultrafast");
        // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
        cmd.add("-c:a");
        cmd.add("copy");
        // NOTE: FLAG TO CONVER "AAC" AUDIO CODEC
        cmd.add("-strict");
        cmd.add("-2");
        // NOTE: OUTPUT FILE
        cmd.add(tempFile.getPath());

        executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Reverse error", null);
    }

    static void merge(ReadableArray videoFiles, String concatCmd, final Promise promise, ReactApplicationContext ctx) {
        final File tempFile = createTempFile("mp4", promise, ctx);

        Log.d(LOG_TAG, "Merging in progress.");

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add("-y"); // NOTE: OVERWRITE OUTPUT FILE

        for (int i = 0; i < videoFiles.size(); i++) {
            cmd.add("-i");
            cmd.add(videoFiles.getString(i));
        }

        cmd.add("-filter_complex");
        cmd.add(concatCmd);

        cmd.add("-map");
        cmd.add("[v]");

        cmd.add("-preset");
        cmd.add("ultrafast");

        // NOTE: DO NOT CONVERT AUDIO TO SAVE TIME
        cmd.add("-c:a");

        cmd.add("copy");
        cmd.add(tempFile.getPath());

        executeFfmpegCommand(cmd, tempFile.getPath(), ctx, promise, "Merge error", null);
    }

    static private Void executeFfmpegCommand(@NonNull ArrayList<String> cmd, @NonNull final String pathToProcessingFile, @NonNull Context ctx, @NonNull final Promise promise, @NonNull final String errorMessageTitle, @Nullable final OnCompressVideoListener cb) {
        FfmpegCmdAsyncTaskParams ffmpegCmdAsyncTaskParams = new FfmpegCmdAsyncTaskParams(cmd, pathToProcessingFile, ctx, promise, errorMessageTitle, cb);

        FfmpegCmdAsyncTask ffmpegCmdAsyncTask = new FfmpegCmdAsyncTask();
        ffmpegCmdAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, ffmpegCmdAsyncTaskParams);

        return null;
    }

    private static String getFfmpegAbsolutePath(Context ctx) {
        File folder = new File(ctx.getApplicationInfo().nativeLibraryDir);
        return new File(folder, FFMPEG_FILE_NAME).getAbsolutePath();
    }

    public static String getSha1FromFile(final File file) {
        MessageDigest messageDigest = null;
        try {
            messageDigest = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            Log.d(LOG_TAG, "Failed to load SHA1 Algorithm. " + e.toString());
            return "";
        }

        try {
            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                final byte[] buffer = new byte[1024];
                for (int read = 0; (read = is.read(buffer)) != -1; ) {
                    messageDigest.update(buffer, 0, read);
                }
                is.close();
            }
        } catch (IOException e) {
            Log.d(LOG_TAG, "Failed to load SHA1 Algorithm. IOException. " + e.toString());
            return "";
        }

        try (Formatter f = new Formatter()) {
            for (final byte b : messageDigest.digest()) {
                f.format("%02x", b);
            }
            return f.toString();
        }
    }

    public static void trim(String path, int startTime, int endTime, ReactApplicationContext context, Promise promise) throws IOException {
        Movie movie = MovieCreator.build(path);
        List<Track> tracks = movie.getTracks();
        movie.setTracks(new LinkedList<>());

        for (Track track : tracks) {
            long currentSample = 0;
            double currentTime = 0;
            double lastTime = -1;
            long startSample1 = -1;
            long endSample1 = -1;

            for (int i = 0; i < track.getSampleDurations().length; i++) {
                long delta = track.getSampleDurations()[i];


                if (currentTime > lastTime && currentTime <= startTime) {
                    // current sample is still before the new starttime
                    startSample1 = currentSample;
                }
                if (currentTime > lastTime && currentTime <= endTime) {
                    // current sample is after the new start time and still before the new endtime
                    endSample1 = currentSample;
                }
                lastTime = currentTime;
                currentTime += (double) delta / (double) track.getTrackMetaData().getTimescale();
                currentSample++;
            }
            movie.addTrack(new AppendTrack(new CroppedTrack(track, startSample1, endSample1)));
        }
        Container out = new DefaultMp4Builder().build(movie);
        File cacheDir = context.getCacheDir();
        File tempFile = null;
        try {
            tempFile = File.createTempFile("trim-cache", ".mp4", cacheDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        FileOutputStream fos = new FileOutputStream(tempFile);
        FileChannel fc = fos.getChannel();
        out.writeContainer(fc);

        fc.close();
        fos.close();
        WritableMap event = Arguments.createMap();
        event.putString("source", tempFile.getAbsolutePath());
        promise.resolve(event);
    }


    private static double correctTimeToSyncSample(Track track, double cutHere, boolean next) {
        double[] timeOfSyncSamples = new double[track.getSyncSamples().length];
        long currentSample = 0;
        double currentTime = 0;
        for (int i = 0; i < track.getSampleDurations().length; i++) {
            long delta = track.getSampleDurations()[i];

            if (Arrays.binarySearch(track.getSyncSamples(), currentSample + 1) >= 0) {
                // samples always start with 1 but we start with zero therefore +1
                timeOfSyncSamples[Arrays.binarySearch(track.getSyncSamples(), currentSample + 1)] = currentTime;
            }
            currentTime += (double) delta / (double) track.getTrackMetaData().getTimescale();
            currentSample++;

        }
        double previous = 0;
        for (double timeOfSyncSample : timeOfSyncSamples) {
            if (timeOfSyncSample > cutHere) {
                if (next) {
                    return timeOfSyncSample;
                } else {
                    return previous;
                }
            }
            previous = timeOfSyncSample;
        }
        return timeOfSyncSamples[timeOfSyncSamples.length - 1];
    }

    private static int getTimeFromString(String myDateString) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        Date date = null;
        try {
            date = sdf.parse(myDateString);
            Calendar calendar = GregorianCalendar.getInstance(); // creates a new calendar instance
            calendar.setTime(date);   // assigns calendar to given date
            int hour = calendar.get(Calendar.HOUR);
            int minute = calendar.get(Calendar.MINUTE);
            int second = calendar.get(Calendar.SECOND);
            return hour * 3600 + minute * 60 + second;
        } catch (ParseException e) {
            return 0;
        }
    }
}

