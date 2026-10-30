package app.sona.music

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

private val lyricAccent = 0xffd0bcff.toInt()
private fun Context.lyricDp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
private fun Context.lyricSurface(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
    setColor(color); cornerRadius = lyricDp(24).toFloat()
    if (stroke != Color.TRANSPARENT) setStroke(lyricDp(1).coerceAtLeast(1), stroke)
}

/** Small visible icon, with a full 48 dp touch target and an accessible name. */
internal fun lyricIcon(context: Context, resource: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
    setImageResource(resource)
    imageTintList = ColorStateList.valueOf(Color.WHITE)
    setPadding(context.lyricDp(12), context.lyricDp(12), context.lyricDp(12), context.lyricDp(12))
    background = RippleDrawable(ColorStateList.valueOf(0x33ffffff),
        InsetDrawable(context.lyricSurface(0xee35343a.toInt()), context.lyricDp(6)),
        context.lyricSurface(Color.WHITE))
    contentDescription = label; tooltipText = label
    setOnClickListener { action() }
}

/** Native controls stay inside the same overlay: no Activity, dialog window or keyboard. */
internal class FloatingLyricsControls(context: Context, done: () -> Unit,
    private val change: (FloatingLyricsConfig) -> Unit) : LinearLayout(context) {
    private var options = FloatingLyricsConfig()
    private val bindings = mutableListOf<() -> Unit>()
    private val colorTitle = label("歌词颜色")
    private val presets = lyricColorPresets
    private fun dp(value: Int) = context.lyricDp(value)
    private fun label(text: String) = TextView(context).apply {
        this.text = text; textSize = 14f; setTextColor(0xffe6e1e5.toInt()); gravity = Gravity.CENTER_VERTICAL
    }
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    init {
        orientation = VERTICAL
        background = context.lyricSurface(0xff29282d.toInt(), 0xff5a575f.toInt())
        clipToOutline = true
        setPadding(dp(16), dp(8), dp(16), dp(12))
        val header = row()
        header.addView(label("悬浮歌词").apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }, LayoutParams(0, dp(48), 1f))
        header.addView(lyricIcon(context, R.drawable.ic_floating_done, "完成设置", done), LayoutParams(dp(48), dp(48)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body, android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val lines = row()
        lines.addView(label("显示行数"), LayoutParams(0, dp(48), 1f))
        for ((count, icon) in listOf(1 to R.drawable.ic_floating_one_line, 2 to R.drawable.ic_floating_two_lines)) {
            val button = lyricIcon(context, icon, if (count == 1) "一条歌词" else "两条歌词") {
                change(options.copy(fixedLines = count))
            }
            lines.addView(button, LayoutParams(dp(48), dp(48)))
            bindings += {
                button.isSelected = options.fixedLines == count
                button.imageTintList = ColorStateList.valueOf(if (button.isSelected) lyricAccent else 0xff8d8792.toInt())
            }
        }
        body.addView(lines)
        body.addView(slider("字号", 12, 32, " sp", { it.fontSize.roundToInt() }) { config, value -> config.copy(fontSize = value.toFloat()) })
        body.addView(slider("不透明度", 25, 100, "%", { (it.opacity * 100).roundToInt() }) { config, value -> config.copy(opacity = value / 100f) })
        val colorHeader = row()
        colorHeader.addView(colorTitle, LayoutParams(0, dp(48), 1f))
        val selectedColor = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        colorHeader.addView(selectedColor, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        bindings += { selectedColor.background = context.lyricSurface(options.textColor, 0xff8d8792.toInt()) }
        body.addView(colorHeader)
        presets.chunked(4).forEach { colors ->
            val palette = row()
            colors.forEach { (name, color) ->
                val swatch = ImageButton(context).apply {
                    contentDescription = name; tooltipText = name
                    setOnClickListener { change(options.copy(textColor = color)) }
                }
                palette.addView(swatch, LayoutParams(0, dp(48), 1f))
                bindings += {
                    swatch.isSelected = options.textColor == color
                    swatch.contentDescription = "$name${if (swatch.isSelected) "，已选择" else ""}"
                    swatch.background = RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                        InsetDrawable(context.lyricSurface(color, if (swatch.isSelected) lyricAccent else 0xff77717d.toInt()), dp(10)),
                        context.lyricSurface(Color.WHITE))
                }
            }
            body.addView(palette)
        }

    }

    private fun slider(title: String, lower: Int, upper: Int, suffix: String,
        read: (FloatingLyricsConfig) -> Int, write: (FloatingLyricsConfig, Int) -> FloatingLyricsConfig): View {
        val column = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        val heading = row()
        heading.addView(label(title), LayoutParams(0, dp(24), 1f))
        val value = label("").apply { setTextColor(lyricAccent) }
        heading.addView(value)
        column.addView(heading)
        val seek = SeekBar(context).apply {
            max = upper - lower
            progressTintList = ColorStateList.valueOf(lyricAccent)
            thumbTintList = progressTintList
            progressBackgroundTintList = ColorStateList.valueOf(0xff5d5765.toInt())
            contentDescription = title
            setPadding(dp(8), 0, dp(8), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) change(write(options, progress + lower))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        column.addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        bindings += {
            val current = read(options).coerceIn(lower, upper)
            if (seek.progress != current - lower) seek.progress = current - lower
            value.text = "$current$suffix"
        }
        return column
    }

    fun configure(config: FloatingLyricsConfig, title: String = "歌词颜色") {
        options = config; colorTitle.text = title; bindings.forEach { it() }
    }
}
