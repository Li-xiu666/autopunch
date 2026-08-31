package com.autopunch.attendance

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autopunch.attendance.log.PunchLog

class AppPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_app_picker)

            val etSearch = findViewById<EditText>(R.id.etSearch)
            val list = findViewById<ListView>(R.id.listApps)

            val apps = runCatching { loadApps() }
                .getOrElse { e ->
                    PunchLog.append(this, "[Picker] 读取应用列表失败: ${e.message}")
                    emptyList()
                }

            if (apps.isEmpty()) {
                Toast.makeText(this, "未能读取到应用列表，请重试", Toast.LENGTH_SHORT).show()
            }

            val adapter = AppListAdapter(this, apps)
            list.adapter = adapter

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    runCatching { adapter.filter.filter(s?.toString().orEmpty()) }
                }
            })

            list.setOnItemClickListener { _, _, position, _ ->
                runCatching {
                    val entry = adapter.getItem(position) ?: return@setOnItemClickListener
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_PACKAGE, entry.packageName)
                            .putExtra(EXTRA_LABEL, entry.label)
                    )
                    finish()
                }
            }
        } catch (e: Exception) {
            PunchLog.append(this, "[Picker] 打开失败: ${e.message}")
            Toast.makeText(this, "应用列表打开失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val out = ArrayList<AppEntry>()
        for (app in pm.getInstalledApplications(0)) {
            runCatching {
                val label = pm.getApplicationLabel(app).toString()
                if (label.isNotBlank()) out.add(AppEntry(label, app.packageName))
            }
        }
        out.sortBy { it.label }
        return out
    }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_LABEL = "label"
    }
}

data class AppEntry(val label: String, val packageName: String) {
    override fun toString(): String = "$label  ($packageName)"
}

private class AppListAdapter(
    context: Context,
    val items: List<AppEntry>
) : ArrayAdapter<AppEntry>(context, R.layout.item_app_picker, R.id.itemLabel, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        runCatching {
            val entry = getItem(position) ?: return view
            view.findViewById<TextView>(R.id.itemLabel).text = entry.label
            view.findViewById<TextView>(R.id.itemPackage).text = entry.packageName
        }
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim().orEmpty()
            val all = items
            val results = FilterResults()
            results.values = if (q.isEmpty()) all
            else all.filter {
                it.label.contains(q, true) || it.packageName.contains(q, true)
            }
            results.count = (results.values as List<*>).size
            return results
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            val list = (results?.values as? List<AppEntry>) ?: emptyList()
            clear()
            addAll(list)
            notifyDataSetChanged()
        }
    }
}