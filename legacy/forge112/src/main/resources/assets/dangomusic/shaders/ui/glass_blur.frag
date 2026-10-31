#version 120

// 9-tap 高斯（线性采样优化为 5 次采样）
uniform sampler2D u_Texture;
uniform vec2 u_Direction; // texelSize * 方向 * 扩散

varying vec2 uv;

void main() {
    vec3 c = texture2D(u_Texture, uv).rgb * 0.2270270270;
    c += texture2D(u_Texture, uv + u_Direction * 1.3846153846).rgb * 0.3162162162;
    c += texture2D(u_Texture, uv - u_Direction * 1.3846153846).rgb * 0.3162162162;
    c += texture2D(u_Texture, uv + u_Direction * 3.2307692308).rgb * 0.0702702703;
    c += texture2D(u_Texture, uv - u_Direction * 3.2307692308).rgb * 0.0702702703;
    gl_FragColor = vec4(c, 1.0);
}
