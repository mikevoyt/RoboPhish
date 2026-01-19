package com.bayapps.android.robophish.ui

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import com.bayapps.android.robophish.R

class ShowPagerAdapter(
    private val rootView: View
) : PagerAdapter() {

    override fun instantiateItem(collection: ViewGroup, position: Int): Any {
        val view = when (position) {
            0 -> rootView.findViewById<View>(R.id.tracks)
            1 -> rootView.findViewById(R.id.setlist)
            2 -> rootView.findViewById(R.id.reviews)
            3 -> rootView.findViewById(R.id.tapernotes)
            else -> null
        } ?: error("Missing view for position $position")
        collection.addView(view)
        return view
    }

    override fun destroyItem(collection: ViewGroup, position: Int, view: Any) {
        collection.removeView(view as View)
    }

    override fun getCount(): Int = 4

    override fun isViewFromObject(view: View, `object`: Any): Boolean = view == `object`

    override fun getPageTitle(position: Int): CharSequence {
        return when (position) {
            0 -> "Tracks"
            1 -> "Setlist"
            2 -> "Reviews"
            3 -> "Taper Notes"
            else -> ""
        }
    }
}
