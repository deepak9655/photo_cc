package com.photocc.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.photocc.app.R
import com.photocc.app.core.Argb
import com.photocc.app.core.BlendMode
import com.photocc.app.core.GroupLayer
import com.photocc.app.core.Layer
import com.photocc.app.core.TextLayer
import com.photocc.app.core.layerBounds
import com.photocc.app.doc.Session
import com.photocc.app.platform.Thumbnails
import kotlin.math.abs

/**
 * Bottom sheets.
 *
 * Everything the user needs while editing lives at the bottom of the screen,
 * inside thumb reach: the layer stack, the tool options and the colour picker.
 * Nothing is a floating desktop-style palette.
 */
class LayerPanel(context: Context, private var session: Session) : LinearLayout(context) {

    var onAddRaster: (() -> Unit)? = null
    var onAddText: (() -> Unit)? = null
    var onAddGroup: (() -> Unit)? = null
    var onDuplicate: (() -> Unit)? = null
    var onMoveUp: (() -> Unit)? = null
    var onMoveDown: (() -> Unit)? = null
    var onMerge: (() -> Unit)? = null
    var onFlatten: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onRename: ((Layer) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val rows = Ui.column(context)
    private val scroller = ScrollView(context)
    private val opacityLabel = Ui.label(context, "", 12f, Ui.TEXT_DIM)
    private val blendButton = Ui.label(context, "", 13f)
    private val opacitySlider = android.widget.SeekBar(context).apply {
        max = 100
        progress = 100
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                opacityLabel.text = context.getString(R.string.opacity_value, value)
                if (bindingOpacity) return
                session.selected?.let { layer -> session.setOpacityLive(layer.id, value / 100f) }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                session.beginStructuralEdit("Opacity")
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                session.endStructuralEdit("Opacity")
            }
        })
    }
    private var bindingOpacity = false

    /** The panel survives document switches; only the session changes. */
    fun attach(session: Session) {
        this.session = session
        refresh()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.SURFACE)

        addView(buildHeader())
        addView(buildActions())
        addView(Ui.divider(context))

        scroller.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        addView(scroller)

        addView(Ui.divider(context))
        addView(buildFooter())
        refresh()
    }

    private fun buildHeader(): View = Ui.row(context).apply {
        setPadding(Ui.dp(context, 12f), Ui.dp(context, 8f), Ui.dp(context, 8f), 0)
        addView(Ui.label(context, context.getString(R.string.layers), 16f))
        addView(Ui.spacer(context))
        addView(Ui.iconButton(context, Glyph.Close, Ui.TEXT_DIM, 40) { onClose?.invoke() })
    }

    private fun buildActions(): View = Ui.row(context, Ui.dp(context, 4f)).apply {
        addView(Ui.iconButton(context, Glyph.Image) { onAddRaster?.invoke() })
        addView(Ui.iconButton(context, Glyph.Text) { onAddText?.invoke() })
        addView(Ui.iconButton(context, Glyph.Group) { onAddGroup?.invoke() })
        addView(Ui.iconButton(context, Glyph.Duplicate) { onDuplicate?.invoke() })
        addView(Ui.iconButton(context, Glyph.Up) { onMoveUp?.invoke() })
        addView(Ui.iconButton(context, Glyph.Down) { onMoveDown?.invoke() })
        addView(Ui.iconButton(context, Glyph.Merge) { onMerge?.invoke() })
        addView(Ui.iconButton(context, Glyph.Flatten) { onFlatten?.invoke() })
        addView(Ui.iconButton(context, Glyph.Trash, Ui.DANGER) { onDelete?.invoke() })
    }

    private fun buildFooter(): View = Ui.column(context).apply {
        setPadding(Ui.dp(context, 14f), Ui.dp(context, 6f), Ui.dp(context, 14f), Ui.dp(context, 10f))
        addView(
            Ui.row(context).apply {
                opacityLabel.text = context.getString(R.string.opacity_value, 100)
                addView(opacityLabel)
                addView(Ui.spacer(context))
                blendButton.setPadding(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), Ui.dp(context, 6f))
                blendButton.background = Ui.rounded(Ui.SURFACE_HIGH, Ui.dp(context, 14f).toFloat())
                blendButton.setOnClickListener { showBlendMenu(blendButton) }
                addView(blendButton)
            },
        )
        addView(opacitySlider)
        addView(
            Ui.row(context).apply {
                addView(Ui.textButton(context, context.getString(R.string.rename)) {
                    session.selected?.let { onRename?.invoke(it) }
                })
                addView(Ui.spacer(context))
                addView(
                    Ui.textButton(context, context.getString(R.string.select_next)) {
                        session.selectNext(-1)
                    },
                )
            },
        )
    }

    private fun showBlendMenu(anchor: View) {
        val layer = session.selected ?: return
        val menu = PopupMenu(context, anchor)
        val modes = BlendMode.entries
        for ((index, mode) in modes.withIndex()) menu.menu.add(0, index, index, mode.label)
        menu.setOnMenuItemClickListener { item ->
            val mode = modes[item.itemId]
            session.setBlend(layer.id, mode)
            refresh()
            true
        }
        menu.show()
    }

    /** Rebuilds the layer rows; called after every structural change. */
    fun refresh() {
        rows.removeAllViews()
        val ordered = ArrayList<Pair<Layer, Int>>()
        collect(session.doc.children, 0, ordered)
        for ((layer, depth) in ordered) rows.addView(buildRow(layer, depth))
        if (scroller.childCount == 0) scroller.addView(rows) else scroller.invalidate()

        val selected = session.selected
        bindingOpacity = true
        val percent = (((selected?.opacity ?: 1f) * 100f).toInt()).coerceIn(0, 100)
        opacitySlider.progress = percent
        opacityLabel.text = context.getString(R.string.opacity_value, percent)
        bindingOpacity = false
        blendButton.text = selected?.blend?.label ?: context.getString(R.string.blend_mode)
        opacitySlider.isEnabled = selected != null
        opacitySlider.alpha = if (selected != null) 1f else 0.4f
    }

    /** Top-of-stack first, groups expanded, which is how Photoshop shows a file. */
    private fun collect(list: List<Layer>, depth: Int, out: MutableList<Pair<Layer, Int>>) {
        for (layer in list.asReversed()) {
            out.add(layer to depth)
            if (layer is GroupLayer && layer.opened) collect(layer.children, depth + 1, out)
        }
    }

    private fun buildRow(layer: Layer, depth: Int): View {
        val selected = layer.id == session.selectedId
        val row = Ui.row(context, Ui.dp(context, 8f)).apply {
            setPadding(
                Ui.dp(context, 10f) + Ui.dp(context, 16f) * depth,
                Ui.dp(context, 6f),
                Ui.dp(context, 10f),
                Ui.dp(context, 6f),
            )
            background = if (selected) Ui.rounded(0x33FFB74D, Ui.dp(context, 12f).toFloat())
            else Ui.rounded(Color.TRANSPARENT, 0f)
            isClickable = true
            setOnClickListener { session.select(layer.id) }
            setOnLongClickListener { onRename?.invoke(layer); true }
        }

        val thumb = ImageView(context).apply {
            layoutParams = LayoutParams(Ui.dp(context, 44f), Ui.dp(context, 44f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF141519.toInt())
            val bitmap = Thumbnails.of(layer, 44)
            if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(GlyphDrawable(Glyph.Layers, Ui.TEXT_DIM))
        }
        row.addView(thumb)

        val texts = Ui.column(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            addView(Ui.label(context, layer.name, 14f))
            val detail = when (layer) {
                is TextLayer -> context.getString(R.string.layer_type_text) + " · " + layer.text.psdFontName
                is GroupLayer -> context.getString(R.string.layer_type_group) + " · " + layer.children.size
                else -> {
                    val bounds = layerBounds(layer)
                    val size = if (bounds != null) "${bounds.w}×${bounds.h}" else context.getString(R.string.layer_type_empty)
                    val mode = if (layer.blend == BlendMode.NORMAL) "" else " · ${layer.blend.label}"
                    "$size$mode"
                }
            }
            addView(Ui.label(context, detail, 11f, Ui.TEXT_DIM))
            layer.unsupported?.let { addView(Ui.label(context, it, 10f, Ui.ACCENT)) }
        }
        row.addView(texts)

        row.addView(
            Ui.iconButton(context, if (layer.visible) Glyph.Eye else Glyph.EyeOff, if (layer.visible) Ui.TEXT else Ui.TEXT_DIM, 38) {
                session.setVisible(layer.id, !layer.visible)
            },
        )
        return row
    }
}

