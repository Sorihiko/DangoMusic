#version 120

uniform sampler2D u_Texture;

varying vec2 uv;

void main(){
    gl_FragColor = texture2D(u_Texture, uv);
}