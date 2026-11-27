package dev.stillmotion.wallpaper;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

final class VideoPoster {
    private VideoPoster() {}

    static boolean present(Surface target, File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 2_600_000L) return false;
        return presentDecoded(target, BitmapFactory.decodeFile(file.getPath()));
    }

    static boolean present(Surface target, Resources resources, int resource) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        return presentDecoded(target, BitmapFactory.decodeResource(resources, resource, options));
    }

    private static boolean presentDecoded(Surface target, Bitmap bitmap) {
        if (bitmap == null) return false;
        try {
            return presentBitmap(target, bitmap);
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean presentBitmap(Surface target, Bitmap bitmap) {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return false;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface window = EGL14.EGL_NO_SURFACE;
        try {
            EGLConfig[] config = new EGLConfig[1];
            int[] count = new int[1];
            int[] attributes = {EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(display, attributes, 0, config, 0, 1, count, 0) || count[0] == 0) return false;
            context = EGL14.eglCreateContext(display, config[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            window = EGL14.eglCreateWindowSurface(display, config[0], target, new int[]{EGL14.EGL_NONE}, 0);
            if (window == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT
                    || !EGL14.eglMakeCurrent(display, window, window, context)) return false;
            int[] width = new int[1];
            int[] height = new int[1];
            if (!EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, width, 0)
                    || !EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, height, 0)
                    || width[0] <= 0 || height[0] <= 0) return false;
            return draw(bitmap, width[0], height[0]) && EGL14.eglSwapBuffers(display, window);
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            EGL14.eglReleaseThread();
        }
    }

    private static boolean draw(Bitmap bitmap, int width, int height) {
        int program = GLES20.glCreateProgram();
        int vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec2 p;attribute vec2 uv;varying vec2 v;void main(){v=uv;gl_Position=vec4(p,0.,1.);}");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float;varying vec2 v;uniform sampler2D tex;void main(){gl_FragColor=texture2D(tex,v);}");
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) return false;
        GLES20.glUseProgram(program);
        float scale = Math.max((float) width / bitmap.getWidth(), (float) height / bitmap.getHeight());
        float x = width / (bitmap.getWidth() * scale) / 2;
        float y = height / (bitmap.getHeight() * scale) / 2;
        float left = .5f - x;
        float right = .5f + x;
        float top = .5f - y;
        float bottom = .5f + y;
        float[] quad = {-1, -1, left, bottom, 1, -1, right, bottom,
                -1, 1, left, top, 1, 1, right, top};
        FloatBuffer data = ByteBuffer.allocateDirect(quad.length * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        data.put(quad).position(0);
        int[] vertexBuffer = new int[1];
        GLES20.glGenBuffers(1, vertexBuffer, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBuffer[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.length * Float.BYTES, data, GLES20.GL_STATIC_DRAW);
        attribute(program, "p", 0);
        attribute(program, "uv", 2 * Float.BYTES);
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        return GLES20.glGetError() == GLES20.GL_NO_ERROR;
    }

    private static void attribute(int program, String name, int offset) {
        int location = GLES20.glGetAttribLocation(program, name);
        GLES20.glEnableVertexAttribArray(location);
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 4 * Float.BYTES, offset);
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
