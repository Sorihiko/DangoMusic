#version 120

uniform sampler2D u_Font;
uniform vec4 u_Color;
uniform float u_Opacity;

varying vec2 uv;

void main() {
    float alpha = texture2D(u_Font, uv).r;
    gl_FragColor = vec4(u_Color.rgb, u_Color.a * alpha * u_Opacity);
}