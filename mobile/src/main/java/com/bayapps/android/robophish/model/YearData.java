package com.bayapps.android.robophish.model;

/**
 * Created by mikevoyt on 8/13/16.
 */

public class YearData {
    private String mYear;
    private String mShowCount;

    public YearData(String year, String showCount) {
        mYear = year;
        mShowCount = showCount;
    }

    public void setYear(String year) {
        mYear = year;
    }

    public void setShowCount(String showCount) {
        mShowCount = showCount;
    }

    public String getYear() {
        return mYear;
    }

    public String getShowCount() {
        return mShowCount;
    }

}
