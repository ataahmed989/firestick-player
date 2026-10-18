package app.fireplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.data.Prefs
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel

/** What opens from the Fire TV home screen: a link box and the same settings windows as the player. */
class HomeActivity : AppCompatActivity() {

    private lateinit var settingsPanels: SettingsPanels
    private lateinit var panel: SidePanel
    private lateinit var list: LinearLayout
    private var consumeBackUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        val prefs = Prefs(this)
        settingsPanels = SettingsPanels(this, prefs, SubtitlePresets(prefs))
        panel = SidePanel(this)
        list = findViewById(R.id.home_list)

        val first = addRow("Open a link", R.drawable.ic_link) { openLink() }
        addRow("Subtitle style", R.drawable.ic_sub_style) { from ->
            openPanel("Subtitle style", from) {
                settingsPanels.renderSubtitleStyle(this, onStyleChanged = {}, onAssChanged = {})
            }
        }
        addRow("Settings", R.drawable.ic_settings) { from ->
            openPanel("Settings", from) { settingsPanels.renderSettings(this) }
        }
        first.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && panel.isOpen) {
                consumeBackUp = true
                panel.close()
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && consumeBackUp) {
                consumeBackUp = false
                return true
            }
        }
        if (!panel.isOpen) return super.dispatchKeyEvent(event)
        val before = currentFocus
        val handled = super.dispatchKeyEvent(event)
        // Keep focus inside the window while it's open.
        if (panel.isOpen && !panel.root.hasFocus()) {
            if (before != null && before.isAttachedToWindow) before.requestFocus() else panel.focusRow(0)
        }
        return handled
    }

    private fun openPanel(title: String, from: View, render: SidePanel.() -> Unit) {
        panel.open(title, onClose = { from.requestFocus() }, render = render)
    }

    private fun openLink() {
        SidePanel.prompt(this, "Video link", "") { text ->
            val link = text.trim()
            if (link.isEmpty()) return@prompt
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), this, PlayerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun addRow(label: String, icon: Int, onClick: (View) -> Unit): View {
        val density = resources.displayMetrics.density
        val colors = ContextCompat.getColorStateList(this, R.color.sel_text_row)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_row)
            minimumHeight = (44 * density).toInt()
            val h = (14 * density).toInt()
            setPadding(h, 0, h, 0)
        }
        val iconView = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = colors
            isDuplicateParentStateEnabled = true
        }
        val size = (20 * density).toInt()
        row.addView(iconView, LinearLayout.LayoutParams(size, size).apply { marginEnd = (12 * density).toInt() })
        row.addView(TextView(this).apply {
            text = label
            typeface = ResourcesCompat.getFont(this@HomeActivity, R.font.figtree_600)
            setTextColor(colors)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isDuplicateParentStateEnabled = true
        })
        row.setOnClickListener { onClick(it) }
        list.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (8 * density).toInt() },
        )
        return row
    }
}
