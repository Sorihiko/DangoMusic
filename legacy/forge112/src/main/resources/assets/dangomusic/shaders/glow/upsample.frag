#version 120

// 3x3 tent 升采样，并叠加到当前级：结果 = 当前级 + 更粗一级 * weight
// 每一级半径翻倍，多级叠加近似 Deep Glow 的平方反比衰减
uniform sampler2D u_Low;      // 更粗（更小）的一级
uniform sampler2D u_High;     // 当前级的降采样结果
uniform vec2 u_TexelSize;     // u_Low 的 texel 尺寸
uniform float u_Weight;

varying vec2 uv;

void main() {
    vec2 t = u_TexelSize;
    vec3 up = texture2D(u_Low, uv).rgb * 4.0;
    up += (texture2D(u_Low, uv + vec2(t.x, 0.0)).rgb
         + texture2D(u_Low, uv - vec2(t.x, 0.0)).rgb
         + texture2D(u_Low, uv + vec2(0.0, t.y)).rgb
         + texture2D(u_Low, uv - vec2(0.0, t.y)).rgb) * 2.0;
    up += texture2D(u_Low, uv + t).rgb
        + texture2D(u_Low, uv - t).rgb
        + texture2D(u_Low, uv + vec2(t.x, -t.y)).rgb
        + texture2D(u_Low, uv + vec2(-t.x, t.y)).rgb;
    up /= 16.0;

    gl_FragColor = vec4(texture2D(u_High, uv).rgb + up * u_Weight, 1.0);
}
