package me.sorihiko.dango.modern

import org.joml.Matrix4f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

import java.nio.FloatBuffer

/**
 * 高版本 OpenGL（3.2 core）里没有 glBegin / glVertex 了。这里模拟 1.8.9 那种"一个个点交进去再画"的写法，
 * 让移植过来的代码改动最少：
 *
 *     Imm.begin()
 *     Imm.tex(u, v); Imm.vertex(x, y)   // 四个点一组，顺序和 GL_QUADS 一样
 *     Imm.end()
 *
 * [end] 用当前绑定的着色器画（顶点属性：0 = a_Pos，1 = a_UV）；
 * [endFixed] 用 [Fixed] 的固定管线替代品画（颜色 × 贴图，矩阵来自 [Fixed]），相当于 1.8.9 的固定管线。
 *
 * 合批：[endFixed] 不马上画，而是把顶点按当时的矩阵和颜色算好，攒进一批；连续的、状态没变的可以一次画掉
 * （歌词每个字一次 → 一行字一次）。要换状态（贴图、混合、裁剪、帧缓冲、着色器……）或要用别的方式画之前，
 * 先调 [flush] 把攒着的画掉，先后顺序和效果都和一个个画完全一样。[Gl] 的状态函数会在状态真的变了时自动 flush。
 */
object Imm {
    const val ATTR_POS = 0
    const val ATTR_UV = 1

    private var vao = 0
    private var vbo = 0
    private var data = FloatArray(4 * 6 * 256)
    private var count = 0          // 已交进来的点数（按四边形算）
    private val quad = FloatArray(16)
    private var corner = 0
    private var u = 0f
    private var v = 0f
    private var upload: FloatBuffer = BufferUtils.createFloatBuffer(data.size)

    /** 当前这一批是否要贴图（Fixed 用） */
    private var textured = true

    fun begin(textured: Boolean = true) {
        count = 0
        corner = 0
        this.textured = textured
    }

    fun tex(u: Float, v: Float) {
        this.u = u
        this.v = v
    }

    fun vertex(x: Float, y: Float) {
        val o = corner * 4
        quad[o] = x; quad[o + 1] = y; quad[o + 2] = u; quad[o + 3] = v
        corner++
        if (corner == 4) {
            corner = 0
            // 四边形 → 两个三角形 (0,1,2) (0,2,3)
            ensure(count + 6)
            putCorner(0); putCorner(1); putCorner(2)
            putCorner(0); putCorner(2); putCorner(3)
        }
    }

    /**
     * 把四边形的第 [i] 个角抄进顶点数组。
     * 逐个赋值，不用临时数组和 System.arraycopy：Java 25 的 C2 编译器在优化原来那种写法
     * （被内联进画字的循环里）时会自己崩掉，整个游戏直接闪退。
     */
    private fun putCorner(i: Int) {
        val src = i * 4
        val dst = count * 4
        val d = data
        d[dst] = quad[src]
        d[dst + 1] = quad[src + 1]
        d[dst + 2] = quad[src + 2]
        d[dst + 3] = quad[src + 3]
        count++
    }

    private fun ensure(vertices: Int) {
        if (vertices * 4 <= data.size) return
        data = data.copyOf(maxOf(data.size * 2, vertices * 4))
        upload = BufferUtils.createFloatBuffer(data.size)
    }

    fun end() {
        flush()
        draw(false)
    }

    /** 一共交了几次要画的东西（26.x 的透明图层据此判断这一帧有没有画东西，没画就不交给原版 / 不用读回） */
    var draws = 0
        private set

    /** 不经过 Imm 画的东西（26.x 直接用原版字形画的字）也记一次 */
    fun countDraw() {
        draws++
    }

    /** 真正调了几次 glDrawArrays（合批之后，性能测量用） */
    var glDraws = 0
        private set

    // ---------------------------------------------------------------- 合批（只用于 endFixed）

    /** 每个点：裁剪空间坐标 4 + 贴图坐标 2 + 颜色 4 */
    private const val BATCH_STRIDE = 10
    private var batch = FloatArray(BATCH_STRIDE * 6 * 256)
    private var batchCount = 0
    private var batchTextured = true
    private var batchVao = 0
    private var batchVbo = 0
    private var batchUpload: FloatBuffer = BufferUtils.createFloatBuffer(batch.size)
    private val mvp = Matrix4f()

