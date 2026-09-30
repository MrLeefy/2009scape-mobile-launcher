package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_INSET_X;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.EfficientAndroidLWJGLKeycode;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.LwjglGlfwKeycode;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.lwjgl.glfw.CallbackBridge;

import fr.spse.gamepad_remapper.RemapperManager;
import fr.spse.gamepad_remapper.RemapperView;

/**
 * Class that displays the game surface and dispatches player input to the client.
 */
public class GLFWGLSurface extends View implements GrabListener {
    /* Gamepad object for gamepad inputs, instantiated on need */
    private Gamepad mGamepad = null;

    float startX = 0;
    float startY = 0;
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector longPressDetector;

    /* The RemapperView.Builder object allows you to set which buttons to remap */
    private final RemapperManager mInputManager = new RemapperManager(getContext(), new RemapperView.Builder(null)
            .remapA(true)
            .remapB(true)
            .remapX(true)
            .remapY(true)
            .remapDpad(true)
            .remapLeftJoystick(true)
            .remapRightJoystick(true)
            .remapStart(true)
            .remapSelect(true)
            .remapLeftShoulder(true)
            .remapRightShoulder(true)
            .remapLeftTrigger(true)
            .remapRightTrigger(true));

    /* Resolution scaler option, allow downsizing a window */
    private final float mScaleFactor = LauncherPreferences.PREF_SCALE_FACTOR/100f;
    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((Activity) getContext()).heightPixels));
    /* Use to detect simple taps */
    private final TapDetector mSingleTapDetector = new TapDetector(1, TapDetector.DETECTION_METHOD_BOTH);
    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;

    /* Events can start with only a move instead of an pointerDown due to mouse passthrough */
    private boolean mShouldBeDown = false;
    /* When fingers are really near to each other, it tends to either swap or remove a pointer ! */
    private int mLastPointerCount = 0;
    /* Previous MotionEvent position, not scale */
    private float mPrevX, mPrevY;
    /* PointerID used for the moving camera */
    private int mCurrentPointerID = -1000;
    /* Initial first pointer positions non-scaled, used to test touch sloppiness */
    private float mInitialX, mInitialY;
    /* Last first pointer positions non-scaled, used to scroll distance */
    private float mScrollLastInitialX, mScrollLastInitialY;
    /* Keep camera steps proportional to drag distance rather than event rate. */
    private float mCameraPanX, mCameraPanY;
    /* How much distance a finger has to go for touch sloppiness to be disabled */
    public static final int FINGER_STILL_THRESHOLD = (int) Tools.dpToPx(9);
    /* How much distance a finger has to go to scroll */
    public static final int FINGER_SCROLL_THRESHOLD = (int) Tools.dpToPx(6);
    /* Whether the button was triggered, used by the handler */
    private boolean triggeredLeftMouseButton = false;
    /* Hold-to-drag left mouse button */
    public static final int MSG_LEFT_MOUSE_BUTTON_CHECK = 1028;
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message msg) {
            if(msg.what == MSG_LEFT_MOUSE_BUTTON_CHECK) {
                if (LauncherPreferences.PREF_DISABLE_GESTURES) return;
                float x = CallbackBridge.mouseX;
                float y = CallbackBridge.mouseY;
                if (CallbackBridge.isGrabbing() &&
                        MathUtils.dist(x, y, mInitialX, mInitialY) < FINGER_STILL_THRESHOLD) {
                    triggeredLeftMouseButton = true;
                    sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, true);
                }
            }
        }
    };



    public GLFWGLSurface(Context context) {
        this(context, null);
    }

    public GLFWGLSurface(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);

    }

    /** Initialize the view and all its settings */
    @SuppressLint("ClickableViewAccessibility")
    public void start(){
        scaleGestureDetector = new ScaleGestureDetector(this.getContext(), new ScaleListener());
        longPressDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (LauncherPreferences.PREF_DISABLE_GESTURES || CallbackBridge.isGrabbing()) return;
                CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT,
                        CallbackBridge.mouseX, CallbackBridge.mouseY);
            }
        });
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView surfaceView = new SurfaceView(getContext());
            mSurface = surfaceView;

            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                private boolean isCalled = false;
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(surfaceView.getHolder().getSurface());
                        return;
                    }
                    isCalled = true;

                    realStart(surfaceView.getHolder().getSurface());
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    refreshSize();
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
            });

            ((ViewGroup)getParent()).addView(surfaceView);
        }else{
            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            mSurface = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean isCalled = false;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    Surface tSurface = new Surface(surface);
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(tSurface);
                        return;
                    }
                    isCalled = true;

                    realStart(tSurface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    refreshSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            ((ViewGroup)getParent()).addView(textureView);
        }
    }


    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (!LauncherPreferences.PREF_DISABLE_GESTURES && scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(e);
        }
        if (longPressDetector != null) longPressDetector.onTouchEvent(e);
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                releaseTouchState();
            }
            return false;
        }

        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            if(e.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && e.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;

            // Mouse found
            if(CallbackBridge.isGrabbing()) return false;
            CallbackBridge.sendCursorPos(   e.getX(i) * mScaleFactor, e.getY(i) * mScaleFactor);
            return true; //mouse event handled successfully
        }

        // System.out.println("Pre touch, isTouchInHotbar=" + Boolean.toString(isTouchInHotbar) + ", action=" + MotionEvent.actionToString(e.getActionMasked()));

        //Getting scaled position from the event
        if(!CallbackBridge.isGrabbing()) {
            CallbackBridge.mouseX = (e.getX() * mScaleFactor);
            CallbackBridge.mouseY = (e.getY() * mScaleFactor);
            // A multi-touch gesture must not complete the down/up pair as a tap.
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                mSingleTapDetector.reset();
            } else if(mSingleTapDetector.onTouchEvent(e)){
                CallbackBridge.putMouseEventWithCoords(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, CallbackBridge.mouseX, CallbackBridge.mouseY);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    releaseTouchState();
                }
                return true;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                float dx = (e.getX()) - startX;
                float dy = (e.getY()) - startY;
                startX = e.getX();
                startY = e.getY();

                int pointerCount = e.getPointerCount();
                if (pointerCount == 1) panCamera(dx, dy);

                // In-menu interactions
                if(!CallbackBridge.isGrabbing()){

                    // Touch hover
                    if(pointerCount == 1){
                        CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                        mPrevX =  e.getX();
                        mPrevY =  e.getY();
                        break;
                    }

                    // Scrolling feature
                    if(LauncherPreferences.PREF_DISABLE_GESTURES) break;
                    // The pointer count can never be 0, and it is not 1, therefore it is >= 2
                    int hScroll =  ((int) (e.getX() - mScrollLastInitialX)) / FINGER_SCROLL_THRESHOLD;
                    int vScroll = ((int) (e.getY() - mScrollLastInitialY)) / FINGER_SCROLL_THRESHOLD;

                    if(vScroll != 0 || hScroll != 0){
                        CallbackBridge.sendScroll(hScroll, vScroll);
                        mScrollLastInitialX = e.getX();
                        mScrollLastInitialY = e.getY();
                    }
                    break;
                }

                // Camera movement
                int pointerIndex = e.findPointerIndex(mCurrentPointerID);
                // Start movement, due to new pointer or loss of pointer
                if (pointerIndex == -1 || mLastPointerCount != pointerCount || !mShouldBeDown) {
                    mShouldBeDown = true;
                    mCurrentPointerID = e.getPointerId(0);
                    mPrevX = e.getX();
                    mPrevY = e.getY();
                    break;
                }
                // Continue movement as usual
                CallbackBridge.mouseX += (e.getX(pointerIndex) - mPrevX) * mSensitivityFactor;
                CallbackBridge.mouseY += (e.getY(pointerIndex) - mPrevY) * mSensitivityFactor;

                mPrevX = e.getX(pointerIndex);
                mPrevY = e.getY(pointerIndex);

                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                break;

            case MotionEvent.ACTION_DOWN: // 0
                startX = e.getX();
                startY = e.getY();
                mPrevX =  e.getX();
                mPrevY =  e.getY();

                if (CallbackBridge.isGrabbing()) {
                    mCurrentPointerID = e.getPointerId(0);
                    mInitialX = CallbackBridge.mouseX;
                    mInitialY = CallbackBridge.mouseY;
                    if (!LauncherPreferences.PREF_DISABLE_GESTURES) {
                        mHandler.sendEmptyMessageDelayed(MSG_LEFT_MOUSE_BUTTON_CHECK,
                                LauncherPreferences.PREF_LONGPRESS_TRIGGER);
                    }
                } else {
                    CallbackBridge.mouseX = e.getX() * mScaleFactor;
                    CallbackBridge.mouseY = e.getY() * mScaleFactor;
                    CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    mHandler.removeMessages(MSG_LEFT_MOUSE_BUTTON_CHECK);
                    if (triggeredLeftMouseButton) {
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                        triggeredLeftMouseButton = false;
                    }
                    mShouldBeDown = false;
                    mCurrentPointerID = -1;
                    mScrollLastInitialX = e.getX(0);
                    mScrollLastInitialY = e.getY(0);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
                int liftedIndex = e.getActionIndex();
                if (e.getPointerId(liftedIndex) == mCurrentPointerID) {
                    mHandler.removeMessages(MSG_LEFT_MOUSE_BUTTON_CHECK);
                    if (triggeredLeftMouseButton) {
                        sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
                        triggeredLeftMouseButton = false;
                    }
                    mShouldBeDown = false;
                    mCurrentPointerID = -1;
                }
                if (e.getPointerCount() > 1) {
                    int remainingIndex = liftedIndex == 0 ? 1 : 0;
                    startX = e.getX(remainingIndex);
                    startY = e.getY(remainingIndex);
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseTouchState();
                break;
        }

        // Actualise the pointer count
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mLastPointerCount = 0;
        } else {
            mLastPointerCount = e.getPointerCount();
        }

        return true;
    }

    /** Release gesture-owned input when Android ends or cancels a touch sequence. */
    public void releaseTouchState() {
        mHandler.removeMessages(MSG_LEFT_MOUSE_BUTTON_CHECK);
        mSingleTapDetector.reset();
        if (triggeredLeftMouseButton) {
            sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT, false);
            triggeredLeftMouseButton = false;
        }
        startX = 0;
        startY = 0;
        mShouldBeDown = false;
        mCurrentPointerID = -1;
        mLastPointerCount = 0;
        mCameraPanX = 0;
        mCameraPanY = 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseTouchState();
        super.onDetachedFromWindow();
    }

    private void panCamera(float dx, float dy) {
        if (LauncherPreferences.PREF_DISABLE_GESTURES) {
            mCameraPanX = 0;
            mCameraPanY = 0;
            return;
        }

        // The mobile client rotates 15 degrees for each arrow-key press. Accumulate
        // finger travel in density-independent pixels so rotation is not event-rate
        // or screen-density dependent.
        final float step = Tools.dpToPx(48);
        mCameraPanX += dx;
        mCameraPanY += dy;

        while (mCameraPanX >= step) {
            AWTInputBridge.sendKey((char) AWTInputEvent.VK_RIGHT, AWTInputEvent.VK_RIGHT);
            mCameraPanX -= step;
        }
        while (mCameraPanX <= -step) {
            AWTInputBridge.sendKey((char) AWTInputEvent.VK_LEFT, AWTInputEvent.VK_LEFT);
            mCameraPanX += step;
        }
        while (mCameraPanY >= step) {
            AWTInputBridge.sendKey((char) AWTInputEvent.VK_UP, AWTInputEvent.VK_UP);
            mCameraPanY -= step;
        }
        while (mCameraPanY <= -step) {
            AWTInputBridge.sendKey((char) AWTInputEvent.VK_DOWN, AWTInputEvent.VK_DOWN);
            mCameraPanY += step;
        }
    }

    /**
     * The event for mouse/joystick movements
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        int mouseCursorIndex = -1;

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepad == null){
                mGamepad = new Gamepad(this, event.getDevice());
            }

            mInputManager.handleMotionEventInput(getContext(), event, mGamepad);
            return true;
        }

        for(int i = 0; i < event.getPointerCount(); i++) {
            if(event.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;
            // Mouse found
            mouseCursorIndex = i;
            break;
        }
        if(mouseCursorIndex == -1) return false; // we cant consoom that, theres no mice!

        // Make sure we grabbed the mouse if necessary
        updateGrabState(CallbackBridge.isGrabbing());
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                CallbackBridge.mouseX = (event.getX(mouseCursorIndex) * mScaleFactor);
                CallbackBridge.mouseY = (event.getY(mouseCursorIndex) * mScaleFactor);
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll((double) event.getAxisValue(MotionEvent.AXIS_HSCROLL), (double) event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(event.getActionButton(),true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(event.getActionButton(),false);
            default:
                return false;
        }
    }

    //TODO MOVE THIS SOMEWHERE ELSE
    /** The input event for mouse with a captured pointer */
    @RequiresApi(26)
    @Override
    public boolean dispatchCapturedPointerEvent(MotionEvent e) {
        CallbackBridge.mouseX = (e.getX() * mScaleFactor);
        CallbackBridge.mouseY = (e.getY() * mScaleFactor);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(e.getActionButton(), true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(e.getActionButton(), false);
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(e.getAxisValue(MotionEvent.AXIS_HSCROLL), e.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            default:
                return false;
        }
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Toast.makeText(this, event.toString(),Toast.LENGTH_SHORT).show();
        //Toast.makeText(this, event.getDevice().toString(), Toast.LENGTH_SHORT).show();

        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if(event.getRepeatCount() != 0) return true;
        if(event.getAction() == KeyEvent.ACTION_MULTIPLE) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }

        if(Gamepad.isGamepadEvent(event)){
            if(mGamepad == null){
                mGamepad = new Gamepad(this, event.getDevice());
            }

            mInputManager.handleKeyEventInput(getContext(), event, mGamepad);
            return true;
        }

        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(eventKeycode);
        if(EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    /** Convert the mouse button, then send it
     * @return Whether the event was processed
     */
    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = -256;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
        }
        if(glfwButton == -256) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }


    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        private static final float ZOOM_STEP = 1.12f;
        private float accumulatedScale = 1f;

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (LauncherPreferences.PREF_DISABLE_GESTURES) {
                accumulatedScale = 1f;
                return true;
            }

            accumulatedScale *= detector.getScaleFactor();
            while (accumulatedScale >= ZOOM_STEP) {
                // F3 decreases the mobile client's zoom value (zoom in).
                AWTInputBridge.sendKey((char)AWTInputEvent.VK_F3, AWTInputEvent.VK_F3);
                accumulatedScale /= ZOOM_STEP;
            }
            while (accumulatedScale <= 1f / ZOOM_STEP) {
                // F4 increases the mobile client's zoom value (zoom out).
                AWTInputBridge.sendKey((char)AWTInputEvent.VK_F4, AWTInputEvent.VK_F4);
                accumulatedScale *= ZOOM_STEP;
            }
            return true;
        }

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            accumulatedScale = 1f;
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            accumulatedScale = 1f;
        }
    }

    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize(){
        windowWidth = Tools.getDisplayFriendlyRes((int) (Tools.currentDisplayMetrics.widthPixels - (PREF_INSET_X*2)), mScaleFactor);
        windowHeight = Tools.getDisplayFriendlyRes((int) (Tools.currentDisplayMetrics.heightPixels - (PREF_INSET_X*2)), mScaleFactor);
        if(mSurface == null){
            Log.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView view = (SurfaceView) mSurface;
            if(view.getHolder() != null){
                view.getHolder().setFixedSize(windowWidth, windowHeight);
            }
        }else{
            TextureView view = (TextureView)mSurface;
            if(view.getSurfaceTexture() != null){
                view.getSurfaceTexture().setDefaultBufferSize(windowWidth, windowHeight);
            }
        }

        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);

    }

    private void realStart(Surface surface){
        // Initial size set
        refreshSize();

        JREUtils.setupBridgeWindow(surface);

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        post(()->updateGrabState(isGrabbing));
    }

    private void updateGrabState(boolean isGrabbing) {
        if(!MainActivity.isAndroid8OrHigher()) return;

        boolean hasPointerCapture = hasPointerCapture();
        if(isGrabbing){
            if(!hasPointerCapture) {
                requestFocus();
                requestPointerCapture();
            }
            return;
        }

        if(hasPointerCapture) {
            releasePointerCapture();
            clearFocus();
        }
    }

    public int dpToPx(int dp) {
        return (int) (dp * Resources.getSystem().getDisplayMetrics().density);
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }
}
