/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika.gles;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;
import android.view.Surface;

/**
 * Core EGL state (display, context, config).
 * <p>
 * The EGLContext must only be attached to one thread at a time.  This class is not thread-safe.
 * 教程：
 * 0.https://source.android.google.cn/devices/graphics/arch-egl-opengl?hl=zh-cn - 必看！
 * 1.https://www.jianshu.com/p/d5ff1ff4ee2a
 * 2.http://vencial.com/opengl/offscreen/rendering
 * 3.https://hetaodie.github.io/eglMakeCurrent%E5%8F%8A%E5%85%B6%E7%9B%B8%E5%85%B3%E7%9F%A5%E8%AF%86
 * 4.http://www.cnitblog.com/zouzheng/archive/2011/05/30/74326.aspx
 * 5.http://zhangtielei.com/posts/blog-opengl-transformations-1.html - 坐标系变换
 * 6.https://blog.piasy.com/2016/06/07/Open-gl-es-android-2-part-1/index.html
 */
public final class EglCore {
    private static final String TAG = GlUtil.TAG;

    /**
     * Constructor flag: surface must be recordable.  This discourages EGL from using a
     * pixel format that cannot be converted efficiently to something usable by the video
     * encoder.
     */
    public static final int FLAG_RECORDABLE = 0x01;

    /**
     * Constructor flag: ask for GLES3, fall back to GLES2 if not available.  Without this
     * flag, GLES2 is used.
     */
    public static final int FLAG_TRY_GLES3 = 0x02;

    // Android-specific extension.
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig mEGLConfig = null;
    private int mGlVersion = -1;


    /**
     * Prepares EGL display and context.
     * <p>
     * Equivalent to EglCore(null, 0).
     */
    public EglCore() {
        this(null, 0);
    }

    /**
     * Prepares EGL display and context.
     * <p>
     * @param sharedContext The context to share, or null if sharing is not desired.
     * @param flags Configuration bit flags, e.g. FLAG_RECORDABLE.
     */
    public EglCore(EGLContext sharedContext, int flags) {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL already set up");
        }

        if (sharedContext == null) {
            sharedContext = EGL14.EGL_NO_CONTEXT;
        }

