package com.roadconquest.app

import android.app.AlertDialog
import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View

/** Clearly separated, tappable choices instead of plain dialog list rows. */
internal fun showActionChoices(
    context: Context,
    title: String,
    options: List<Pair<String, () -> Unit>>,
    separateLast: Boolean = false
) {
    val dp = context.resources.displayMetrics.density
    fun px(value: Int) = (value * dp + 0.5f).toInt()
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(8), px(20), px(8))
    }
    val dialog = AlertDialog.Builder(context).setTitle(title)
        .setView(ScrollView(context).apply { addView(content) })
        .setNegativeButton("Cancel", null).create()
    options.forEachIndexed { index, (label, action) ->
        if (index > 0) {
            content.addView(View(context).apply {
                setBackgroundColor(if (separateLast && index == options.lastIndex)
                    0x66777777 else 0x33777777)
            }, LinearLayout.LayoutParams(-1, px(1)).apply {
                setMargins(0, px(6), 0, px(6))
            })
        }
        content.addView(Button(context).apply {
            text = label
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            minHeight = px(48)
            setOnClickListener { dialog.dismiss(); action() }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    dialog.show()
}
