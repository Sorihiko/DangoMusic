#version 120

uniform vec2 u_ScreenSize;
uniform mat4 u_Transform;

varying vec2 v_Position; // 逻辑坐标（变换前）
varying vec2 v_Screen;   // 屏幕像素坐标（y 向下）

void main() {
    v_Position = gl_Vertex.xy;
    vec4 vert = u_Transform * vec4(gl_Vertex.xy, 0.0, 1.0);
    v_Screen = vert.xy;
    vec2 pos = vert.xy / u_ScreenSize * 2.0 - 1.0;
    gl_Position = vec4(pos.x, -pos.y, vert.z, 1.0);
}
