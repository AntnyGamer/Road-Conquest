package com.roadconquest.app.onboarding

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.roadconquest.app.R
import com.roadconquest.app.util.Prefs

object ExitConfirmation {
    /** Leaving the map does not change the user's recording mode or stop the service. */
    fun show(activity: Activity, tracking: Boolean, onLeave: () -> Unit): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_exit_confirmation)
        dialog.setTitle(R.string.exit_title)
        updateTracking(dialog, tracking)
        dialog.findViewById<Button>(R.id.exitStayButton).setOnClickListener { dialog.dismiss() }
        var leaving = false
        dialog.findViewById<Button>(R.id.exitLeaveButton).setOnClickListener {
            if (leaving) return@setOnClickListener
            leaving = true
            if (dialog.findViewById<CheckBox>(R.id.exitDontAsk).isChecked) {
                Prefs.setExitConfirmationEnabled(activity, false)
            }
            dialog.dismiss()
            onLeave()
        }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val width = minOf((440 * activity.resources.displayMetrics.density).toInt(),
            activity.resources.displayMetrics.widthPixels - (32 * activity.resources.displayMetrics.density).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    fun updateTracking(dialog: Dialog, tracking: Boolean) {
        dialog.findViewById<TextView>(R.id.exitTrackingStatus)
            .setText(if (tracking) R.string.exit_tracking_active else R.string.exit_tracking_off)
        dialog.findViewById<TextView>(R.id.exitBody)
            .setText(if (tracking) R.string.exit_body_active else R.string.exit_body_off)
    }
}
