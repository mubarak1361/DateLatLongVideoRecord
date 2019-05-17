package com.example.datelatlongvideorecord;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ZoomControls;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements SurfaceTexture.OnFrameAvailableListener {

    public static final String TAG = "HoleInOneHero";
    private GLSurfaceView glSurfaceView;
    private TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();

    private CameraSurfaceRenderer mRenderer;
    private Camera mCamera;
    private CameraHandler mCameraHandler;
    private boolean mRecordingEnabled;      // controls button state

    private int mCameraPreviewWidth, mCameraPreviewHeight;

    private static final boolean VERBOSE = false;

    // Camera filters; must match up with cameraFilterNames in strings.xml
    static final int FILTER_NONE = 0;
    static final int FILTER_BLACK_WHITE = 1;
    static final int FILTER_BLUR = 2;
    static final int FILTER_SHARPEN = 3;
    static final int FILTER_EDGE_DETECT = 4;
    static final int FILTER_EMBOSS = 5;

    private boolean isPreviewRunning = true;
    private SimpleDateFormat formatter = new SimpleDateFormat("dd-M-yyyy hh:mm:ss", Locale.ENGLISH);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        File mediaStorageDir = new File(Environment.
                getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CameraSample");
        if (!mediaStorageDir.exists()){
            if (!mediaStorageDir.mkdirs()) {
                Log.d("CameraSample", "failed to create directory");
            }
        }
        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outputFile = new File(mediaStorageDir.getPath() + File.separator +
                "VID_"+ timeStamp + ".mp4");

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        mCameraHandler = new CameraHandler(this);

        mRecordingEnabled = sVideoEncoder.isRecording();

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        glSurfaceView = (GLSurfaceView) findViewById(R.id.cameraPreview_surfaceView);
        glSurfaceView.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(this,mCameraHandler, sVideoEncoder, outputFile);
        glSurfaceView.setRenderer(mRenderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        glSurfaceView.requestRender();
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     * <p>
     * Sets mCameraPreviewWidth and mCameraPreviewHeight to the actual width/height of the preview.
     */
    private void openCamera(int desiredWidth, int desiredHeight) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera

        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();
        CamcorderProfile profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
        profile.videoFrameWidth = desiredWidth;
        profile.videoFrameHeight = desiredHeight;
        parms.setPreviewSize(profile.videoFrameWidth, profile.videoFrameHeight);
        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        if (parms.getSupportedFocusModes().contains(
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parms.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        }

        // leave the frame rate set to default
        mCamera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }

        mCameraPreviewWidth = mCameraPreviewSize.width;
        Log.e(TAG,"Camera width: "+mCameraPreviewWidth);
        mCameraPreviewHeight = mCameraPreviewSize.height;
    }

    /**
     * onClick handler for "record" button.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        mRecordingEnabled = !mRecordingEnabled;
        glSurfaceView.queueEvent(new Runnable() {
            @Override public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(mRecordingEnabled);
            }
        });
        updateControls();
    }

    private void updateControls() {
      /*  Button toggleRelease = (Button) findViewById(R.id.toggleRecording_button);
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);
*/
        //CheckBox cb = (CheckBox) findViewById(R.id.rebindHack_checkbox);
        //cb.setChecked(TextureRender.sWorkAroundContextProblem);
    }

    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<MainActivity> mWeakActivity;

        public CameraHandler(MainActivity activity) {
            mWeakActivity = new WeakReference<MainActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            MainActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }

    /**
     * Renderer object for our GLSurfaceView.
     * <p>
     * Do not call any methods here directly from another thread -- use the
     * GLSurfaceView#queueEvent() call.
     */
    class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
        private static final String TAG = MainActivity.TAG;
        private static final boolean VERBOSE = false;

        private static final int RECORDING_OFF = 0;
        private static final int RECORDING_ON = 1;
        private static final int RECORDING_RESUMED = 2;

        private CameraHandler mCameraHandler;
        private TextureMovieEncoder mVideoEncoder;
        private File mOutputFile;

        private FullFrameRect mFullScreen;

        private final float[] mSTMatrix = new float[16];
        private int mTextureId;

        private SurfaceTexture mSurfaceTexture;
        private boolean mRecordingEnabled;
        private int mRecordingStatus;
        private int mFrameCount;

        // width/height of the incoming camera preview frames
        private boolean mIncomingSizeUpdated;
        private int mIncomingWidth;
        private int mIncomingHeight;

        private int mCurrentFilter;
        private int mNewFilter;
        private Context context;


        /**
         * Constructs CameraSurfaceRenderer.
         * <p>
         * @param cameraHandler Handler for communicating with UI thread
         * @param movieEncoder video encoder object
         * @param outputFile output file for encoded video; forwarded to movieEncoder
         */
        public CameraSurfaceRenderer(Context context, CameraHandler cameraHandler,
                                     TextureMovieEncoder movieEncoder, File outputFile) {
            this.context = context;
            mCameraHandler = cameraHandler;
            mVideoEncoder = movieEncoder;
            mOutputFile = outputFile;

            mTextureId = -1;

            mRecordingStatus = -1;
            mRecordingEnabled = false;
            mFrameCount = -1;

            mIncomingSizeUpdated = false;
            mIncomingWidth = mIncomingHeight = -1;

            // We could preserve the old filter mode, but currently not bothering.
            mCurrentFilter = -1;
            mNewFilter = FILTER_NONE;
        }

        /**
         * Notifies the renderer thread that the activity is pausing.
         * <p>
         * For best results, call this *after* disabling Camera preview.
         */
        public void notifyPausing() {
            if (mSurfaceTexture != null) {
                Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
                mSurfaceTexture.release();
                mSurfaceTexture = null;
            }
            if (mFullScreen != null) {
                mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
                mFullScreen = null;             //  to be destroyed
            }
            mIncomingWidth = mIncomingHeight = -1;
        }

        /**
         * Notifies the renderer that we want to stop or start recording.
         */
        public void changeRecordingState(boolean isRecording) {
            Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
            mRecordingEnabled = isRecording;
        }

        /**
         * Changes the filter that we're applying to the camera preview.
         */
        public void changeFilterMode(int filter) {
            mNewFilter = filter;
        }

        /**
         * Updates the filter program.
         */
        public void updateFilter() {
            Texture2dProgram.ProgramType programType;
            float[] kernel = null;
            float colorAdj = 0.0f;

            Log.d(TAG, "Updating filter to " + mNewFilter);
            switch (mNewFilter) {
                case FILTER_NONE:
                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
                    break;
                case FILTER_BLACK_WHITE:
                    // (In a previous version the TEXTURE_EXT_BW variant was enabled by a flag called
                    // ROSE_COLORED_GLASSES, because the shader set the red channel to the B&W color
                    // and green/blue to zero.)
                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
                    break;
                case FILTER_BLUR:
                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                    kernel = new float[] {
                            1f/16f, 2f/16f, 1f/16f,
                            2f/16f, 4f/16f, 2f/16f,
                            1f/16f, 2f/16f, 1f/16f };
                    break;
                case FILTER_SHARPEN:
                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                    kernel = new float[] {
                            0f, -1f, 0f,
                            -1f, 5f, -1f,
                            0f, -1f, 0f };
                    break;
                case FILTER_EDGE_DETECT:
                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                    kernel = new float[] {
                            -1f, -1f, -1f,
                            -1f, 8f, -1f,
                            -1f, -1f, -1f };
                    break;
                case FILTER_EMBOSS:
                    programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                    kernel = new float[] {
                            2f, 0f, 0f,
                            0f, -1f, 0f,
                            0f, 0f, -1f };
                    colorAdj = 0.5f;
                    break;
                default:
                    throw new RuntimeException("Unknown filter mode " + mNewFilter);
            }

            // Do we need a whole new program?  (We want to avoid doing this if we don't have
            // too -- compiling a program could be expensive.)
            if (programType != mFullScreen.getProgram().getProgramType()) {
                mFullScreen.changeProgram(new Texture2dProgram(programType));
                // If we created a new program, we need to initialize the texture width/height.
                mIncomingSizeUpdated = true;
            }

            // Update the filter kernel (if any).
            if (kernel != null) {
                mFullScreen.getProgram().setKernel(kernel, colorAdj);
            }

            mCurrentFilter = mNewFilter;
        }

        /**
         * Records the size of the incoming camera preview frames.
         * <p>
         * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
         * so we assume it could go either way.  (Fortunately they both run on the same thread,
         * so we at least know that they won't execute concurrently.)
         */
        public void setCameraPreviewSize(int width, int height) {
            Log.d(TAG, "setCameraPreviewSize");
            mIncomingWidth = width;
            mIncomingHeight = height;
            mIncomingSizeUpdated = true;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            Log.d(TAG, "onSurfaceCreated");

            // We're starting up or coming back.  Either way we've got a new EGLContext that will
            // need to be shared with the video encoder, so figure out if a recording is already
            // in progress.
            mRecordingEnabled = mVideoEncoder.isRecording();
            if (mRecordingEnabled) {
                mRecordingStatus = RECORDING_RESUMED;
            } else {
                mRecordingStatus = RECORDING_OFF;
            }

            // Set up the texture blitter that will be used for on-screen display.  This
            // is *not* applied to the recording, because that uses a separate shader.
            mFullScreen = new FullFrameRect(
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

            mTextureId = mFullScreen.createTextureObject();

            // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
            // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
            // available messages will arrive on the main thread.
            mSurfaceTexture = new SurfaceTexture(mTextureId);

            // Tell the UI thread to enable the camera preview.
            mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                    CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
            if (isPreviewRunning) {
                mCamera.stopPreview();
            }

            Camera.Parameters parameters = mCamera.getParameters();
            Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

            if(display.getRotation() == Surface.ROTATION_0) {
                mCamera.setDisplayOrientation(90);
            }

            if(display.getRotation() == Surface.ROTATION_270) {
                mCamera.setDisplayOrientation(180);
            }

            mCamera.setParameters(parameters);
            previewCamera();
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
            boolean showBox = false;

            // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
            // was there before.
            mSurfaceTexture.updateTexImage();

            // If the recording state is changing, take care of it here.  Ideally we wouldn't
            // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
            // makes it hard to do elsewhere.
            if (mRecordingEnabled) {
                switch (mRecordingStatus) {
                    case RECORDING_OFF:
                        Log.d(TAG, "START recording");
                        // start recording
                        mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                                mOutputFile, 720, 1280, 1000000, EGL14.eglGetCurrentContext()));
                        mRecordingStatus = RECORDING_ON;
                        break;
                    case RECORDING_RESUMED:
                        Log.d(TAG, "RESUME recording");
                        mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                        mRecordingStatus = RECORDING_ON;
                        break;
                    case RECORDING_ON:
                        // yay
                        break;
                    default:
                        throw new RuntimeException("unknown status " + mRecordingStatus);
                }
            } else {
                switch (mRecordingStatus) {
                    case RECORDING_ON:
                    case RECORDING_RESUMED:
                        // stop recording
                        Log.d(TAG, "STOP recording");
                        mVideoEncoder.stopRecording();
                        mRecordingStatus = RECORDING_OFF;
                        break;
                    case RECORDING_OFF:
                        // yay
                        break;
                    default:
                        throw new RuntimeException("unknown status " + mRecordingStatus);
                }
            }

            // Set the video encoder's texture name.  We only need to do this once, but in the
            // current implementation it has to happen after the video encoder is started, so
            // we just do it here.
            //
            // TODO: be less lame.
            mVideoEncoder.setTextureId(mTextureId);

            // Tell the video encoder thread that a new frame is available.
            // This will be ignored if we're not actually recording.
            mVideoEncoder.frameAvailable(mSurfaceTexture);

            if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
                // Texture size isn't set yet.  This is only used for the filters, but to be
                // safe we can just skip drawing while we wait for the various races to resolve.
                // (This seems to happen if you toggle the screen off/on with power button.)
                Log.i(TAG, "Drawing before incoming texture size set; skipping");
                return;
            }
            // Update the filter, if necessary.
            if (mCurrentFilter != mNewFilter) {
                updateFilter();
            }
            if (mIncomingSizeUpdated) {
                mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
                mIncomingSizeUpdated = false;
            }

            // Draw the video frame.
            mSurfaceTexture.getTransformMatrix(mSTMatrix);
            mFullScreen.drawFrame(mTextureId, mSTMatrix);

            // Draw a flashing box if we're recording.  This only appears on screen.
            showBox = (mRecordingStatus == RECORDING_ON);
            if (showBox && (++mFrameCount & 0x04) == 0) {
               // drawBox();
                setLogoSprite();
            }
        }

        private void setLogoSprite() {

            String strDate = formatter.format(new Date());
            //Bitmap logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_logo_128);
            Bitmap logoBitmap = fromText(strDate, 50);
            int logoHeight = logoBitmap.getHeight();
            int logoWidth = logoBitmap.getWidth();

            Log.d(TAG, "Video sprite : " + logoWidth + " " + logoHeight);

            Texture2dProgram mLogoProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D);
            Sprite3d mSpriteLogoRect = new Sprite3d(new Drawable2d(logoWidth, logoHeight));
            int logoTextureId = mLogoProgram.createTextureObject();
            mSpriteLogoRect.setTextureId(logoTextureId);
            mLogoProgram.setBitmap(logoBitmap, logoTextureId);
            logoBitmap.recycle();
            mSpriteLogoRect.transform(new Sprite3d.Transformer()
                    .rotateAroundX(180)
                    .translate(-400, 900, 0)
                    //.translate(0, 0, 0)
                    .scale(1.0f, 1.0f, 1.0f)
                    .build());

            float[] mDisplayProjectionMatrix = new float[16];

            float screenAspect = ((float) glSurfaceView.getWidth()) / glSurfaceView.getHeight();

            float near = -1.0f, far = 1.0f,
                    right = 1280 / 2,
                    top = right / screenAspect;

            Matrix.orthoM(mDisplayProjectionMatrix, 0,
                    -right, right,
                    -top, top,
                    near, far);

            mSpriteLogoRect.draw(mLogoProgram, mDisplayProjectionMatrix, GlUtil.IDENTITY_MATRIX);
        }

        private Bitmap fromText(String text, int textSize) {

            Paint paint = new Paint();//set pain object
            paint.setTextSize(textSize);//add text to it
            paint.setColor(Color.RED);// add color to it

            float baseline = -paint.ascent(); // ascent() is negative
            int width = (int) (paint.measureText(text) + 1.0f);
            int height = (int) (baseline + paint.descent() + 1.0f);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setHasAlpha(true);
            Canvas canvas = new Canvas(bitmap);
            // canvas.drawColor(Color.argb(0, 255, 255, 255));
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawText(text, 0, baseline, paint);
            //image_text.setImageBitmap(bitmap);
            return bitmap;
        }

        /**
         * Draws a red box in the corner.
         */
        private void drawBox() {
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glScissor(0, 0, 100, 100);
            GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        }
    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    private void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume -- acquiring camera");
        super.onResume();
        updateControls();
        openCamera(720, 1280);      // updates mCameraPreviewWidth/Height

        glSurfaceView.onResume();
        glSurfaceView.queueEvent(new Runnable() {
            @Override public void run() {
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight);
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause -- releasing camera");
        super.onPause();
        releaseCamera();
        glSurfaceView.queueEvent(new Runnable() {
            @Override public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });
        glSurfaceView.onPause();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        mCameraHandler.invalidateHandler();     // paranoia
    }

    public void previewCamera() {
        try {
            mCamera.setPreviewDisplay(glSurfaceView.getHolder());
            mCamera.startPreview();
            isPreviewRunning = true;
        } catch(Exception e) {
            Log.d(TAG, "Cannot start preview", e);
        }
    }

    /**
     * Enables zoom feature in native camera .  Called from listener of the view
     * used for zoom in  and zoom out.
     *
     *
     * @param zoomInOrOut  "false" for zoom in and "true" for zoom out
     */
    public void zoomCamera(boolean zoomInOrOut) {
        if(mCamera!=null) {
            Camera.Parameters parameter = mCamera.getParameters();

            if(parameter.isZoomSupported()) {
                int MAX_ZOOM = parameter.getMaxZoom();
                int currnetZoom = parameter.getZoom();
                if(zoomInOrOut && (currnetZoom <MAX_ZOOM && currnetZoom >=0)) {
                    parameter.setZoom(++currnetZoom);
                }
                else if(!zoomInOrOut && (currnetZoom <=MAX_ZOOM && currnetZoom >0)) {
                    parameter.setZoom(--currnetZoom);
                }
            }
            else
                Toast.makeText(this, "Zoom Not Avaliable", Toast.LENGTH_LONG).show();

            mCamera.setParameters(parameter);
        }
    }

}
