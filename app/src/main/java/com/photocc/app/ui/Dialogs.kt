package com.photocc.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.photocc.app.R
import com.photocc.app.core.Argb
import com.photocc.app.core.FontMap
import com.photocc.app.core.TextData

/** What to write when exporting. */
enum class ExportFormat { Psd, Png, Jpeg }

/**
 * Every dialog the editor needs, built in code.
 *
 * They are deliberately plain `AlertDialog`s: no custom window chrome, so they
 * look native on every OEM skin and behave correctly with keyboards, gestures
 * and one-handed use.
 */
object Dialogs {

    private fun panel(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(context, 20f), Ui.dp(context, 12f), Ui.dp(context, 20f), Ui.dp(context, 4f))
    }

    /** A text field that looks like the rest of the app rather than like a system widget. */
    private fun field(context: Context, initial: String, numeric: Boolean = true): EditText =
        EditText(context).apply {
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT_FAINT)
            textSize = 16f
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 10f), Ui.dp(context, 12f), Ui.dp(context, 10f))
            background = Ui.roundedStroke(Ui.SURFACE_HIGH, Ui.OUTLINE, 1f, Ui.RADIUS_MD)
        }

    /** New document: presets, free size, and a live size/print estimate. */
    fun newDocument(context: Context, onOk: (width: Int, height: Int, background: Int) -> Unit): AlertDialog {
        val content = panel(context)
        var widthValue = 1080
        var heightValue = 1080
        var chosen = 0
        val backgrounds = intArrayOf(0, Argb.pack(255, 255, 255, 255), Argb.pack(255, 0, 0, 0))
        val backgroundNames = listOf(
            context.getString(R.string.background_transparent),
            context.getString(R.string.background_white),
            context.getString(R.string.background_black),
        )

        val width = field(context, "1080")
        val height = field(context, "1080")
        val summary = Ui.label(context, "", 11.5f, Ui.TEXT_DIM)
        fun refreshSummary() {
            widthValue = width.text.toString().toIntOrNull()?.coerceIn(1, 30000) ?: widthValue
            heightValue = height.text.toString().toIntOrNull()?.coerceIn(1, 30000) ?: heightValue
            val megapixels = widthValue.toLong() * heightValue.toLong() / 1_000_000.0
            val memory = widthValue.toLong() * heightValue.toLong() * 4L / (1024 * 1024)
            summary.text = context.getString(R.string.new_summary, megapixels, memory)
        }

        val sizeRow = Ui.row(context, Ui.dp(context, 8f)).apply {
            addView(width, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.label(context, "×", 16f, Ui.TEXT_DIM, medium = true), LinearLayout.LayoutParams(Ui.dp(context, 20f), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(height, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(Ui.sectionLabel(context, context.getString(R.string.new_size)))
        content.addView(sizeRow)
        content.addView(summary.apply { setPadding(0, Ui.dp(context, 6f), 0, Ui.dp(context, 4f)) })

        val presets = listOf(
            "1080 × 1080" to (1080 to 1080),
            "1920 × 1080" to (1920 to 1080),
            "1080 × 1920" to (1080 to 1920),
            "2048 × 2048" to (2048 to 2048),
            "A4 · 2480 × 3508" to (2480 to 3508),
        )
        content.addView(Ui.sectionLabel(context, context.getString(R.string.new_presets)))
        val presetFlow = Ui.row(context, Ui.dp(context, 6f))
        for ((label, size) in presets) {
            presetFlow.addView(Ui.chip(context, label, selected = size.first == widthValue && size.second == heightValue) {
                width.setText(size.first.toString())
                height.setText(size.second.toString())
                refreshSummary()
            })
        }
        content.addView(
            android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(presetFlow)
            },
        )

        content.addView(Ui.sectionLabel(context, context.getString(R.string.new_background)).apply { setPadding(0, Ui.dp(context, 10f), 0, 0) })
        val backgroundCaption = Ui.label(context, backgroundNames[0], 11f, Ui.TEXT_FAINT)
        val backgroundRow = Ui.row(context, Ui.dp(context, 8f))
        val swatches = ArrayList<View>()
        for ((index, color) in backgrounds.withIndex()) {
            val swatch = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, Ui.dp(context, 44f), 1f)
                background = Ui.roundedStroke(
                    color,
                    if (index == chosen) Ui.ACCENT else Ui.OUTLINE,
                    if (index == chosen) 3f else 1f,
                    Ui.RADIUS_MD,
                )
                setOnClickListener {
                    chosen = index
                    backgroundCaption.text = backgroundNames[index]
                    for ((i, other) in swatches.withIndex()) {
                        other.background = Ui.roundedStroke(
                            backgrounds[i],
                            if (i == chosen) Ui.ACCENT else Ui.OUTLINE,
                            if (i == chosen) 3f else 1f,
                            Ui.RADIUS_MD,
                        )
                    }
                }
            }
            swatches.add(swatch)
            backgroundRow.addView(swatch)
        }
        content.addView(backgroundRow)
        content.addView(backgroundCaption.apply { setPadding(0, Ui.dp(context, 4f), 0, 0) })

        width.addTextChangedListener(simpleWatcher { refreshSummary() })
        height.addTextChangedListener(simpleWatcher { refreshSummary() })
        refreshSummary()

        return AlertDialog.Builder(context)
            .setTitle(R.string.new_document)
            .setView(scroll(content))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val w = width.text.toString().toIntOrNull()?.coerceIn(1, 30000) ?: 1080
                val h = height.text.toString().toIntOrNull()?.coerceIn(1, 30000) ?: 1080
                onOk(w, h, backgrounds[chosen])
            }
            .create()
    }

    /** Minimal TextWatcher so callers only have to say what changed. */
    private fun simpleWatcher(onChange: () -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = onChange()
        }

    /** Export: format, JPEG quality and an output scale. */
    fun export(
        context: Context,
        suggestedName: String,
        onOk: (format: ExportFormat, quality: Int, scale: Float) -> Unit,
    ): AlertDialog {
        val content = panel(context)
        var format = ExportFormat.Psd
        var quality = 92
        var scale = 1f
        lateinit var qualityLabel: TextView

        val qualityRow = Ui.column(context).apply {
            addView(
                Ui.row(context).apply {
                    addView(Ui.sectionLabel(context, context.getString(R.string.quality)))
                    addView(Ui.spacer(context))
                    qualityLabel = Ui.label(context, context.getString(R.string.quality_value, quality), 12f, Ui.TEXT, mono = true)
                    addView(qualityLabel)
                },
            )
        }
        val qualitySlider = Ui.slider(context, 40, 100, quality) { value ->
            quality = value
            qualityLabel.text = context.getString(R.string.quality_value, value)
        }
        qualityRow.addView(qualitySlider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 36f)))

        val scaleChips = Ui.row(context, Ui.dp(context, 6f))
        val scaleOptions = listOf(100 to 1f, 50 to 0.5f, 25 to 0.25f)
        val scaleChipViews = ArrayList<TextView>()
        for ((percent, factor) in scaleOptions) {
            val chip = Ui.chip(context, "$percent%", selected = percent == 100) {
                scale = factor
                for ((i, other) in scaleChipViews.withIndex()) {
                    val active = scaleOptions[i].first == percent
                    other.setTextColor(if (active) Ui.ON_ACCENT else Ui.TEXT_DIM)
                    other.background = Ui.roundedStroke(
                        if (active) Ui.ACCENT else Ui.SURFACE_MAX,
                        Ui.OUTLINE,
                        1f,
                        Ui.RADIUS_PILL,
                    )
                }
            }
            scaleChipViews.add(chip)
            scaleChips.addView(chip)
        }

        val formats = listOf(
            ExportFormat.Psd to context.getString(R.string.format_psd),
            ExportFormat.Png to context.getString(R.string.format_png),
            ExportFormat.Jpeg to context.getString(R.string.format_jpeg),
        )
        val formatRow = Ui.row(context, Ui.dp(context, 6f))
        val formatButtons = ArrayList<TextView>()
        for ((_, label) in formats) formatButtons.add(Ui.chip(context, label) { })

        fun select(value: ExportFormat) {
            format = value
            for ((i, button) in formatButtons.withIndex()) {
                val active = formats[i].first == value
                button.setTextColor(if (active) Ui.ON_ACCENT else Ui.TEXT_DIM)
                button.background = Ui.roundedStroke(
                    if (active) Ui.ACCENT else Ui.SURFACE_MAX,
                    Ui.OUTLINE,
                    1f,
                    Ui.RADIUS_PILL,
                )
            }
            qualityRow.visibility = if (value == ExportFormat.Jpeg) View.VISIBLE else View.GONE
            scaleChips.visibility = if (value == ExportFormat.Psd) View.GONE else View.VISIBLE
        }
        for ((index, entry) in formats.withIndex()) {
            formatButtons[index].setOnClickListener { select(entry.first) }
        }
        select(ExportFormat.Psd)

        content.addView(Ui.sectionLabel(context, context.getString(R.string.export_format)))
        content.addView(formatRow)
        content.addView(Ui.sectionLabel(context, context.getString(R.string.export_scale)).apply { setPadding(0, Ui.dp(context, 12f), 0, 0) })
        content.addView(scaleChips)
        content.addView(qualityRow.apply { setPadding(0, Ui.dp(context, 4f), 0, 0) })
        content.addView(Ui.label(context, context.getString(R.string.export_hint, suggestedName), 11f, Ui.TEXT_FAINT))

        return AlertDialog.Builder(context)
            .setTitle(R.string.export)
            .setView(scroll(content))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.export) { _, _ -> onOk(format, quality, scale) }
            .create()
    }

    /** Text layer editor: string, size, font, alignment, weight, colour. */
    fun textLayer(
        context: Context,
        titleRes: Int,
        initial: TextData,
        isNew: Boolean,
        onPreview: (TextData) -> Unit,
        onPickColor: ((Int) -> Unit) -> Unit,
        onOk: (TextData) -> Unit,
    ): AlertDialog {
        val content = panel(context)
        val working = initial.copyOf()

        val text = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(initial.text)
            setTextColor(Ui.TEXT)
            gravity = Gravity.TOP or Gravity.START
            minLines = 2
            maxLines = 6
            setBackground(Ui.rounded(Ui.SURFACE_HIGH, Ui.dp(context, 12f).toFloat()))
            setPadding(Ui.dp(context, 10f), Ui.dp(context, 8f), Ui.dp(context, 10f), Ui.dp(context, 8f))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    working.text = s?.toString() ?: ""
                    onPreview(working)
                }
            })
        }
        content.addView(text)

        val sizeLabel = Ui.label(context, context.getString(R.string.size_value, initial.size.toInt()), 12f, Ui.TEXT_DIM)
        content.addView(sizeLabel)
        content.addView(
            Ui.slider(context, 6, 300, initial.size.toInt().coerceIn(6, 300)) { value ->
                working.size = value.toFloat()
                sizeLabel.text = context.getString(R.string.size_value, value)
                onPreview(working)
            },
        )

        val fontRow = Ui.row(context)
        val fonts = FontMap.picker
        for ((index, font) in fonts.withIndex()) {
            val button = Ui.label(context, font.label).apply {
                textSize = 12f
                setPadding(Ui.dp(context, 10f), Ui.dp(context, 8f), Ui.dp(context, 10f), Ui.dp(context, 8f))
                background = Ui.roundedStroke(
                    if (index == 0) 0x33FFB74D else Ui.SURFACE_HIGH,
                    if (index == 0) Ui.ACCENT else 0x00000000,
                    if (index == 0) 2f else 1f,
                    Ui.dp(context, 14f).toFloat(),
                )
                setOnClickListener {
                    working.fontFamily = font.family
                    working.psdFontName = font.psdName
                    // Bold/italic variants are re-derived for the new family.
                    working.psdFontName = FontMap.deviceToPsdName(font.family, if (working.fauxBold) 700 else 400, working.fauxItalic)
                    for ((i, other) in fontRow.children().withIndex()) {
                        other.background = Ui.roundedStroke(
                            if (i == index) 0x33FFB74D else Ui.SURFACE_HIGH,
                            if (i == index) Ui.ACCENT else 0x00000000,
                            if (i == index) 2f else 1f,
                            Ui.dp(context, 14f).toFloat(),
                        )
                    }
                    onPreview(working)
                }
            }
            fontRow.addView(button)
        }
        content.addView(horizontalScroll(fontRow))

        val styleRow = Ui.row(context)
        val alignButtons = ArrayList<TextView>()
        val aligns = TextData.Align.entries
        for ((index, align) in aligns.withIndex()) {
            val button = Ui.label(context, align.label).apply {
                textSize = 12f
                setPadding(Ui.dp(context, 12f), Ui.dp(context, 8f), Ui.dp(context, 12f), Ui.dp(context, 8f))
                background = Ui.rounded(
                    if (align == initial.align) 0x33FFB74D else Ui.SURFACE_HIGH,
                    Ui.dp(context, 14f).toFloat(),
                )
                setOnClickListener {
                    working.align = align
                    for ((i, other) in alignButtons.withIndex()) {
                        other.background = Ui.rounded(
                            if (i == index) 0x33FFB74D else Ui.SURFACE_HIGH,
                            Ui.dp(context, 14f).toFloat(),
                        )
                    }
                    onPreview(working)
                }
            }
            alignButtons.add(button)
            styleRow.addView(button)
        }
        styleRow.addView(
            toggle(context, context.getString(R.string.bold), initial.fauxBold) { on ->
                working.fauxBold = on
                working.psdFontName = FontMap.deviceToPsdName(working.fontFamily, if (on) 700 else 400, working.fauxItalic)
                onPreview(working)
            },
        )
        styleRow.addView(
            toggle(context, context.getString(R.string.italic), initial.fauxItalic) { on ->
                working.fauxItalic = on
                working.psdFontName = FontMap.deviceToPsdName(working.fontFamily, if (working.fauxBold) 700 else 400, on)
                onPreview(working)
            },
        )
        content.addView(horizontalScroll(styleRow))

        val colorRow = Ui.row(context)
        val swatch = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 40f), Ui.dp(context, 40f))
            background = Ui.roundedStroke(initial.color, 0x66FFFFFF, 1f, Ui.dp(context, 10f).toFloat())
        }
        colorRow.addView(swatch)
        colorRow.addView(Ui.label(context, context.getString(R.string.text_colour), 12f, Ui.TEXT_DIM))
        colorRow.addView(Ui.spacer(context))
        swatch.setOnClickListener {
            onPickColor { color ->
                working.color = color
                swatch.background = Ui.roundedStroke(color, 0x66FFFFFF, 1f, Ui.dp(context, 10f).toFloat())
                onPreview(working)
            }
        }
        content.addView(colorRow)

        val builder = AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(scroll(content))
            .setPositiveButton(R.string.apply) { _, _ -> onOk(working) }
        if (!isNew) builder.setNegativeButton(android.R.string.cancel, null) else builder.setNegativeButton(android.R.string.cancel, null)
        return builder.create()
    }

    /** Colour picker dialog wrapping [ColorPickerView]. */
    fun color(context: Context, initial: Int, onOk: (Int) -> Unit): AlertDialog {
        val picker = ColorPickerView(context, initial)
        var value = initial
        picker.onColor = { value = it }
        return AlertDialog.Builder(context)
            .setTitle(R.string.colour)
            .setView(scroll(picker))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ -> onOk(value) }
            .create()
    }

    fun rename(context: Context, initial: String, onOk: (String) -> Unit): AlertDialog {
        val input = EditText(context).apply {
            setText(initial)
            setTextColor(Ui.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val content = panel(context)
        content.addView(input)
        return AlertDialog.Builder(context)
            .setTitle(R.string.rename)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.apply) { _, _ -> onOk(input.text.toString()) }
            .create()
    }

    fun info(context: Context, title: String, message: String): AlertDialog =
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create()

    fun confirm(
        context: Context,
        title: Int,
        message: String,
        confirmLabel: Int,
        onConfirm: () -> Unit,
    ): AlertDialog =
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
            .create()

    private fun toggle(context: Context, label: String, initiallyOn: Boolean, onChange: (Boolean) -> Unit): TextView {
        var on = initiallyOn
        return Ui.label(context, label).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Ui.dp(context, 12f), Ui.dp(context, 8f), Ui.dp(context, 12f), Ui.dp(context, 8f))
            background = Ui.rounded(if (on) 0x33FFB74D else Ui.SURFACE_HIGH, Ui.dp(context, 14f).toFloat())
            setOnClickListener {
                on = !on
                background = Ui.rounded(if (on) 0x33FFB74D else Ui.SURFACE_HIGH, Ui.dp(context, 14f).toFloat())
                onChange(on)
            }
        }
    }

    private fun LinearLayout.children(): List<TextView> =
        (0 until childCount).mapNotNull { getChildAt(it) as? TextView }

    private fun scroll(view: View): View = ScrollView(view.context).apply {
        addView(view)
        setPadding(0, 0, 0, 0)
        isFillViewport = false
    }

    private fun horizontalScroll(view: View): View = android.widget.HorizontalScrollView(view.context).apply {
        addView(view)
        isHorizontalScrollBarEnabled = false
    }
}
