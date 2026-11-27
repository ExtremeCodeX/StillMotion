package dev.stillmotion.wallpaper;

import android.content.res.Resources;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import static android.opengl.EGL14.*;
import static android.opengl.GLES20.*;

final class OrbitaRenderer implements AutoCloseable {
    private static String fragmentSource;
    private EGLDisplay display = EGL_NO_DISPLAY;
    private EGLContext context = EGL_NO_CONTEXT;
    private EGLSurface window = EGL_NO_SURFACE;
    private boolean initialized;
    private int program, size, wake, zoom, phase, after;
    private final int[] width = new int[1], height = new int[1];
    private final OrbitaBounds bounds = new OrbitaBounds();
    private boolean resize = true;
    private boolean radial;
    private FloatBuffer quad;

    boolean open(Surface surface, Resources resources, OrbitaArchive.Spec theme, boolean gasEffect) throws IOException {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, null, 0, null, 0)) return false;
        initialized = true;
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        int[] attributes = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        if (!eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return false;
        context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
                new int[]{EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE}, 0);
        if (context == EGL_NO_CONTEXT) return false;
        window = eglCreateWindowSurface(display, configs[0], surface, new int[]{EGL_NONE}, 0);
        if (window == EGL_NO_SURFACE || !eglMakeCurrent(display, window, window, context)) return false;
        try {
            int vertex = shader(GL_VERTEX_SHADER, "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}");
            radial = theme.radial;
            int fragment = shader(GL_FRAGMENT_SHADER, variant(resources, radial, gasEffect));
            program = glCreateProgram();
            glAttachShader(program, vertex);
            glAttachShader(program, fragment);
            glLinkProgram(program);
            glGetProgramiv(program, GL_LINK_STATUS, count, 0);
            if (count[0] == 0) throw new IOException(glGetProgramInfoLog(program));
            glDeleteShader(vertex);
            glDeleteShader(fragment);
            glUseProgram(program);
            size = glGetUniformLocation(program,"size");
            wake = glGetUniformLocation(program,"wake");
            zoom = glGetUniformLocation(program,"zoom");
            phase = glGetUniformLocation(program,"phase");
            after = glGetUniformLocation(program,"after");
            tint("rim", theme.rim);
            tint("depth", theme.depth);
            quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            int position = glGetAttribLocation(program,"p");
            glEnableVertexAttribArray(position);
            glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, quad);
            glClearColor(2f/255f, 2f/255f, 7f/255f, 1f);
            glDisable(GL_DITHER);
            eglSwapInterval(display, 0);
            return glGetError() == GL_NO_ERROR;
        } finally {
            unbind();
        }
    }

    boolean draw(float wakePosition, float zoomPosition, float wavePhase, float afterStrength) {
        if (!eglMakeCurrent(display, window, window, context)) return false;
        try {
            if (resize) {
                if (!eglQuerySurface(display, window, EGL_WIDTH, width, 0)
                        || !eglQuerySurface(display, window, EGL_HEIGHT, height, 0)
                        || width[0] <= 0 || height[0] <= 0) return false;
                glViewport(0, 0, width[0], height[0]);
                glUniform2f(size, width[0], height[0]);
                resize = false;
            }
            glUniform1f(wake, wakePosition);
            glUniform1f(zoom, zoomPosition);
            glUniform1f(phase, wavePhase);
            glUniform1f(after, afterStrength);
            bounds.update(width[0], height[0], wakePosition, zoomPosition, radial);
            if (bounds.width == width[0] && bounds.height == height[0]) {
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            } else {
                glClear(GL_COLOR_BUFFER_BIT);
                glEnable(GL_SCISSOR_TEST);
                glScissor(bounds.left, bounds.bottom, bounds.width, bounds.height);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                glDisable(GL_SCISSOR_TEST);
            }
            return eglSwapBuffers(display, window);
        } finally {
            unbind();
        }
    }

    void resize() {
        resize = true;
    }

    private static String variant(Resources resources, boolean radial, boolean gas) throws IOException {
        if (fragmentSource == null) {
            try (InputStream in = resources.openRawResource(R.raw.orbita)) {
                ByteArrayOutputStream source = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int n;
                while ((n = in.read(buffer)) != -1) source.write(buffer, 0, n);
                fragmentSource = source.toString("UTF-8");
            }
        }
        return "#define ORBITA_RADIAL " + (radial ? 1 : 0)
                + "\n#define ORBITA_GAS " + (gas ? 1 : 0) + "\n" + fragmentSource;
    }

    private void tint(String name, int rgb) {
        glUniform3f(glGetUniformLocation(program,name),((rgb >>> 16) & 255)/255f, ((rgb >>> 8) & 255)/255f, (rgb & 255)/255f);
    }

    private static int shader(int type, String source) throws IOException {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        int[] status = new int[1];
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) throw new IOException(glGetShaderInfoLog(shader));
        return shader;
    }

    private void unbind() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }

    @Override
    public void close() {
        if (!initialized) return;
        if (window != EGL_NO_SURFACE) eglDestroySurface(display, window);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        initialized = false;
    }
}