    /** 用 [Fixed] 当前的矩阵和颜色画刚交进来的点：先攒进这一批 */
    fun endFixed() {
        if (count == 0) return
        draws++
        if (batchCount > 0 && batchTextured != textured) flush()
        batchTextured = textured
        Fixed.matrix(mvp)
        val c = Fixed.color
        val r = c[0]
        val g = c[1]
        val b = c[2]
        val a = c[3]
        if ((batchCount + count) * BATCH_STRIDE > batch.size) {
            batch = batch.copyOf(maxOf(batch.size * 2, (batchCount + count) * BATCH_STRIDE))
            batchUpload = BufferUtils.createFloatBuffer(batch.size)
        }
        val m = mvp
        val out = batch
        var o = batchCount * BATCH_STRIDE
        for (i in 0 until count) {
            val x = data[i * 4]
            val y = data[i * 4 + 1]
            out[o] = m.m00() * x + m.m10() * y + m.m30()
            out[o + 1] = m.m01() * x + m.m11() * y + m.m31()
            out[o + 2] = m.m02() * x + m.m12() * y + m.m32()
            out[o + 3] = m.m03() * x + m.m13() * y + m.m33()
            out[o + 4] = data[i * 4 + 2]
            out[o + 5] = data[i * 4 + 3]
            out[o + 6] = r
            out[o + 7] = g
            out[o + 8] = b
            out[o + 9] = a
            o += BATCH_STRIDE
        }
        batchCount += count
        count = 0
    }

    /** 把攒着的一批画掉（没有就什么都不做） */
    fun flush() {
        VanillaTextBatch.flush()
        if (batchCount == 0) return
        val n = batchCount
        batchCount = 0
        glDraws++
        if (batchVao == 0) {
            batchVao = GL30.glGenVertexArrays()
            batchVbo = GL15.glGenBuffers()
        }
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)

        Fixed.useBatch(batchTextured)
        GL30.glBindVertexArray(batchVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchVbo)
        batchUpload.clear()
        batchUpload.put(batch, 0, n * BATCH_STRIDE)
        batchUpload.flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, batchUpload, GL15.GL_STREAM_DRAW)
        GL20.glEnableVertexAttribArray(0)
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, BATCH_STRIDE * 4, 0L)
        GL20.glEnableVertexAttribArray(1)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, BATCH_STRIDE * 4, 16L)
        GL20.glEnableVertexAttribArray(2)
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, BATCH_STRIDE * 4, 24L)
        McCompat.beforeDraw()
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, n)

        GL30.glBindVertexArray(prevVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuffer)
        GL20.glUseProgram(prevProgram)
    }

    private fun draw(fixed: Boolean) {
        if (count == 0) return
        VanillaTextBatch.flush()
        draws++
        glDraws++
        if (vao == 0) {
            vao = GL30.glGenVertexArrays()
            vbo = GL15.glGenBuffers()
        }
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)

        if (fixed) Fixed.use(textured)

        GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
        upload.clear()
        upload.put(data, 0, count * 4)
        upload.flip()
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, upload, GL15.GL_STREAM_DRAW)
        GL20.glEnableVertexAttribArray(ATTR_POS)
        GL20.glVertexAttribPointer(ATTR_POS, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(ATTR_UV)
        GL20.glVertexAttribPointer(ATTR_UV, 2, GL11.GL_FLOAT, false, 16, 8L)
        McCompat.beforeDraw()
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count)

        // 原样换回去：Minecraft 记着它自己绑的是哪个 VAO / 着色器
        GL30.glBindVertexArray(prevVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuffer)
        if (fixed) GL20.glUseProgram(prevProgram)
        count = 0
    }
}

/**
 * 固定管线的替代品：当前颜色 + 矩阵（相当于 1.8.9 的 glColor4f 和 glPushMatrix / glTranslatef …）。
 *
 * [pose] 是"模型"矩阵（不含投影）；真正画的时候乘上 Minecraft 当前的投影矩阵和视图矩阵，
 * 这样和原版的文字（Font）用的是同一套坐标。
 */
object Fixed {
    val pose = Matrix4f()
    private val stack = ArrayDeque<Matrix4f>()
    val color = floatArrayOf(1f, 1f, 1f, 1f)

    fun push() {
        stack.addLast(Matrix4f(pose))
    }

    fun pop() {
        stack.removeLastOrNull()?.let { pose.set(it) }
    }

    fun reset(base: Matrix4f? = null) {
        stack.clear()
        if (base != null) pose.set(base) else pose.identity()
    }

    fun color(r: Float, g: Float, b: Float, a: Float) {
        color[0] = r; color[1] = g; color[2] = b; color[3] = a
    }

    /** 当前的完整矩阵：投影 × 视图 × [pose]（合批时用它把点算到屏幕上） */
    fun matrix(dest: Matrix4f): Matrix4f = dest.set(McCompat.projection()).mul(McCompat.modelView()).mul(pose)

    // ---------------------------------------------------------------- 着色器

