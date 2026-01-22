package com.bayapps.android.robophish.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.AnimationDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.media3.common.MediaItem
import com.bayapps.android.robophish.R

object MediaItemViewHolder {
    const val STATE_INVALID = -1
    const val STATE_NONE = 0
    const val STATE_PLAYABLE = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3

    private var colorStatePlaying: ColorStateList? = null
    private var colorStateNotPlaying: ColorStateList? = null

    @JvmStatic
    fun setupView(
        activity: Activity,
        convertView: View?,
        parent: ViewGroup,
        item: MediaItem,
        state: Int
    ): View {
        if (colorStateNotPlaying == null || colorStatePlaying == null) {
            initializeColorStateLists(activity)
        }

        var view = convertView
        val holder: Holder
        var cachedState = STATE_INVALID

        if (view == null) {
            view = LayoutInflater.from(activity).inflate(R.layout.media_list_item, parent, false)
            holder = Holder(
                imageView = view.findViewById(R.id.play_eq),
                titleView = view.findViewById(R.id.title),
                descriptionView = view.findViewById(R.id.description),
                locationView = view.findViewById(R.id.location)
            )
            view.tag = holder
        } else {
            holder = view.tag as Holder
            cachedState = view.getTag(R.id.tag_mediaitem_state_cache) as? Int ?: STATE_INVALID
        }

        holder.titleView.text = item.mediaMetadata.title
        holder.descriptionView.text = item.mediaMetadata.subtitle
        val location = item.mediaMetadata.description
        val showLocation = item.mediaMetadata.isBrowsable == true && !location.isNullOrBlank()
        holder.locationView.visibility = if (showLocation) View.VISIBLE else View.GONE
        holder.locationView.text = if (showLocation) location else ""

        val resultView = view ?: throw IllegalStateException("Missing view")
        if (cachedState != state) {
            when (state) {
                STATE_PLAYABLE -> {
                    val playDrawable = ContextCompat.getDrawable(activity, R.drawable.ic_play_arrow_black_36dp)
                    DrawableCompat.setTintList(playDrawable!!, colorStateNotPlaying)
                    holder.imageView.setImageDrawable(playDrawable)
                    holder.imageView.visibility = View.VISIBLE
                }
                STATE_PLAYING -> {
                    val animation = ContextCompat.getDrawable(
                        activity,
                        R.drawable.ic_equalizer_white_36dp
                    ) as AnimationDrawable
                    DrawableCompat.setTintList(animation, colorStatePlaying)
                    holder.imageView.setImageDrawable(animation)
                    holder.imageView.visibility = View.VISIBLE
                    animation.start()
                }
                STATE_PAUSED -> {
                    val pauseDrawable = ContextCompat.getDrawable(
                        activity,
                        R.drawable.ic_equalizer1_white_36dp
                    )
                    DrawableCompat.setTintList(pauseDrawable!!, colorStatePlaying)
                    holder.imageView.setImageDrawable(pauseDrawable)
                    holder.imageView.visibility = View.VISIBLE
                }
                else -> holder.imageView.visibility = View.GONE
            }
            resultView.setTag(R.id.tag_mediaitem_state_cache, state)
        }

        return resultView
    }

    private fun initializeColorStateLists(context: Context) {
        colorStateNotPlaying = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.media_item_icon_not_playing)
        )
        colorStatePlaying = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.media_item_icon_playing)
        )
    }

    private data class Holder(
        val imageView: ImageView,
        val titleView: TextView,
        val descriptionView: TextView,
        val locationView: TextView
    )
}
