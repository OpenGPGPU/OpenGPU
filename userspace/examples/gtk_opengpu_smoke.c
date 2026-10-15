/* Minimal GTK3 window exercising the OpenGPU GtkGLArea path.
 * The panel is drawn by OpenGL ES; GTK owns the window, label and button.
 */
#include <gtk/gtk.h>
#include <epoxy/gl.h>
#include <stdlib.h>
#include <stdio.h>

struct AppState {
    GtkWidget *area;
    GtkWidget *button;
    gboolean reported;
    gboolean active;
    GLuint program;
    GLuint vbo;
};

static const char *vs_src =
    "attribute vec2 position;\n"
    "void main() { gl_Position = vec4(position, 0.0, 1.0); }\n";
static const char *fs_src =
    "precision mediump float;\n"
    "uniform vec4 color;\n"
    "void main() { gl_FragColor = color; }\n";

static GLuint compile_shader(GLenum type, const char *source)
{
    GLuint shader = glCreateShader(type);
    GLint ok = 0;
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof log, NULL, log);
        g_printerr("gtk_opengpu_smoke: shader compile failed: %s\n", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static void realize(GtkGLArea *area, gpointer data)
{
    struct AppState *state = data;
    GLuint vs, fs;
    GLint ok = 0;
    const GLfloat vertices[] = {
        -0.82f, -0.60f,  0.82f, -0.60f,  0.82f, 0.60f,
        -0.82f, -0.60f,  0.82f,  0.60f, -0.82f, 0.60f,
    };
    g_print("OPENGPU GTK REALIZE ENTER\n");
    fflush(stdout);
    gtk_gl_area_make_current(area);
    {
        GError *error = gtk_gl_area_get_error(area);
        if (error) {
            g_printerr("OPENGPU GTK REALIZE ERROR: %s\n", error->message);
            return;
        }
    }
    {
        const char *vendor = (const char *)glGetString(GL_VENDOR);
        const char *renderer = (const char *)glGetString(GL_RENDERER);
        g_print("OPENGPU GTK GL VENDOR: %s\n", vendor ? vendor : "<null>");
        g_print("OPENGPU GTK GL RENDERER: %s\n", renderer ? renderer : "<null>");
        if (!renderer || !g_strrstr(renderer, "opengpu")) {
            g_printerr("OPENGPU GTK REALIZE ERROR: renderer is not OpenGPU\n");
            return;
        }
    }
    vs = compile_shader(GL_VERTEX_SHADER, vs_src);
    fs = compile_shader(GL_FRAGMENT_SHADER, fs_src);
    if (!vs || !fs)
        return;
    state->program = glCreateProgram();
    glAttachShader(state->program, vs);
    glAttachShader(state->program, fs);
    glBindAttribLocation(state->program, 0, "position");
    glLinkProgram(state->program);
    glGetProgramiv(state->program, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        g_printerr("gtk_opengpu_smoke: program link failed\n");
        return;
    }
    glGenBuffers(1, &state->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, state->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof vertices, vertices, GL_STATIC_DRAW);
    g_print("OPENGPU GTK REALIZE PASS\n");
    fflush(stdout);
}

static gboolean automatic_redraw(gpointer data);

static gboolean render(GtkGLArea *area, GdkGLContext *context, gpointer data)
{
    struct AppState *state = data;
    GLint color;
    int width = gtk_widget_get_allocated_width(GTK_WIDGET(area));
    int height = gtk_widget_get_allocated_height(GTK_WIDGET(area));
    (void)context;
    g_print("OPENGPU GTK RENDER ENTER\n");
    fflush(stdout);
    gtk_gl_area_make_current(area);
    if (gtk_gl_area_get_error(area)) {
        g_printerr("OPENGPU GTK RENDER ERROR\n");
        return FALSE;
    }
    glViewport(0, 0, width, height);
    glClearColor(0.04f, 0.06f, 0.10f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(state->program);
    color = glGetUniformLocation(state->program, "color");
    glUniform4f(color, state->active ? 0.95f : 0.20f,
                state->active ? 0.35f : 0.55f, 0.18f, 1.0f);
    glBindBuffer(GL_ARRAY_BUFFER, state->vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, NULL);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glDisableVertexAttribArray(0);
    glFinish();
    {
        GLubyte pixel[4] = {0};
        const int expected[3] = { state->active ? 242 : 51,
                                  state->active ? 89 : 140, 46 };
        glReadPixels(width / 2, height / 2,
                     1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        if (glGetError() != GL_NO_ERROR ||
            abs((int)pixel[0] - expected[0]) > 2 ||
            abs((int)pixel[1] - expected[1]) > 2 ||
            abs((int)pixel[2] - expected[2]) > 2) {
            g_printerr("OPENGPU GTK FRAME ERROR: pixel=%u,%u,%u,%u\n",
                       pixel[0], pixel[1], pixel[2], pixel[3]);
            exit(1);
        }
        g_print("OPENGPU GTK FRAME PASS: %s pixel=%u,%u,%u,%u size=%dx%d\n",
                state->active ? "active" : "idle",
                pixel[0], pixel[1], pixel[2], pixel[3], width, height);
        fflush(stdout);
    }
    if (!state->reported && getenv("OPENGPU_GTK_AUTOTEST")) {
        state->reported = TRUE;
        g_timeout_add_seconds(3, automatic_redraw, state);
    }
    return TRUE;
}

static void clicked(GtkButton *button, gpointer data)
{
    struct AppState *state = data;
    (void)button;
    state->active = !state->active;
    gtk_widget_queue_draw(state->area);
    g_print("OPENGPU GTK REDRAW: %s\n", state->active ? "active" : "idle");
}

static gboolean automatic_redraw(gpointer data)
{
    struct AppState *state = data;
    if (state->button)
        g_signal_emit_by_name(state->button, "clicked");
    else
        clicked(NULL, state);
    return G_SOURCE_REMOVE;
}

static void activate(GtkApplication *application, gpointer data)
{
    struct AppState *state = data;
    GtkWidget *window, *box, *label, *button;
    gboolean compact = getenv("OPENGPU_GTK_COMPACT") != NULL;
    g_print("OPENGPU GTK ACTIVATE PASS\n");
    fflush(stdout);
    window = gtk_application_window_new(application);
    gtk_window_set_title(GTK_WINDOW(window), "OpenGPU GTK smoke");
    gtk_window_set_default_size(GTK_WINDOW(window), compact ? 64 : 320,
                               compact ? 64 : 240);
    if (compact) {
        GtkCssProvider *css = gtk_css_provider_new();
        gtk_window_set_decorated(GTK_WINDOW(window), FALSE);
        gtk_window_fullscreen(GTK_WINDOW(window));
        gtk_css_provider_load_from_data(css,
            "* { font-size: 8px; } button { min-height: 0; min-width: 0; padding: 1px; }",
            -1, NULL);
        gtk_style_context_add_provider_for_screen(gtk_widget_get_screen(window),
            GTK_STYLE_PROVIDER(css), GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
        g_object_unref(css);
    }
    state->area = gtk_gl_area_new();
    gtk_gl_area_set_use_es(GTK_GL_AREA(state->area), TRUE);
    gtk_gl_area_set_required_version(GTK_GL_AREA(state->area), 2, 0);
    /* The OpenGPU scanout EGL config is XRGB8888 (alpha is unavailable). */
    gtk_gl_area_set_has_alpha(GTK_GL_AREA(state->area), FALSE);
    gtk_gl_area_set_has_depth_buffer(GTK_GL_AREA(state->area), FALSE);
    g_signal_connect(state->area, "realize", G_CALLBACK(realize), state);
    g_signal_connect(state->area, "render", G_CALLBACK(render), state);
    if (compact) {
        /* Keep the complete test rectangle visible in a 64x64 output. The
         * normal fixture retains its title, label and interactive button. */
        gtk_container_add(GTK_CONTAINER(window), state->area);
    } else {
        box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
        gtk_container_set_border_width(GTK_CONTAINER(box), 20);
        label = gtk_label_new("OpenGPU GtkGLArea");
        gtk_box_pack_start(GTK_BOX(box), label, FALSE, FALSE, 0);
        gtk_box_pack_start(GTK_BOX(box), state->area, TRUE, TRUE, 0);
        button = gtk_button_new_with_label("Redraw GPU panel");
        state->button = button;
        g_signal_connect(button, "clicked", G_CALLBACK(clicked), state);
        gtk_box_pack_start(GTK_BOX(box), button, FALSE, FALSE, 0);
        gtk_container_add(GTK_CONTAINER(window), box);
        gtk_widget_show_all(box);
    }
    g_print("OPENGPU GTK WINDOW BUILT\n");
    fflush(stdout);
    gtk_widget_show(window);
    gtk_widget_show(state->area);
    g_print("OPENGPU GTK SHOW PASS\n");
    fflush(stdout);
    gtk_window_present(GTK_WINDOW(window));
    g_print("OPENGPU GTK PRESENT PASS\n");
    fflush(stdout);

}

int main(int argc, char **argv)
{
    struct AppState state = {0};
    GtkApplication *application;
    int status;
    application = gtk_application_new("org.opengpu.GtkSmoke", G_APPLICATION_DEFAULT_FLAGS);
    g_signal_connect(application, "activate", G_CALLBACK(activate), &state);
    status = g_application_run(G_APPLICATION(application), argc, argv);
    g_object_unref(application);
    return status;
}
