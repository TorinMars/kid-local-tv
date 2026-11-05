package com.kidlocaltv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen loading scene: a little train with a bear driver rolls through the hills, puffing
 * smoke, while the next video opens.
 *
 * Drawn with plain Canvas shapes (no images, no extra library) at about 30 frames per second, so
 * it costs little on a slow TV while LibVLC is busy opening the video. It animates only while it is
 * on screen. The scene is laid out in a 720-unit-high space scaled to the view; wider screens just
 * see more hills.
 */
class TrainLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val sky = Paint()
    private val rect = RectF()
    private val farHill = Path()
    private val nearHill = Path()

    private var scale = 1f
    /** Scene width in scene units (the height is always [SCENE_H]). */
    private var sceneW = 1280f
    private var startedAt = SystemClock.uptimeMillis()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (h == 0) return
        scale = h / SCENE_H
        sceneW = w / scale
        sky.shader = LinearGradient(0f, 0f, 0f, HORIZON, SKY_TOP, SKY_BOTTOM, Shader.TileMode.CLAMP)
        hump(farHill, FAR_PERIOD, 520f, 110f)
        hump(nearHill, NEAR_PERIOD, 570f, 80f)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
        canvas.save()
        canvas.scale(scale, scale)
        drawBackground(canvas, t)
        drawTrack(canvas, t)
        drawTrain(canvas, t)
        drawFlowers(canvas, t)
        canvas.restore()
        // Keep going only while visible; a hidden view is not drawn, so this loop stops by itself.
        if (isShown) postInvalidateDelayed(FRAME_MS)
    }

    // ---- scenery ----

    private fun drawBackground(c: Canvas, t: Float) {
        c.drawRect(0f, 0f, sceneW, SCENE_H, sky)

        // Sun with slowly turning rays.
        val sunX = sceneW * 0.84f
        val sunY = 120f
        stroke.color = SUN
        stroke.strokeWidth = 10f
        for (i in 0 until 12) {
            val a = t * 0.4f + i * (Math.PI.toFloat() / 6f)
            c.drawLine(sunX + cos(a) * 74f, sunY + sin(a) * 74f, sunX + cos(a) * 98f, sunY + sin(a) * 98f, stroke)
        }
        fill.color = SUN
        c.drawCircle(sunX, sunY, 58f, fill)

        // Clouds drifting slowly to the left.
        fill.color = CLOUD
        for (i in 0 until 4) {
            val span = sceneW + 300f
            val x = wrap(i * span / 4f - t * (14f + i * 4f), span) - 150f
            val y = 90f + (i % 2) * 90f + i * 12f
            cloud(c, x, y, 1f - i * 0.12f)
        }

        // Hills: far ones slow, near ones faster.
        fill.color = FAR_HILL
        tile(c, farHill, FAR_PERIOD, t * 30f)
        fill.color = NEAR_HILL
        tile(c, nearHill, NEAR_PERIOD, t * 70f)

        // Grass.
        fill.color = GRASS
        c.drawRect(0f, 560f, sceneW, SCENE_H, fill)

        // Trees beside the track pass quickly.
        val treeShift = wrap(t * TRACK_SPEED * 0.75f, TREE_PERIOD)
        var x = -treeShift
        var n = 0
        while (x < sceneW + TREE_PERIOD) {
            tree(c, x + 60f, 572f, if (n % 3 == 1) 0.8f else 1f)
            x += TREE_PERIOD
            n++
        }
    }

    private fun drawTrack(c: Canvas, t: Float) {
        fill.color = GRAVEL
        c.drawRect(0f, 616f, sceneW, 640f, fill)
        fill.color = SLEEPER
        val shift = wrap(t * TRACK_SPEED, SLEEPER_GAP)
        var x = -shift
        while (x < sceneW) {
            c.drawRect(x, 614f, x + 18f, 628f, fill)
            x += SLEEPER_GAP
        }
        fill.color = RAIL
        c.drawRect(0f, 608f, sceneW, 616f, fill)
    }

    /** Flowers in the front grass, passing fastest of all. */
    private fun drawFlowers(c: Canvas, t: Float) {
        val colors = intArrayOf(BALL_RED, TRIM, BALL_WHITE, WAGON_PURPLE)
        var x = -wrap(t * TRACK_SPEED * 1.4f, FLOWER_PERIOD)
        var n = ((t * TRACK_SPEED * 1.4f) / FLOWER_PERIOD).toInt()
        while (x < sceneW + FLOWER_PERIOD) {
            val fx = x + (n * 37 % 60)
            val fy = 672f + (n % 3) * 14f
            stroke.color = LEAVES
            stroke.strokeWidth = 4f
            c.drawLine(fx, fy, fx, fy + 22f, stroke)
            fill.color = colors[((n % 4) + 4) % 4]
            for (i in 0 until 5) {
                val a = i * (2f * Math.PI.toFloat() / 5f)
                c.drawCircle(fx + cos(a) * 9f, fy + sin(a) * 9f, 7f, fill)
            }
            fill.color = TRIM
            c.drawCircle(fx, fy, 6f, fill)
            x += FLOWER_PERIOD
            n++
        }
    }

    // ---- the train ----

    private fun drawTrain(c: Canvas, t: Float) {
        // The train rocks gently forwards and back, so it feels like it is working hard.
        val left = (sceneW - TRAIN_LENGTH) / 2f + sin(t * 1.1f) * 18f
        val travelled = t * TRACK_SPEED
        val wagonColors = intArrayOf(WAGON_YELLOW, WAGON_GREEN, WAGON_PURPLE)
        for (i in 0 until 3) {
            val x = left + i * (WAGON_LENGTH + GAP)
            val bob = bump(t, i * 0.7f)
            wagon(c, x, bob, wagonColors[i], i, t, travelled)
            // Coupler to the next car.
            fill.color = DARK
            c.drawRect(x + WAGON_LENGTH - 4f, 562f, x + WAGON_LENGTH + GAP + 4f, 570f, fill)
        }
        locomotive(c, left + 3 * (WAGON_LENGTH + GAP), bump(t, 2.3f), t, travelled)
    }

    private fun locomotive(c: Canvas, x: Float, bob: Float, t: Float, travelled: Float) {
        val y = bob
        // Smoke first, so the chimney sits on top of the newest puff.
        smoke(c, x + 255f, 398f + y, t)

        fill.color = DARK
        c.drawRect(x + 6f, 552f + y, x + 300f, 578f + y, fill) // frame
        rect.set(x + 290f, 546f + y, x + 312f, 578f + y)
        c.drawRoundRect(rect, 6f, 6f, fill) // buffer beam

        // Boiler with yellow bands.
        fill.color = LOCO_RED
        rect.set(x + 118f, 468f + y, x + 296f, 560f + y)
        c.drawRoundRect(rect, 40f, 40f, fill)
        fill.color = TRIM
        c.drawRect(x + 160f, 468f + y, x + 172f, 560f + y, fill)
        c.drawRect(x + 228f, 468f + y, x + 240f, 560f + y, fill)
        // Lamp at the front.
        fill.color = LAMP
        c.drawCircle(x + 292f, 492f + y, 11f, fill)

        // Dome and chimney.
        fill.color = TRIM
        c.drawCircle(x + 200f, 470f + y, 20f, fill)
        fill.color = DARK
        c.drawRect(x + 242f, 420f + y, x + 268f, 472f + y, fill)
        rect.set(x + 232f, 404f + y, x + 278f, 424f + y)
        c.drawRoundRect(rect, 6f, 6f, fill)

        // Cab, roof and window with the bear driver.
        fill.color = LOCO_BLUE
        rect.set(x + 18f, 420f + y, x + 134f, 560f + y)
        c.drawRoundRect(rect, 14f, 14f, fill)
        fill.color = ROOF
        rect.set(x + 6f, 402f + y, x + 146f, 426f + y)
        c.drawRoundRect(rect, 10f, 10f, fill)
        fill.color = WINDOW
        rect.set(x + 40f, 440f + y, x + 112f, 500f + y)
        c.drawRoundRect(rect, 12f, 12f, fill)
        bear(c, x + 76f, 478f + y + sin(t * 6f) * 1.5f)

        // Wheels: two big driving wheels joined by a rod, one small front wheel.
        wheel(c, x + 70f, 586f + y, 24f, travelled)
        wheel(c, x + 150f, 586f + y, 24f, travelled)
        wheel(c, x + 256f, 592f + y, 18f, travelled)
        val a = travelled / 24f
        val px = cos(a) * 11f
        val py = sin(a) * 11f
        stroke.color = ROD
        stroke.strokeWidth = 8f
        c.drawLine(x + 70f + px, 586f + y + py, x + 150f + px, 586f + y + py, stroke)
    }

    private fun wagon(c: Canvas, x: Float, y: Float, color: Int, kind: Int, t: Float, travelled: Float) {
        // Cargo first, so it sits inside the wagon.
        when (kind) {
            0 -> { // balls
                fill.color = BALL_RED; c.drawCircle(x + 50f, 486f + y, 26f, fill)
                fill.color = BALL_BLUE; c.drawCircle(x + 104f, 480f + y, 30f, fill)
                fill.color = BALL_WHITE; c.drawCircle(x + 154f, 488f + y, 24f, fill)
            }
            1 -> { // toy blocks
                val colors = intArrayOf(BALL_RED, BALL_BLUE, TRIM)
                for (i in 0 until 3) {
                    fill.color = colors[i]
                    val bx = x + 24f + i * 54f
                    rect.set(bx, 458f + y, bx + 46f, 504f + y)
                    c.drawRoundRect(rect, 6f, 6f, fill)
                }
                fill.color = colors[0]
                rect.set(x + 74f, 414f + y, x + 120f, 460f + y)
                c.drawRoundRect(rect, 6f, 6f, fill)
            }
            else -> { // balloons on strings, swaying
                val colors = intArrayOf(BALL_RED, TRIM, BALL_BLUE)
                for (i in 0 until 3) {
                    val sway = sin(t * 1.6f + i) * 10f
                    val baseX = x + 56f + i * 44f
                    val topY = 330f + (i % 2) * 40f + y
                    stroke.color = STRING
                    stroke.strokeWidth = 2.5f
                    c.drawLine(baseX, 496f + y, baseX + sway, topY + 34f, stroke)
                    fill.color = colors[i]
                    rect.set(baseX + sway - 26f, topY - 32f, baseX + sway + 26f, topY + 34f)
                    c.drawOval(rect, fill)
                }
            }
        }
        fill.color = color
        rect.set(x, 492f + y, x + WAGON_LENGTH, 566f + y)
        c.drawRoundRect(rect, 12f, 12f, fill)
        fill.color = DARK
        c.drawRect(x + 6f, 560f + y, x + WAGON_LENGTH - 6f, 576f + y, fill)
        wheel(c, x + 46f, 592f + y, 18f, travelled)
        wheel(c, x + WAGON_LENGTH - 46f, 592f + y, 18f, travelled)
    }

    private fun wheel(c: Canvas, cx: Float, cy: Float, r: Float, travelled: Float) {
        fill.color = DARK
        c.drawCircle(cx, cy, r, fill)
        fill.color = WHEEL
        c.drawCircle(cx, cy, r - 5f, fill)
        stroke.color = DARK
        stroke.strokeWidth = 3.5f
        val a = travelled / r
        for (i in 0 until 3) {
            val s = a + i * (Math.PI.toFloat() / 3f)
            val dx = cos(s) * (r - 6f)
            val dy = sin(s) * (r - 6f)
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, stroke)
        }
        fill.color = TRIM
        c.drawCircle(cx, cy, r * 0.25f, fill)
    }

    private fun bear(c: Canvas, cx: Float, cy: Float) {
        fill.color = BEAR
        c.drawCircle(cx - 13f, cy - 13f, 8f, fill)
        c.drawCircle(cx + 13f, cy - 13f, 8f, fill)
        c.drawCircle(cx, cy, 19f, fill)
        fill.color = BEAR_LIGHT
        c.drawCircle(cx + 2f, cy + 6f, 8f, fill)
        fill.color = DARK
        c.drawCircle(cx - 6f, cy - 4f, 2.6f, fill)
        c.drawCircle(cx + 8f, cy - 4f, 2.6f, fill)
        c.drawCircle(cx + 3f, cy + 3f, 2.8f, fill)
    }

    /** Puffs leave the chimney every [PUFF_EVERY] s, rise, grow and fade as they drift back. */
    private fun smoke(c: Canvas, x: Float, y: Float, t: Float) {
        val newest = (t / PUFF_EVERY).toInt()
        for (k in 0 until PUFFS) {
            val age = t - (newest - k) * PUFF_EVERY
            if (age < 0f || age > PUFF_LIFE) continue
            val p = age / PUFF_LIFE
            fill.color = SMOKE
            fill.alpha = ((1f - p) * 220f).toInt()
            val wobble = sin((newest - k) * 1.7f) * 12f
            c.drawCircle(x - age * 110f + wobble, y - age * 70f, 12f + p * 34f, fill)
        }
        fill.alpha = 255
    }

    private fun cloud(c: Canvas, x: Float, y: Float, size: Float) {
        c.drawCircle(x, y, 34f * size, fill)
        c.drawCircle(x + 40f * size, y - 16f * size, 42f * size, fill)
        c.drawCircle(x + 86f * size, y, 32f * size, fill)
        rect.set(x, y - 4f * size, x + 86f * size, y + 32f * size)
        c.drawRoundRect(rect, 16f * size, 16f * size, fill)
    }

    private fun tree(c: Canvas, x: Float, ground: Float, size: Float) {
        fill.color = TRUNK
        c.drawRect(x - 7f * size, ground - 52f * size, x + 7f * size, ground, fill)
        fill.color = LEAVES
        c.drawCircle(x, ground - 74f * size, 34f * size, fill)
        c.drawCircle(x - 22f * size, ground - 56f * size, 22f * size, fill)
        c.drawCircle(x + 22f * size, ground - 56f * size, 22f * size, fill)
    }

    // ---- helpers ----

    /** One rounded hill, [period] wide, filled down to the bottom of the scene. */
    private fun hump(path: Path, period: Float, base: Float, height: Float) {
        path.reset()
        path.moveTo(0f, base)
        path.cubicTo(period * 0.25f, base - height, period * 0.75f, base - height, period, base)
        path.lineTo(period, SCENE_H)
        path.lineTo(0f, SCENE_H)
        path.close()
    }

    /** Draws [path] side by side across the screen, moved left by [scroll]. */
    private fun tile(c: Canvas, path: Path, period: Float, scroll: Float) {
        var x = -wrap(scroll, period)
        while (x < sceneW) {
            c.save()
            c.translate(x, 0f)
            c.drawPath(path, fill)
            c.restore()
            x += period
        }
    }

    /** A small up-and-down jolt, as if the wheels ran over rail joints. */
    private fun bump(t: Float, phase: Float): Float = sin(t * 9f + phase) * 1.6f

    private fun wrap(v: Float, m: Float): Float = ((v % m) + m) % m

    private companion object {
        const val SCENE_H = 720f
        const val HORIZON = 520f
        const val FRAME_MS = 33L
        const val TRACK_SPEED = 240f
        const val FAR_PERIOD = 640f
        const val NEAR_PERIOD = 420f
        const val TREE_PERIOD = 300f
        const val SLEEPER_GAP = 42f
        const val FLOWER_PERIOD = 130f
        const val WAGON_LENGTH = 200f
        const val GAP = 20f
        const val TRAIN_LENGTH = 3 * (WAGON_LENGTH + GAP) + 312f
        const val PUFF_EVERY = 0.35f
        const val PUFF_LIFE = 2.4f
        const val PUFFS = 8

        const val SKY_TOP = 0xFF6EC1F0.toInt()
        const val SKY_BOTTOM = 0xFFD4F1FF.toInt()
        const val SUN = 0xFFFFD54A.toInt()
        const val CLOUD = 0xF0FFFFFF.toInt()
        const val FAR_HILL = 0xFF9AD68F.toInt()
        const val NEAR_HILL = 0xFF66BF62.toInt()
        const val GRASS = 0xFF5DB548.toInt()
        const val GRAVEL = 0xFFB9926B.toInt()
        const val SLEEPER = 0xFF7A5236.toInt()
        const val RAIL = 0xFF5B5E6B.toInt()
        const val TRUNK = 0xFF8D5A3B.toInt()
        const val LEAVES = 0xFF3E9E4A.toInt()
        const val DARK = 0xFF2E3440.toInt()
        const val LOCO_RED = 0xFFE53935.toInt()
        const val LOCO_BLUE = 0xFF1E88E5.toInt()
        const val ROOF = 0xFF153D7A.toInt()
        const val WINDOW = 0xFFCDE9FF.toInt()
        const val TRIM = 0xFFFFC107.toInt()
        const val LAMP = 0xFFFFF59D.toInt()
        const val ROD = 0xFFB0BEC5.toInt()
        const val WHEEL = 0xFFE53935.toInt()
        const val SMOKE = 0xFFF5F5F5.toInt()
        const val BEAR = 0xFF8D6E63.toInt()
        const val BEAR_LIGHT = 0xFFD7B899.toInt()
        const val WAGON_YELLOW = 0xFFFDD835.toInt()
        const val WAGON_GREEN = 0xFF43A047.toInt()
        const val WAGON_PURPLE = 0xFF8E24AA.toInt()
        const val BALL_RED = 0xFFEF5350.toInt()
        const val BALL_BLUE = 0xFF42A5F5.toInt()
        const val BALL_WHITE = 0xFFFAFAFA.toInt()
        const val STRING = 0xFF5D4037.toInt()
    }
}
