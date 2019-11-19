package com.bayapps.android.robophish.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.viewpager.widget.PagerAdapter;

import com.bayapps.android.robophish.R;

/**
 * Created by mikevoyt on 8/11/16.
 */

public class ShowPagerAdapter extends PagerAdapter {

    private LayoutInflater mInflater;
    private View mRootView;

    public ShowPagerAdapter(LayoutInflater inflater, View rootView) {
        mInflater = inflater;
        mRootView = rootView;
    }


    private static boolean mAdded = false;
    @Override
    public Object instantiateItem(ViewGroup collection, int position) {

        View view = null;
        if (position == 0) view = mRootView.findViewById(R.id.tracks);
        if (position == 1) view = mRootView.findViewById(R.id.setlist);
        if (position == 2) view = mRootView.findViewById(R.id.reviews);
        if (position == 3) view = mRootView.findViewById(R.id.tapernotes);
        collection.addView(view);

        return view;
    }


    @Override
    public void destroyItem(ViewGroup collection, int position, Object view) {
        collection.removeView((View) view);
    }


    @Override
    public int getCount() {
        return 4;
    }

    @Override
    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    @Override
    public CharSequence getPageTitle(int position) {
        switch (position) {
            case 0: return "Tracks";
            case 1: return "Setlist";
            case 2: return "Reviews";
            case 3: return "Taper Notes";
        }
        return null;
    }

}
