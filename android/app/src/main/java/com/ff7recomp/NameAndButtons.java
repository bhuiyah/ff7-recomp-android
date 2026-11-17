package com.ff7recomp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * A line with a name on the left and its buttons on the right. When the buttons would leave the
 * name less than {@code minNameWidth}, the buttons go on a line of their own under the name
 * (indented by {@code indent}) and the name keeps the whole width. Before this, a mod row on a
 * 1240x1080 screen (Retroid Pocket Classic) gave the name a few pixels, so it wrapped one letter
 * per line and seemed to be gone (GitHub #58).
 */
public class NameAndButtons extends ViewGroup {
    private final View name;
    private final ViewGroup buttons;
    private final int minNameWidth, indent;
    private boolean under;

    public NameAndButtons(Context c, View name, ViewGroup buttons, int minNameWidth, int indent) {
        super(c);
        this.name = name;
        this.buttons = buttons;
        this.minNameWidth = minNameWidth;
        this.indent = indent;
        addView(name);
        addView(buttons);
    }

    /** The buttons are on their own line (for tests). */
    public boolean buttonsUnder() { return under; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) w = Integer.MAX_VALUE / 2;
        int unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        buttons.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w), MeasureSpec.AT_MOST), unspec);
        int bw = buttons.getMeasuredWidth(), bh = buttons.getMeasuredHeight();
        under = w - bw < minNameWidth;
        int h;
        if (!under) {
            name.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w - bw), MeasureSpec.EXACTLY), unspec);
            h = Math.max(name.getMeasuredHeight(), bh);
        } else {
            name.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w), MeasureSpec.EXACTLY), unspec);
            buttons.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w - indent), MeasureSpec.AT_MOST), unspec);
            h = name.getMeasuredHeight() + buttons.getMeasuredHeight();
        }
        h += getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(Math.min(w, MeasureSpec.getSize(wSpec)) + getPaddingLeft() + getPaddingRight(), wSpec),
                resolveSize(h, hSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = getPaddingLeft(), y = getPaddingTop();
        int nw = name.getMeasuredWidth(), nh = name.getMeasuredHeight();
        int bw = buttons.getMeasuredWidth(), bh = buttons.getMeasuredHeight();
        if (!under) {
            int h = Math.max(nh, bh);
            name.layout(x, y + (h - nh) / 2, x + nw, y + (h - nh) / 2 + nh);
            buttons.layout(x + nw, y + (h - bh) / 2, x + nw + bw, y + (h - bh) / 2 + bh);
        } else {
            name.layout(x, y, x + nw, y + nh);
            buttons.layout(x + indent, y + nh, x + indent + bw, y + nh + bh);
        }
    }
}