        // 1.返回默认的屏幕设备抽象，也有一些其他说法：设备上的底层窗口系统
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        // 2.初始化屏幕设备抽象，OpenGL 与屏幕设备进行桥接
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        // 3.获取屏幕设备渲染表面配置 EGLConfig
        // 4.通过 EGLConfig & EGLDisplay 创建渲染上下文 EGLContext，代表 OpenGL 状态机, OpenGL 指令的执行环境
        // 换个说法解释下 EGLContext 的作用 -> 存储渲染相关的输入数据
        // Try to get a GLES3 context, if requested.
        if ((flags & FLAG_TRY_GLES3) != 0) {
            Log.d(TAG, "Trying GLES 3");
            // 确定渲染表面的配置信息，指定 FrameBuffer 的配置项，如色彩格式、像素格式、RGBA 的表示以及 SurfaceType 等
            EGLConfig config = getConfig(flags, 3);
            if (config != null) {
                int[] attrL3list = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL14.EGL_NONE
                };
                EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, sharedContext, attrL3list, 0);
                if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                    //Log.d(TAG, "Got GLES 3 config");
                    mEGLConfig = config;
                    mEGLContext = context;
                    mGlVersion = 3;
                }
            }
        }

        // GLES 2 only, or GLES 3 attempt failed
        if (mEGLContext == EGL14.EGL_NO_CONTEXT) {
            Log.d(TAG, "Trying GLES 2");
            // 确定渲染表面的配置信息，即指定 FrameBuffer 的配置项，如色彩格式、像素格式、RGBA 的表示以及 SurfaceType 等
            EGLConfig config = getConfig(flags, 2);
            if (config == null) {
                throw new RuntimeException("Unable to find a suitable EGLConfig");
            }
            int[] attr2List = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, sharedContext, attr2List, 0);
            checkEglError("eglCreateContext");
            mEGLConfig = config;
            mEGLContext = context;
            mGlVersion = 2;
        }

        // 5.通过查询 EGLContext 使用的 EGL 的版本号，来确认最终的创建是否成功
        // Confirm with query.
        int[] values = new int[1];
        EGL14.eglQueryContext(mEGLDisplay, mEGLContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0);
        Log.d(TAG, "EGLContext created, client version " + values[0]);
    }

    /**
     * Finds a suitable EGLConfig.
     *
     * @param flags Bit flags from constructor.
     * @param version Must be 2 or 3.
     */
    private EGLConfig getConfig(int flags, int version) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;
        if (version >= 3) {
            renderableType |= EGLExt.EGL_OPENGL_ES3_BIT_KHR;
        }

        // The actual surface is generally RGBA or RGBX, so situationally omitting alpha
        // doesn't really help.  It can also lead to a huge performance hit on glReadPixels()
        // when reading into a GL_RGBA buffer.
        int[] attrList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                //EGL14.EGL_DEPTH_SIZE, 16,
                //EGL14.EGL_STENCIL_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderableType,
                EGL14.EGL_NONE, 0,      // placeholder for recordable [@-3]
                EGL14.EGL_NONE
        };
        if ((flags & FLAG_RECORDABLE) != 0) {
            attrList[attrList.length - 3] = EGL_RECORDABLE_ANDROID;
            attrList[attrList.length - 2] = 1;
        }

        // 根据指定的 EGLDisplay & 声明的 attrList 来选择合适的 EGLConfig
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attrList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            Log.w(TAG, "unable to find RGB8888 / " + version + " EGLConfig");
            return null;
        }
        return configs[0];
    }

    /**
     * Discards all resources held by this class, notably the EGL context.  This must be
     * called from the thread where the context was created.
     * <p>
     * On completion, no context will be current.
     */
    public void release() {
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            // Android is unusual in that it uses a reference-counted EGLDisplay.  So for
            // every eglInitialize() we need an eglTerminate().
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(mEGLDisplay);
        }

        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        mEGLConfig = null;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                // We're limited here -- finalizers don't run on the thread that holds
                // the EGL state, so if a surface or context is still current on another
                // thread we can't fully release it here.  Exceptions thrown from here
                // are quietly discarded.  Complain in the log file.
                Log.w(TAG, "WARNING: EglCore was not explicitly released -- state may be leaked");
                release();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Destroys the specified surface.  Note the EGLSurface won't actually be destroyed if it's
     * still current in a context.
     */
    public void releaseSurface(EGLSurface eglSurface) {
        EGL14.eglDestroySurface(mEGLDisplay, eglSurface);
    }

    /**
     * Creates an EGL surface associated with a Surface.
     * If this is destined for MediaCodec, the EGLConfig should have the "recordable" attribute.
     *
     * 创建上屏渲染的 EGLSurface，并与传入的 Surface 或 SurfaceTexture 进行关联
     * Surface 设计来存储渲染相关的输出数据
     */
    public EGLSurface createWindowSurface(Object surface) {
        if (!(surface instanceof Surface) && !(surface instanceof SurfaceTexture)) {
            throw new RuntimeException("invalid surface: " + surface);
        }
        // Create a window surface, and attach it to the Surface we received.
        int[] surfaceAttributes = {
                EGL14.EGL_NONE
        };
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, surface, surfaceAttributes, 0);
        checkEglError("eglCreateWindowSurface");
        if (eglSurface == null) {
            throw new RuntimeException("Create window-surface was null");
        }
        return eglSurface;
    }

    /**
     * Creates an EGL surface associated with an offscreen buffer.
     * 创建离屏渲染的 EGLSurface
     */
    public EGLSurface createOffscreenSurface(int width, int height) {
        // 指定离屏渲染的 EGLSurface 宽高
        int[] surfaceAttributes = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        EGLSurface eglSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, mEGLConfig, surfaceAttributes, 0);
        checkEglError("eglCreatePBufferSurface");
        if (eglSurface == null) {
            throw new RuntimeException("Create offscreen-surface was null");
        }
        return eglSurface;
    }

    /**
     * Makes our EGL context current, using the supplied surface for both "draw" and "read".
     *
     * 大白话：把上下文 Context、屏幕 Display、还有渲染面 Surface、线程等关联起来
     *
     * 1.线程可查其与哪个 Context 绑定，再查 Context 与哪个 surface 进行绑定，从而得出渲染的目标
     * 2.不能在两个线程里绑定同一个 Context
     * 3.不能在两个不同的线程里，绑定相同的 Surface 到两个不同的 Context 上
     */
    public void makeCurrent(EGLSurface eglSurface) {
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            // called makeCurrent() before create?
            Log.d(TAG, "NOTE: makeCurrent w/o display");
        }
        if (!EGL14.eglMakeCurrent(mEGLDisplay, eglSurface, eglSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * Makes our EGL context current, using the supplied "draw" and "read" surfaces.
     */
    public void makeCurrent(EGLSurface drawSurface, EGLSurface readSurface) {
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            // called makeCurrent() before create?
            Log.d(TAG, "NOTE: makeCurrent w/o display");
        }
        if (!EGL14.eglMakeCurrent(mEGLDisplay, drawSurface, readSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent(draw,read) failed");
        }
    }

    /**
     * Makes no context current.
     */
    public void makeNothingCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     * EGL tell system or window to display this current rendered frame
     * @return false on failure
     */
    public boolean swapBuffers(EGLSurface eglSurface) {
        return EGL14.eglSwapBuffers(mEGLDisplay, eglSurface);
    }

    /**
     * Sends the presentation time stamp to EGL.
     * Time is expressed in nanoseconds.
     *
     * 设置当前帧的时间，单位：纳秒
     */
    public void setPresentationTime(EGLSurface eglSurface, long nsecs) {
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, eglSurface, nsecs);
    }

    /**
     * Returns true if our context and the specified surface are current.
     */
    public boolean isCurrent(EGLSurface eglSurface) {
        return mEGLContext.equals(EGL14.eglGetCurrentContext()) &&
            eglSurface.equals(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
    }

    /**
     * Performs a simple surface query.
     *
     * 查询 Surface 中的属性
     */
    public int querySurface(EGLSurface eglSurface, int what) {
        int[] value = new int[1];
        EGL14.eglQuerySurface(mEGLDisplay, eglSurface, what, value, 0);
        return value[0];
    }

    /**
     * Queries a string value.
     */
    public String queryString(int what) {
        return EGL14.eglQueryString(mEGLDisplay, what);
    }

    /**
     * Returns the GLES version this context is configured for (currently 2 or 3).
     */
    public int getGlVersion() {
        return mGlVersion;
    }

    /**
     * Writes the current display, context, and surface to the log.
     */
    public static void logCurrent(String msg) {
        EGLDisplay display;
        EGLContext context;
        EGLSurface surface;

        display = EGL14.eglGetCurrentDisplay();
        context = EGL14.eglGetCurrentContext();
        surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
        Log.i(TAG, "Current EGL (" + msg + "): display=" + display + ", context=" + context +
                ", surface=" + surface);
    }

    /**
     * Checks for EGL errors.  Throws an exception if an error has been raised.
     */
    private void checkEglError(String msg) {
        int error;
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }
}
