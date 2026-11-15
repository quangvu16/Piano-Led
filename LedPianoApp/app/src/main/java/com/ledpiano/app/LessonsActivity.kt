package com.ledpiano.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ledpiano.app.databinding.ActivityLessonsBinding
import com.ledpiano.app.databinding.ItemLessonBinding
import com.ledpiano.app.databinding.ItemLessonHeaderBinding
import com.ledpiano.app.practice.Lessons
import com.ledpiano.app.practice.PracticeStats
import com.ledpiano.app.util.MidiIo
import com.ledpiano.app.util.padForSystemBars

/**
 * The lesson path: levels 1–5 from easy right-hand five-finger songs to
 * two-hand pieces, each with progress, then every other bundled song.
 */
class LessonsActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val title: String, val progress: String) : Row()
        data class Item(val uri: String, val title: String, val info: String, val badge: String, val done: Boolean) : Row()
    }

    private lateinit var binding: ActivityLessonsBinding
    private val app get() = application as LedPianoApp
    private val rows = ArrayList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLessonsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.padForSystemBars()
        binding.btnBack.setOnClickListener { finish() }
        binding.recyclerLessons.layoutManager = LinearLayoutManager(this)
        binding.recyclerLessons.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        buildRows()
        adapter.notifyDataSetChanged()
    }

    private fun buildRows() {
        rows.clear()
        val stats = app.stats
        val levels = Lessons.levels(this)
        val used = HashSet<String>()
        for (level in levels) {
            val done = level.lessons.count { stats.record(it.uri).completed }
            rows.add(
                Row.Header(
                    getString(R.string.lessons_level, level.number, level.title),
                    getString(R.string.lessons_progress, done, level.lessons.size),
                ),
            )
            for (l in level.lessons) {
                used.add(l.uri)
                val rec = stats.record(l.uri)
                val hands = getString(
                    when (l.hands) {
                        "R" -> R.string.lesson_hands_r
                        "L" -> R.string.lesson_hands_l
                        else -> R.string.lesson_hands_lr
                    },
                )
                val parts = arrayListOf(hands)
                if (l.subtitle.isNotBlank()) parts.add(l.subtitle)
                if (rec.bestAccuracy >= 0) parts.add(getString(R.string.lesson_best, rec.bestAccuracy))
                else if (rec.completed) parts.add(getString(R.string.lesson_done))
                rows.add(Row.Item(l.uri, l.title, parts.joinToString(" · "), if (rec.completed) "✓" else l.level.toString(), rec.completed))
            }
        }
        // Every other bundled sample, so nothing from the old list is lost.
        addSampleSection(getString(R.string.lessons_other), "samples", used, stats)
        // Famous songs bundled from the original app.
        addSampleSection(getString(R.string.lessons_vietnamese), "samples/vietnamese", used, stats)
        addSampleSection(getString(R.string.lessons_english), "samples/english", used, stats)
    }

    /** Appends one header + song rows for every `.mid` in [dir] not already listed. */
    private fun addSampleSection(title: String, dir: String, used: MutableSet<String>, stats: PracticeStats) {
        val uris = assets.list(dir)
            ?.filter { it.endsWith(".mid", ignoreCase = true) }
            ?.sorted()
            .orEmpty()
            .map { MidiIo.ASSET_SCHEME + dir + "/" + it }
            .filter { it !in used }
        if (uris.isEmpty()) return
        rows.add(Row.Header(title, ""))
        for (uri in uris) {
            used.add(uri)
            val rec = stats.record(uri)
            val name = MidiIo.cleanSongName(uri.substringAfterLast('/'))
            val info = if (rec.bestAccuracy >= 0) getString(R.string.lesson_best, rec.bestAccuracy)
            else if (rec.completed) getString(R.string.lesson_done) else ""
            rows.add(Row.Item(uri, name, info, if (rec.completed) "✓" else "♪", rec.completed))
        }
    }

    private fun open(item: Row.Item) {
        app.settings.addRecentSong(item.uri, item.title)
        startActivity(PlayerActivity.newIntent(this, item.uri, item.title))
    }

    private class Holder(val view: android.view.View) : RecyclerView.ViewHolder(view)

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) Holder(ItemLessonHeaderBinding.inflate(inf, parent, false).root)
            else Holder(ItemLessonBinding.inflate(inf, parent, false).root)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> {
                    val b = ItemLessonHeaderBinding.bind(holder.view)
                    b.txtLevel.text = row.title
                    b.txtLevelProgress.text = row.progress
                }
                is Row.Item -> {
                    val b = ItemLessonBinding.bind(holder.view)
                    b.txtLessonTitle.text = row.title
                    b.txtLessonInfo.text = row.info
                    b.txtBadge.text = row.badge
                    b.txtBadge.setBackgroundResource(if (row.done) R.drawable.bg_tile_ok else R.drawable.bg_tile_neutral)
                    b.root.setOnClickListener { open(row) }
                }
            }
        }
    }
}
