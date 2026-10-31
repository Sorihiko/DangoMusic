#version 120

uniform sampler2D u_Texture;
uniform float u_Opacity;

varying vec2 uv;

void main() {
    vec4 color = texture2D(u_Texture, uv);
    gl_FragColor = vec4(color.rgb, color.a * u_Opacity);
}