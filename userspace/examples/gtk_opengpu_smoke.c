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

static void realize(GtkGLArea *area, gpointer data)
{
    struct AppState *state = data;
    (void)data;
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
    state->program = 0;
    state->vbo = 0;
    g_print("OPENGPU GTK REALIZE PASS\n");
    fflush(stdout);
}

static gboolean automatic_redraw(gpointer data);

static gboolean render(GtkGLArea *area, GdkGLContext *context, gpointer data)
{
    struct AppState *state = data;
    GLubyte pixel[4] = {0};
    const int expected[3] = { state->active ? 242 : 10,
                              state->active ? 89 : 15, 30 };
    (void)context;
    g_print("OPENGPU GTK RENDER ENTER\n");
    fflush(stdout);
    gtk_gl_area_make_current(area);
    if (gtk_gl_area_get_error(area)) {
        g_printerr("OPENGPU GTK RENDER ERROR\n");
        return FALSE;
    }
    glViewport(0, 0, gtk_widget_get_allocated_width(GTK_WIDGET(area)),
               gtk_widget_get_allocated_height(GTK_WIDGET(area)));
    glClearColor(state->active ? 0.95f : 0.04f,
                 state->active ? 0.35f : 0.06f, 0.12f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glFinish();
    glReadPixels(gtk_widget_get_allocated_width(GTK_WIDGET(area)) / 2,
                 gtk_widget_get_allocated_height(GTK_WIDGET(area)) / 2,
                 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    if (glGetError() != GL_NO_ERROR ||
        abs((int)pixel[0] - expected[0]) > 3 ||
        abs((int)pixel[1] - expected[1]) > 3 ||
        abs((int)pixel[2] - expected[2]) > 3) {
        g_printerr("OPENGPU GTK FRAME ERROR: pixel=%u,%u,%u,%u\n",
                   pixel[0], pixel[1], pixel[2], pixel[3]);
        exit(1);
    }
    g_print("OPENGPU GTK FRAME PASS: %s pixel=%u,%u,%u,%u\n",
            state->active ? "active" : "idle",
            pixel[0], pixel[1], pixel[2], pixel[3]);
    fflush(stdout);
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
    g_signal_emit_by_name(state->button, "clicked");
    return G_SOURCE_REMOVE;
}

static void activate(GtkApplication *application, gpointer data)
{
    struct AppState *state = data;
    GtkWidget *window, *box, *label, *button;
    g_print("OPENGPU GTK ACTIVATE PASS\n");
    fflush(stdout);
    window = gtk_application_window_new(application);
    gtk_window_set_title(GTK_WINDOW(window), "OpenGPU GTK smoke");
    gtk_window_set_default_size(GTK_WINDOW(window), 320, 240);
    box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_container_set_border_width(GTK_CONTAINER(box), 20);
    label = gtk_label_new("OpenGPU GtkGLArea");
    gtk_box_pack_start(GTK_BOX(box), label, FALSE, FALSE, 0);
    state->area = gtk_gl_area_new();
    gtk_gl_area_set_use_es(GTK_GL_AREA(state->area), TRUE);
    gtk_gl_area_set_required_version(GTK_GL_AREA(state->area), 2, 0);
    /* The OpenGPU scanout EGL config is XRGB8888 (alpha is unavailable). */
    gtk_gl_area_set_has_alpha(GTK_GL_AREA(state->area), FALSE);
    gtk_gl_area_set_has_depth_buffer(GTK_GL_AREA(state->area), FALSE);
    g_signal_connect(state->area, "realize", G_CALLBACK(realize), state);
    g_signal_connect(state->area, "render", G_CALLBACK(render), state);
    gtk_box_pack_start(GTK_BOX(box), state->area, TRUE, TRUE, 0);
    button = gtk_button_new_with_label("Redraw GPU panel");
    state->button = button;
    g_signal_connect(button, "clicked", G_CALLBACK(clicked), state);
    gtk_box_pack_start(GTK_BOX(box), button, FALSE, FALSE, 0);
    gtk_container_add(GTK_CONTAINER(window), box);
    g_print("OPENGPU GTK WINDOW BUILT\n");
    fflush(stdout);
    gtk_widget_show(window);
    gtk_widget_show(box);
    gtk_widget_show(label);
    gtk_widget_show(state->area);
    gtk_widget_show(button);
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
