#version 320 es
precision highp float;
precision highp int;
layout(triangles) in;
layout(triangle_strip, max_vertices=3) out;
layout(location=0) in vec3 fromVertex[][16];
layout(location=0) out vec3 toFragment[16];

void emitInput(int vertex) {
    gl_Position = gl_in[vertex].gl_Position;
    gl_PrimitiveID = gl_PrimitiveIDIn;
    gl_Layer = 1;
    toFragment = fromVertex[vertex];
    EmitVertex();
}

void main() {
    emitInput(0);
    emitInput(1);
    emitInput(2);
    EndPrimitive();
}
