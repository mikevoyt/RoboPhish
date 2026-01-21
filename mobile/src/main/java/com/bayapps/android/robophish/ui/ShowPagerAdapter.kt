package com.bayapps.android.robophish.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.bayapps.android.robophish.R

class ShowPagerAdapter(
    private val onTracksViewCreated: (View) -> Unit,
    private val onSetlistViewCreated: (View) -> Unit,
    private val onReviewsViewCreated: (View) -> Unit,
    private val onTaperNotesViewCreated: (View) -> Unit
) : RecyclerView.Adapter<ShowPagerAdapter.PageViewHolder>() {

    private val pages = listOf(
        Page("Tracks", R.layout.fragment_show_tracks),
        Page("Setlist", R.layout.fragment_show_setlist),
        Page("Reviews", R.layout.fragment_show_reviews),
        Page("Taper Notes", R.layout.fragment_show_tapernotes)
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        when (position) {
            0 -> onTracksViewCreated(holder.itemView)
            1 -> onSetlistViewCreated(holder.itemView)
            2 -> onReviewsViewCreated(holder.itemView)
            3 -> onTaperNotesViewCreated(holder.itemView)
        }
    }

    override fun getItemViewType(position: Int): Int = pages[position].layoutId

    override fun getItemCount(): Int = pages.size

    fun getPageTitle(position: Int): CharSequence = pages[position].title

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    data class Page(val title: String, @LayoutRes val layoutId: Int)
}
