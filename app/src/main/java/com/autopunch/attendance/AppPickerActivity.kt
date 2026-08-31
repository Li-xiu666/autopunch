package com.autopunch.attendance

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Filter
import android.widget.ListView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup

class AppPickerActivity : Activity() {

    private val entries = ArrayList<AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val etSearch = findViewById<EditText>(R.id.etSearch)
        val list = findViewById<ListView>(R.id.listApps)

        entries.addAll(loadApps())

        val adapter = AppListAdapter(this, entries)
        list.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter.filter(s?.toString().orEmpty())
            }
        })

        list.setOnItemClickListener { _, _, position, _ ->
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

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val out = ArrayList<AppEntry>()
        for (app in pm.getInstalledApplications(0)) {
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrNull() ?: continue
            out.add(AppEntry(label, app.packageName))
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
) : ArrayAdapter<AppEntry>(context, android.R.layout.simple_list_item_2, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val entry = getItem(position) ?: return view
        view.findViewById<TextView>(android.R.id.text1).text = entry.label
        view.findViewById<TextView>(android.R.id.text2).text = entry.packageName
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val q = constraint?.toString()?.trim().orEmpty()
            val all = items.toList()
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