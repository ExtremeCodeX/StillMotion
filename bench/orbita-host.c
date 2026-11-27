#define _POSIX_C_SOURCE 200809L
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static GLuint compile(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint ok;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[2048];
        glGetShaderInfoLog(shader, sizeof log, NULL, log);
        fprintf(stderr, "%s\n", log);
        exit(1);
    }
    return shader;
}

static GLuint program(const char *source, int radial, int gas, int optimize) {
    char code[16384];
    if (optimize) {
        snprintf(code, sizeof code, "#define ORBITA_RADIAL %d\n#define ORBITA_GAS %d\n%s", radial, gas, source);
    } else {
        snprintf(code, sizeof code, "%s", source);
    }
    GLuint result = glCreateProgram();
    GLuint vertex = compile(GL_VERTEX_SHADER, "attribute vec2 p; void main(){gl_Position=vec4(p,0.,1.);}");
    GLuint fragment = compile(GL_FRAGMENT_SHADER, code);
    glAttachShader(result, vertex);
    glAttachShader(result, fragment);
    glBindAttribLocation(result, 0, "p");
    glLinkProgram(result);
    GLint ok;
    glGetProgramiv(result, GL_LINK_STATUS, &ok);
    assert(ok);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    return result;
}

static void read_source(const char *path, char *result, size_t length) {
    FILE *input = fopen(path, "rb");
    assert(input);
    size_t read = fread(result, 1, length - 1, input);
    assert(!ferror(input) && feof(input));
    result[read] = 0;
    fclose(input);
}

static double now_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec * 1000.0 + time.tv_nsec / 1e6;
}

int main(int argc, char **argv) {
    assert(argc == 3);
    char sources[2][8192];
    read_source(argv[1], sources[0], sizeof sources[0]);
    read_source(argv[2], sources[1], sizeof sources[1]);
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    assert(eglInitialize(display, NULL, NULL));
    EGLConfig config;
    EGLint count;
    const EGLint config_attrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
    assert(eglChooseConfig(display, config_attrs, &config, 1, &count) && count);
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attrs);
    float quad[] = {-1,-1,1,-1,-1,1,1,1};
    int w, h, radial, gas, stage, left, bottom, rw, rh, past_w = 0, past_h = 0;
    float wake, zoom;
    unsigned char *before = NULL, *after = NULL;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint programs[2][2][2] = {{{0}}};
    puts("width,height,radial,gas,stage,sample,fragment_percent,baseline_ms,optimized_ms,changed_channels,max_error");
    while (scanf("%d,%d,%d,%d,%d,%f,%f,%d,%d,%d,%d", &w,&h,&radial,&gas,&stage,&wake,&zoom,&left,&bottom,&rw,&rh) == 11) {
        size_t bytes = (size_t) w*h*4;
        if (w != past_w || h != past_h) {
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            const EGLint surface_attrs[] = {EGL_WIDTH, w, EGL_HEIGHT, h, EGL_NONE};
            surface = eglCreatePbufferSurface(display, config, surface_attrs);
            assert(eglMakeCurrent(display,surface,surface,context));
            before = realloc(before, bytes);
            after = realloc(after, bytes);
            assert(before && after);
            past_w = w; past_h = h;
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,quad);
            glClearColor(2.f/255,2.f/255,7.f/255,1.f);
            glViewport(0,0,w,h);
        }
        for (int sample = 0; sample < 5; sample++) {
            double ms[2];
            for (int order = 0; order < 2; order++) {
                int optimized = (sample + order) % 2;
                GLuint *slot = &programs[optimized][radial][gas];
                if (!*slot) *slot = program(sources[optimized],radial,gas,optimized);
                GLuint p = *slot;
                glUseProgram(p);
                glUniform2f(glGetUniformLocation(p,"size"),w,h);
                glUniform3f(glGetUniformLocation(p,"rim"),.42f,.25f,1.f);
                glUniform3f(glGetUniformLocation(p,"depth"),.09f,.11f,.41f);
                glUniform1f(glGetUniformLocation(p,"wake"),wake);
                glUniform1f(glGetUniformLocation(p,"zoom"),zoom);
                glUniform1f(glGetUniformLocation(p,"phase"),.81f+.49f*sample);
                glUniform1f(glGetUniformLocation(p,"radial"),radial);
                glUniform1f(glGetUniformLocation(p,"gas"),gas);
                if (optimized) glDisable(GL_DITHER);
                else glEnable(GL_DITHER);
                for (int frame = -1; frame < 2; frame++) {
                    double start = now_ms();
                    if (optimized && (rw != w || rh != h)) {
                        glClear(GL_COLOR_BUFFER_BIT);
                        glEnable(GL_SCISSOR_TEST);
                        glScissor(left,bottom,rw,rh);
                    }
                    glDrawArrays(GL_TRIANGLE_STRIP,0,4);
                    glDisable(GL_SCISSOR_TEST);
                    glFinish();
                    if (frame == 0) ms[optimized] = 0.;
                    if (frame >= 0) ms[optimized] += (now_ms()-start)*.5;
                }
                assert(glGetError()==GL_NO_ERROR);
                glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,optimized ? after : before);
            }
            size_t changed = 0;
            int max_error = 0;
            for (size_t i = 0; i < bytes; i++) {
                int error = abs((int) before[i] - after[i]);
                if (error) changed++;
                if (error > max_error) max_error = error;
            }
            printf("%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%zu,%d\n",
                   w,h,radial,gas,stage,sample,100.0*rw*rh/w/h,ms[0],ms[1],changed,max_error);
            fflush(stdout);
        }
    }
    fprintf(stderr, "Renderer: %s\n", glGetString(GL_RENDERER));
    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display,surface);
    eglDestroyContext(display,context);
    eglTerminate(display);
    free(before);
    free(after);
}