/**
 * HSV colour picker: saturation/value field, hue rail, alpha rail, hex entry and
 * a short row of swatches. The field bitmap is cached per hue and shared between
 * instances, so a picker costs 64 KB, not a megabyte.
 */
class ColorPickerView(context: Context, initial: Int) : LinearLayout(context) {

    var onColor: ((Int) -> Unit)? = null

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f
    private var alpha = 1f
    private val field = FieldView(context)
    private val hueRail = HueView(context)
    private val alphaRail = AlphaView(context)
    private val preview = View(context)
    private val hex = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(Ui.TEXT)
        textSize = 14f
        setHintTextColor(Ui.TEXT_DIM)
        isSingleLine = true
    }
    private var binding = false

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(context, 16f), Ui.dp(context, 12f), Ui.dp(context, 16f), Ui.dp(context, 4f))
        setColor(initial)

        field.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 140f))
        addView(field)
        addView(hueRail)
        addView(alphaRail)
        addView(
            Ui.row(context).apply {
                preview.layoutParams = LayoutParams(Ui.dp(context, 40f), Ui.dp(context, 40f))
                preview.background = Ui.rounded(0xFFFFFFFF.toInt(), Ui.dp(context, 10f).toFloat())
                addView(preview)
                hex.layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                addView(hex)
                addView(
                    Ui.textButton(context, context.getString(R.string.swatch_reset)) {
                        setColor(Argb.pack(255, 255, 255, 255))
                    },
                )
            },
        )
        addView(swatches())

        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (binding) return
                val text = s?.toString()?.trim()?.removePrefix("#") ?: return
                if (text.length != 6) return
                val parsed = text.toLongOrNull(16) ?: return
                val color = Argb.pack((alpha * 255).toInt(), (parsed shr 16).toInt() and 0xFF, (parsed shr 8).toInt() and 0xFF, parsed.toInt() and 0xFF)
                setColorInternal(color, syncHex = false)
            }
        })
    }

    private fun swatches(): View = Ui.row(context, Ui.dp(context, 4f)).apply {
        val colors = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
            0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
        )
        for (color in colors) {
            val swatch = View(context)
            val size = Ui.dp(context, 30f)
            swatch.layoutParams = LayoutParams(size, size)
            swatch.background = Ui.rounded(color, Ui.dp(context, 8f).toFloat())
            swatch.setOnClickListener { setColor(color) }
            addView(swatch)
        }
    }

    fun setColor(color: Int) = setColorInternal(color, syncHex = true)

    private fun setColorInternal(color: Int, syncHex: Boolean) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color or 0xFF000000.toInt(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        alpha = Argb.a(color) / 255f
        binding = true
        if (syncHex) {
            hex.setText(String.format("%06X", color and 0xFFFFFF))
        }
        binding = false
        field.invalidate()
        hueRail.invalidate()
        alphaRail.invalidate()
        preview.background = Ui.rounded(currentColor(), Ui.dp(context, 10f).toFloat())
        onColor?.invoke(currentColor())
    }

    private fun currentColor(): Int = Argb.pack(
        (alpha * 255f).toInt().coerceIn(0, 255),
        (android.graphics.Color.red(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))),
        (android.graphics.Color.green(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))),
        (android.graphics.Color.blue(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))),
    )

    /** Saturation (x) and brightness (y) for the current hue. */
    private inner class FieldView(context: Context) : View(context) {
        private var cacheHue = -1f
        private var cache: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.WHITE
        }

        override fun onDraw(canvas: Canvas) {
            if (cache == null || abs(cacheHue - hue) > 0.5f) {
                cache?.recycle()
                cache = buildField()
                cacheHue = hue
            }
            val bitmap = cache ?: return
            canvas.drawBitmap(bitmap, null, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            val x = saturation * width
            val y = (1f - value) * height
            canvas.drawCircle(x, y, 9f, marker)
        }

        private fun buildField(): Bitmap {
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(size * size)
            val hsv = floatArrayOf(hue, 0f, 0f)
            for (y in 0 until size) {
                hsv[2] = 1f - y.toFloat() / (size - 1)
                for (x in 0 until size) {
                    hsv[1] = x.toFloat() / (size - 1)
                    pixels[y * size + x] = Color.HSVToColor(hsv)
                }
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            return bitmap
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                saturation = (event.x / width).coerceIn(0f, 1f)
                value = (1f - event.y / height).coerceIn(0f, 1f)
                setColorInternal(currentColor(), syncHex = true)
                return true
            }
            return true
        }
    }

    private inner class HueView(context: Context) : View(context) {
        private val paint = Paint()
        private val shader = LinearGradient(
            0f, 0f, 360f, 0f,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }

        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 28f))
        }

        override fun onDraw(canvas: Canvas) {
            paint.shader = shader
            canvas.drawRect(0f, height / 2f - 8f, width.toFloat(), height / 2f + 8f, paint)
            val x = hue / 360f * width
            canvas.drawRect(x - 3f, 0f, x + 3f, height.toFloat(), marker)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                hue = (event.x / width).coerceIn(0f, 1f) * 360f
                setColorInternal(currentColor(), syncHex = true)
                return true
            }
            return true
        }
    }

    private inner class AlphaView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }

        init {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 28f))
        }

        override fun onDraw(canvas: Canvas) {
            val opaque = currentColor() or (0xFF shl 24)
            val transparent = opaque and 0x00FFFFFF
            paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, transparent, opaque, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, height / 2f - 8f, width.toFloat(), height / 2f + 8f, paint)
            val x = alpha * width
            canvas.drawRect(x - 3f, 0f, x + 3f, height.toFloat(), marker)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                alpha = (event.x / width).coerceIn(0f, 1f)
                setColorInternal(currentColor(), syncHex = true)
                return true
            }
            return true
        }
    }
}

