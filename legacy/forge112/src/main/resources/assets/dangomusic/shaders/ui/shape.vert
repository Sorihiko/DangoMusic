#version 120

uniform vec2 u_ScreenSize;
uniform mat4 u_Transform;

varying vec2 v_Position;

void main() {
    v_Position = gl_Vertex.xy;
    vec4 vert = u_Transform * vec4(gl_Vertex.xy, 0.0, 1.0);
    vec2 pos = vert.xy / u_ScreenSize * 2.0 - 1.0;
    gl_Position = vec4(pos.x, -pos.y, vert.z, 1.0);
}