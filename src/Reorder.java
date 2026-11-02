package daily.recorder;

import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/**
 * Drag-to-reorder for rows at the top of a vertical LinearLayout inside a ScrollView. Dragging a row's handle
 * lifts the row and makes it follow the finger; the rows it passes slide out of the way, and the list scrolls
 * when the finger is near its top or bottom edge. On release the listener gets the old and new position.
 */
final class Reorder {
    interface Listener {
        void onMoved(int from, int to);
    }

    private static final long SHIFT_MS = 150;
    private static final int TRANSPARENT = 0;

    private final ScrollView scroll;
    private final LinearLayout list;
    private final int edgePx, scrollStepPx, liftPx, liftColor;
    private final Listener listener;
    private final List<View> rows = new ArrayList<>();
    private final Runnable autoScroll = this::autoScroll;

    private View dragged; // null when no drag is in progress
    private int from, to;
    private float startRawY, rawY;
    private int startScrollY;

    /**
     * @param edgePx       distance from the list's top/bottom edge where dragging scrolls it
     * @param scrollStepPx how far the list scrolls per frame while doing so
     * @param liftPx       elevation of the dragged row
     * @param liftColor    opaque background of the dragged row, so rows sliding under it stay hidden
     */
    Reorder(ScrollView scroll, LinearLayout list, int edgePx, int scrollStepPx, int liftPx, int liftColor,
            Listener listener) {
        this.scroll = scroll;
        this.list = list;
        this.edgePx = edgePx;
        this.scrollStepPx = scrollStepPx;
        this.liftPx = liftPx;
        this.liftColor = liftColor;
        this.listener = listener;
    }

    boolean isDragging() {
        return dragged != null;
    }

    /** Forgets all rows; call before the list is rebuilt. */
    void clear() {
        rows.clear();
    }

    /** Registers the next row (rows must be added in list order) and the handle that drags it. */
    void add(View row, View handle) {
        rows.add(row);
        handle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> start(row, event.getRawY());
                case MotionEvent.ACTION_MOVE -> drag(event.getRawY());
                case MotionEvent.ACTION_UP -> finish(true);
                case MotionEvent.ACTION_CANCEL -> finish(false);
                default -> { }
            }
            return true;
        });
    }

    private void start(View row, float y) {
        dragged = row;
        from = to = rows.indexOf(row);
        startRawY = rawY = y;
        startScrollY = scroll.getScrollY();
        list.requestDisallowInterceptTouchEvent(true); // keep the ScrollView from taking over the gesture
        row.setTranslationZ(liftPx); // drawn above the rows it passes
        row.setBackgroundColor(liftColor);
        scroll.postOnAnimation(autoScroll);
    }

    private void drag(float y) {
        if (dragged == null) return;
        rawY = y;
        update();
    }

    /** Positions the dragged row under the finger and slides the rows it has passed. */
    private void update() {
        float offset = rawY - startRawY + scroll.getScrollY() - startScrollY;
        dragged.setTranslationY(offset);
        float center = dragged.getTop() + dragged.getHeight() / 2f + offset;

        int target = from;
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            float rowCenter = row.getTop() + row.getHeight() / 2f;
            if (i > from && center > rowCenter) target = i;
            if (i < from && center < rowCenter && target == from) target = i;
        }
        if (target == to) return;
        to = target;

        for (int i = 0; i < rows.size(); i++) {
            if (i == from) continue;
            float shift = 0;
            if (from < to && i > from && i <= to) shift = -dragged.getHeight();
            if (to < from && i >= to && i < from) shift = dragged.getHeight();
            rows.get(i).animate().translationY(shift).setDuration(SHIFT_MS);
        }
    }

    /** While dragging, scrolls the list when the finger is near its top or bottom edge. */
    private void autoScroll() {
        if (dragged == null) return;
        int[] location = new int[2];
        scroll.getLocationOnScreen(location);
        int top = location[1], bottom = top + scroll.getHeight();
        int direction = rawY < top + edgePx ? -1 : rawY > bottom - edgePx ? 1 : 0;
        if (direction != 0) {
            scroll.scrollBy(0, direction * scrollStepPx);
            update();
        }
        scroll.postOnAnimation(autoScroll);
    }

    private void finish(boolean commit) {
        if (dragged == null) return;
        dragged.setTranslationZ(0);
        dragged.setBackgroundColor(TRANSPARENT);
        dragged = null;
        list.requestDisallowInterceptTouchEvent(false);
        for (View row : rows) {
            row.animate().cancel();
            row.setTranslationY(0);
        }
        if (commit && to != from) listener.onMoved(from, to);
    }
}
