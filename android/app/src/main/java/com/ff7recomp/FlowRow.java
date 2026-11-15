package com.ff7recomp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Buttons side by side that go on to the next line when the width runs out, instead of being
 * squeezed into a column of letters or pushed off the screen (a 640x480 screen, GitHub #47).
 * Children keep their own size and margins (LinearLayout.LayoutParams / MarginLayoutParams);
 * each line is centred vertically.
 */
public class FlowRow extends ViewGroup {
    public FlowRow(Context c) { super(c); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, lineH = 0, h = 0, widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            MarginLayoutParams lp = margins(c);
            // a child is never wider than the row
            c.measure(getChildMeasureSpec(MeasureSpec.makeMeasureSpec(Math.max(0, maxW), MeasureSpec.AT_MOST),
                            lp.leftMargin + lp.rightMargin, lp.width),
                    getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), 0, lp.height));
            int cw = c.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int ch = c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x > 0 && x + cw > maxW) { h += lineH; x = 0; lineH = 0; }
            x += cw;
            lineH = Math.max(lineH, ch);
            widest = Math.max(widest, x);
        }
        h += lineH;
        int w = MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wSpec)
                : Math.min(widest, maxW) + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(w, resolveSize(h + getPaddingTop() + getPaddingBottom(), hSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int i = 0, y = getPaddingTop();
        while (i < getChildCount()) {
            // one line: which children fit, and its height
            int j = i, x = 0, lineH = 0;
            for (; j < getChildCount(); j++) {
                View c = getChildAt(j);
                if (c.getVisibility() == GONE) continue;
                MarginLayoutParams lp = margins(c);
                int cw = c.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                if (x > 0 && x + cw > maxW) break;
                x += cw;
                lineH = Math.max(lineH, c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
            }
            x = getPaddingLeft();
            for (int k = i; k < j; k++) {
                View c = getChildAt(k);
                if (c.getVisibility() == GONE) continue;
                MarginLayoutParams lp = margins(c);
                int top = y + lp.topMargin + (lineH - lp.topMargin - lp.bottomMargin - c.getMeasuredHeight()) / 2;
                c.layout(x + lp.leftMargin, top, x + lp.leftMargin + c.getMeasuredWidth(), top + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            }
            y += lineH;
            i = Math.max(j, i + 1);
        }
    }

    private static MarginLayoutParams margins(View c) {
        ViewGroup.LayoutParams p = c.getLayoutParams();
        return p instanceof MarginLayoutParams ? (MarginLayoutParams) p : new MarginLayoutParams(p == null ? new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) : p);
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof MarginLayoutParams; }
    @Override public LayoutParams generateLayoutParams(android.util.AttributeSet a) { return new MarginLayoutParams(getContext(), a); }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
    @Override protected LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof MarginLayoutParams ? new MarginLayoutParams((MarginLayoutParams) p) : new MarginLayoutParams(p);
    }
}