/**
 * Tool options strip: brush size/hardness/flow, fill tolerance and the current
 * colour. Only the controls that matter for the active tool are visible, which is
 * what keeps a 5-inch screen usable.
 */
class ToolOptions(context: Context) : LinearLayout(context) {

    var onSize: ((Float) -> Unit)? = null
    var onHardness: ((Float) -> Unit)? = null
    var onFlow: ((Float) -> Unit)? = null
    var onTolerance: ((Int) -> Unit)? = null
    var onPickColor: (() -> Unit)? = null

    private val sizeLabel = Ui.label(context, "", 11f, Ui.TEXT_DIM)
    private val sizeSlider = Ui.slider(context, 2, 200, 24) { value -> onSize?.invoke(value.toFloat()) }
    private val hardnessSlider = Ui.slider(context, 0, 100, 80) { value -> onHardness?.invoke(value / 100f) }
    private val flowSlider = Ui.slider(context, 5, 100, 100) { value -> onFlow?.invoke(value / 100f) }
    private val toleranceSlider = Ui.slider(context, 0, 100, 32) { value -> onTolerance?.invoke(value) }
    private val swatch = View(context)

    init {
        orientation = VERTICAL
        setPadding(Ui.dp(context, 12f), Ui.dp(context, 6f), Ui.dp(context, 12f), Ui.dp(context, 6f))
        setBackgroundColor(Ui.SURFACE)
        addView(
            Ui.row(context).apply {
                sizeLabel.text = context.getString(R.string.size_value, 24)
                addView(sizeLabel)
                addView(Ui.spacer(context))
                swatch.layoutParams = LayoutParams(Ui.dp(context, 32f), Ui.dp(context, 32f))
                swatch.background = Ui.roundedStroke(Color.WHITE, 0x66FFFFFF, 1f, Ui.dp(context, 8f).toFloat())
                swatch.setOnClickListener { onPickColor?.invoke() }
                addView(swatch)
            },
        )
        addView(sizeSlider)
        addView(hardnessSlider)
        addView(flowSlider)
        addView(toleranceSlider)
        sizeSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                sizeLabel.text = context.getString(R.string.size_value, progress + 2)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        })
    }

    fun bind(tool: EditorView.Tool, brushSize: Float, color: Int) {
        val painter = tool == EditorView.Tool.BRUSH || tool == EditorView.Tool.ERASER
        sizeSlider.visibility = if (painter) VISIBLE else GONE
        sizeLabel.visibility = if (painter) VISIBLE else GONE
        hardnessSlider.visibility = if (painter) VISIBLE else GONE
        flowSlider.visibility = if (painter) VISIBLE else GONE
        toleranceSlider.visibility = if (tool == EditorView.Tool.FILL) VISIBLE else GONE
        swatch.visibility = if (painter || tool == EditorView.Tool.FILL || tool == EditorView.Tool.TEXT) VISIBLE else GONE
        sizeSlider.progress = (brushSize - 2f).toInt().coerceIn(0, 198)
        sizeLabel.text = context.getString(R.string.size_value, brushSize.toInt())
        swatch.background = Ui.roundedStroke(color, 0x66FFFFFF, 1f, Ui.dp(context, 8f).toFloat())
    }

    fun currentColorSwatch(): Int = (swatch.background as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor ?: Color.WHITE
}