    private const val VERT = """
#version 150
in vec2 a_Pos;
in vec2 a_UV;
uniform mat4 u_MVP;
out vec2 uv;
void main() {
    gl_Position = u_MVP * vec4(a_Pos, 0.0, 1.0);
    uv = a_UV;
}
"""

    private const val FRAG = """
#version 150
uniform sampler2D u_Texture;
uniform vec4 u_Color;
uniform int u_Textured;
in vec2 uv;
out vec4 fragColor;
void main() {
    vec4 c = u_Color;
    if (u_Textured != 0) c *= texture(u_Texture, uv);
    fragColor = c;
}
"""

    private var program = 0
    private var locMvp = 0
    private var locColor = 0
    private var locTextured = 0
    private var locTexture = 0
    private val matBuf = BufferUtils.createFloatBuffer(16)
    private val mvp = Matrix4f()

    private fun compile(type: Int, src: String): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            error("Fixed shader compile failed: " + GL20.glGetShaderInfoLog(id, 4096))
        }
        return id
    }

    private fun ensure() {
        if (program != 0) return
        val vs = compile(GL20.GL_VERTEX_SHADER, VERT)
        val fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG)
        val p = GL20.glCreateProgram()
        GL20.glAttachShader(p, vs)
        GL20.glAttachShader(p, fs)
        GL20.glBindAttribLocation(p, Imm.ATTR_POS, "a_Pos")
        GL20.glBindAttribLocation(p, Imm.ATTR_UV, "a_UV")
        GL20.glLinkProgram(p)
        GL20.glDeleteShader(vs)
        GL20.glDeleteShader(fs)
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            error("Fixed shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
        }
        program = p
        locMvp = GL20.glGetUniformLocation(p, "u_MVP")
        locColor = GL20.glGetUniformLocation(p, "u_Color")
        locTextured = GL20.glGetUniformLocation(p, "u_Textured")
        locTexture = GL20.glGetUniformLocation(p, "u_Texture")
    }

    // 合批用：点已经算好在屏幕上、自带颜色
    private const val BATCH_VERT = """
#version 150
in vec4 a_Clip;
in vec2 a_UV;
in vec4 a_Color;
out vec2 uv;
out vec4 color;
void main() {
    gl_Position = a_Clip;
    uv = a_UV;
    color = a_Color;
}
"""

    private const val BATCH_FRAG = """
#version 150
uniform sampler2D u_Texture;
uniform int u_Textured;
in vec2 uv;
in vec4 color;
out vec4 fragColor;
void main() {
    vec4 c = color;
    if (u_Textured != 0) c *= texture(u_Texture, uv);
    fragColor = c;
}
"""

    private var batchProgram = 0
    private var batchLocTextured = 0
    private var batchLocTexture = 0

    /** 绑定合批用的着色器（贴图用当前 0 号单元上绑的那张） */
    fun useBatch(textured: Boolean) {
        if (batchProgram == 0) {
            val vs = compile(GL20.GL_VERTEX_SHADER, BATCH_VERT)
            val fs = compile(GL20.GL_FRAGMENT_SHADER, BATCH_FRAG)
            val p = GL20.glCreateProgram()
            GL20.glAttachShader(p, vs)
            GL20.glAttachShader(p, fs)
            GL20.glBindAttribLocation(p, 0, "a_Clip")
            GL20.glBindAttribLocation(p, 1, "a_UV")
            GL20.glBindAttribLocation(p, 2, "a_Color")
            GL20.glLinkProgram(p)
            GL20.glDeleteShader(vs)
            GL20.glDeleteShader(fs)
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                error("Fixed batch shader link failed: " + GL20.glGetProgramInfoLog(p, 4096))
            }
            batchProgram = p
            batchLocTextured = GL20.glGetUniformLocation(p, "u_Textured")
            batchLocTexture = GL20.glGetUniformLocation(p, "u_Texture")
        }
        GL20.glUseProgram(batchProgram)
        GL20.glUniform1i(batchLocTextured, if (textured) 1 else 0)
        GL20.glUniform1i(batchLocTexture, 0)
    }

    /** 绑定固定管线着色器，设好矩阵和颜色（贴图用当前 0 号单元上绑的那张） */
    fun use(textured: Boolean) {
        ensure()
        GL20.glUseProgram(program)
        mvp.set(McCompat.projection()).mul(McCompat.modelView()).mul(pose)
        matBuf.clear()
        mvp.get(matBuf)
        GL20.glUniformMatrix4fv(locMvp, false, matBuf)
        GL20.glUniform4f(locColor, color[0], color[1], color[2], color[3])
        GL20.glUniform1i(locTextured, if (textured) 1 else 0)
        GL20.glUniform1i(locTexture, 0)
    }
}
