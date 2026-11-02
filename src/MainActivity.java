package daily.recorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_EXPORT = 1, REQUEST_IMPORT = 2;
    private static final String STATE_DAY = "day", STATE_MONTH = "month", STATE_DRAFT = "draft";
    private static final String STATE_EDIT_DAY = "editDay", STATE_EDIT_ID = "editId", STATE_EDIT_TEXT = "editText";
    private static final int INPUT_ID = 1; // lets Android restore the input's cursor and scroll after recreation
    private static final int INPUT_MAX_LINES = 4; // the input box grows up to this, then scrolls
    private static final int SWIPE_MIN_DP = 64; // how far a sideways swipe must go to change the day or month
    private static final int MULTILINE_TEXT = InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;

    // Text sizes (sp)
    private static final int TEXT_TITLE = 18, TEXT_BODY = 16, TEXT_SMALL = 14, TEXT_ICON = 24;
    // Spacing (dp)
    private static final int SPACE_XS = 4, SPACE_S = 8, SPACE_M = 12, SPACE_L = 24, SPACE_XL = 48;
    // Secondary text color and the background of a note being dragged, per light/dark mode
    private static final int GRAY_LIGHT = 0xFF5F6368, GRAY_DARK = 0xFF9AA0A6;
    private static final int LIFT_LIGHT = 0xFFE8EAED, LIFT_DARK = 0xFF3C4043;
    // Pure white or black background, per light/dark mode
    private static final int BACKGROUND_LIGHT = 0xFFFFFFFF, BACKGROUND_DARK = 0xFF000000;
    // Accent for today's date on the calendar; readable on both white and black
    private static final int BLUE = 0xFF0066FF;

    private int gray, lift, background, touchSlop;
    private Diary diary;
    private LocalDate shownDay;
    private YearMonth shownMonth;
    private boolean monthMode;

    private LinearLayout dayScreen, entryList, inputRow;
    private TextView dayTitle;
    private ScrollView entryScroll;
    private Reorder reorder;
    private List<Diary.Entry> shownEntries = List.of();
    private EditText input;
    private Button todayButton;

    // The open edit dialog, if any, so it can be reopened with its unsaved text after recreation
    private AlertDialog editDialog;
    private Diary.Entry editing;
    private EditText editField;

    private LinearLayout monthScreen, monthGrid;
    private TextView monthTitle;

    private float swipeStartX, swipeStartY;
    private boolean swipeTracking, swiping;

    private interface IoAction {
        void run() throws IOException;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE); // the system light/dark theme comes with a title bar
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        gray = dark ? GRAY_DARK : GRAY_LIGHT;
        lift = dark ? LIFT_DARK : LIFT_LIGHT;
        background = dark ? BACKGROUND_DARK : BACKGROUND_LIGHT;
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop(); // movement before a touch counts as a gesture
        diary = new Diary(getFilesDir());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildDayScreen();
        buildMonthScreen();

        String day = state == null ? null : state.getString(STATE_DAY);
        String month = state == null ? null : state.getString(STATE_MONTH);
        shownDay = day == null ? LocalDate.now() : LocalDate.parse(day);
        shownMonth = month == null ? YearMonth.from(shownDay) : YearMonth.parse(month);
        setScreen(state == null || month != null); // start on the calendar; restore the previous view after recreation

        if (state != null) {
            // Saved by hand: Android only keeps the text of views on screen, and the input is hidden on the calendar.
            input.setText(state.getString(STATE_DRAFT));
            String editDay = state.getString(STATE_EDIT_DAY);
            if (editDay != null) {
                reopenEdit(LocalDate.parse(editDay), Long.parseLong(state.getString(STATE_EDIT_ID)),
                        state.getString(STATE_EDIT_TEXT));
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (editDialog != null) editDialog.dismiss(); // its text is already saved in onSaveInstanceState
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // picks up a changed date or timezone
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_DAY, shownDay.toString());
        if (monthMode) out.putString(STATE_MONTH, shownMonth.toString());
        out.putString(STATE_DRAFT, input.getText().toString());
        if (editing != null) {
            out.putString(STATE_EDIT_DAY, editing.date().toString());
            out.putString(STATE_EDIT_ID, Long.toString(editing.millis));
            out.putString(STATE_EDIT_TEXT, editField.getText().toString());
        }
    }

    // ---- Day screen ----

    private void buildDayScreen() {
        dayScreen = screen();
        dayTitle = header(dayScreen, "day");
        dayTitle.setOnClickListener(v -> { shownMonth = YearMonth.from(shownDay); setScreen(true); });

        entryList = column();
        entryScroll = new ScrollView(this);
        entryScroll.addView(entryList);
        dayScreen.addView(entryScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        reorder = new Reorder(entryScroll, entryList, dp(SPACE_XL), dp(SPACE_S), dp(SPACE_S), lift, (from, to) -> {
            Diary.Entry moved = shownEntries.get(from);
            attempt("move", () -> diary.move(moved, to));
        });

        input = new EditText(this);
        input.setId(INPUT_ID);
        input.setInputType(MULTILINE_TEXT);
        input.setMaxLines(INPUT_MAX_LINES);
        Button add = iconButton("+", "Add entry", v -> addEntry());
        add.setTextSize(TEXT_ICON);
        inputRow = row(weighted(input), add);
        dayScreen.addView(inputRow);

        todayButton = button("Go to today", v -> { shownDay = LocalDate.now(); refresh(); });
        dayScreen.addView(todayButton);
    }

    private void refreshDay() throws IOException {
        LocalDate today = LocalDate.now();
        // Weekday, month and day in the phone's language; the year only when it isn't this year.
        String skeleton = shownDay.getYear() == today.getYear() ? "EEEEMMMMd" : "EEEEMMMMdy";
        dayTitle.setText(shownDay.format(DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton))));
        inputRow.setVisibility(shownDay.isAfter(today) ? View.GONE : View.VISIBLE); // today and past days only
        todayButton.setVisibility(shownDay.equals(today) ? View.GONE : View.VISIBLE);

        entryList.removeAllViews();
        Diary.Day day = diary.read(shownDay);
        reorder.clear();
        shownEntries = day.entries;
        for (Diary.Entry entry : day.entries) {
            TextView handle = text(TEXT_TITLE);
            handle.setTextColor(gray);
            handle.setText("≡");
            handle.setPadding(dp(SPACE_S), dp(SPACE_S), dp(SPACE_M), dp(SPACE_S)); // generous touch area
            TextView body = text(TEXT_BODY);
            body.setText(entry.text);
            body.setPadding(0, dp(SPACE_S), 0, dp(SPACE_S));
            LinearLayout line = row(handle, weighted(body));
            line.setPadding(0, dp(SPACE_XS), dp(SPACE_XS), dp(SPACE_XS));
            line.setOnClickListener(v -> editEntry(entry, entry.text));
            entryList.addView(line);
            reorder.add(line, handle);
        }
        if (entryList.getChildCount() == 0) {
            TextView empty = note("Nothing written this day.");
            empty.setPadding(0, dp(SPACE_XL), 0, 0);
            entryList.addView(empty);
        }
        if (!day.damaged.isEmpty()) {
            TextView warning = note(day.damaged.size()
                    + " damaged line(s) in this day's file couldn't be shown. They are kept as they are.");
            warning.setPadding(0, dp(SPACE_L), 0, 0);
            entryList.addView(warning);
        }
    }

    private void addEntry() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        attempt("save", () -> {
            diary.add(shownDay, text);
            input.setText("");
            entryScroll.post(() -> entryScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    /** Opens the edit dialog for an entry, with text in the field. The entry's time shows only here, as the title. */
    private void editEntry(Diary.Entry entry, String text) {
        String title = entry.time().format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(
                Locale.getDefault(), DateFormat.is24HourFormat(this) ? "Hm" : "hm")));
        EditText field = new EditText(this);
        field.setText(text);
        field.setInputType(MULTILINE_TEXT);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(SPACE_L), dp(SPACE_S), dp(SPACE_L), 0);
        box.addView(field);

        editing = entry;
        editField = field;
        editDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String edited = field.getText().toString().trim();
                    if (edited.isEmpty()) {
                        confirmDelete(entry); // saving an emptied entry means removing it, but ask first
                    } else {
                        attempt("save", () -> diary.update(entry, edited));
                    }
                })
                .setNeutralButton("Delete", (dialog, which) -> confirmDelete(entry))
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(dialog -> { editDialog = null; editing = null; editField = null; })
                .show();
    }

    /** Reopens the edit dialog that was open before recreation, if its entry still exists. */
    private void reopenEdit(LocalDate day, long id, String text) {
        try {
            for (Diary.Entry entry : diary.read(day).entries) {
                if (entry.millis == id) {
                    editEntry(entry, text);
                    return;
                }
            }
        } catch (IOException e) {
            // the day can't be read, so there is nothing to reopen
        }
    }

    private void confirmDelete(Diary.Entry entry) {
        new AlertDialog.Builder(this)
                .setMessage("Delete this entry?")
                .setPositiveButton("Delete", (dialog, which) -> attempt("delete", () -> diary.delete(entry)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Month screen ----

    private void buildMonthScreen() {
        monthScreen = screen();
        monthTitle = header(monthScreen, "month");

        LinearLayout weekdays = row();
        weekdays.setPadding(0, dp(SPACE_L + SPACE_XS), 0, 0); // gap below the month title
        DayOfWeek day = firstDayOfWeek();
        for (int i = 0; i < 7; i++, day = day.plus(1)) {
            weekdays.addView(note(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())), weight());
        }
        monthScreen.addView(weekdays);

        monthGrid = column();
        monthScreen.addView(monthGrid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button export = button("Export", v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                // A generic type, so file managers keep the .dr name instead of adding .txt for text/plain
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, "daily-recorder.dr"), REQUEST_EXPORT));
        // Any file can be picked, since Android doesn't know the .dr type (it would hide those files under a
        // narrower filter); the import checks the file's first line and rejects anything that isn't an export.
        // Older .txt exports still import too.
        Button importButton = button("Import", v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), REQUEST_IMPORT));
        monthScreen.addView(row(weighted(export), weighted(importButton)));
    }

    private void refreshMonth() throws IOException {
        LocalDate today = LocalDate.now();
        // Month and year in the phone's language and word order
        monthTitle.setText(shownMonth.format(DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMy"))));
        Set<LocalDate> written = diary.datesWithEntries(shownMonth);

        monthGrid.removeAllViews();
        LocalDate first = shownMonth.atDay(1);
        LocalDate cell = first.minusDays((first.getDayOfWeek().getValue() - firstDayOfWeek().getValue() + 7) % 7);
        for (int week = 0; week < 6; week++) {
            LinearLayout days = row();
            for (int i = 0; i < 7; i++, cell = cell.plusDays(1)) {
                // Day number on top, and a line below it for the dot, kept even when empty so numbers never move.
                LinearLayout box = column();
                box.setPadding(0, dp(SPACE_XS), 0, dp(SPACE_XS));
                // Both fill the cell's width, so each centers its own text, like the weekday names above.
                TextView number = text(TEXT_BODY);
                number.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView dot = text(TEXT_BODY);
                dot.setGravity(Gravity.CENTER_HORIZONTAL);
                dot.setLines(1);
                box.addView(number);
                box.addView(dot);
                if (YearMonth.from(cell).equals(shownMonth)) {
                    LocalDate date = cell;
                    number.setText(String.valueOf(cell.getDayOfMonth()));
                    if (written.contains(cell)) dot.setText("•");
                    if (cell.equals(today)) {
                        number.setTypeface(Typeface.DEFAULT_BOLD);
                        number.setTextColor(BLUE);
                    }
                    box.setContentDescription(cell.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                            + (written.contains(cell) ? ", has entries" : ""));
                    box.setOnClickListener(v -> { shownDay = date; setScreen(false); });
                }
                days.addView(box, weight());
            }
            monthGrid.addView(days);
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        // Report success only after the file is closed: closing is when cloud storage may still fail.
        if (request == REQUEST_EXPORT) {
            attempt("export", () -> {
                int[] counts;
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    counts = diary.exportTo(out);
                }
                toast(counts[1] == 0
                        ? "Exported all " + counts[0] + " entries"
                        : "Exported " + counts[0] + " entries (" + counts[1] + " damaged lines skipped)");
            });
        } else if (request == REQUEST_IMPORT) {
            attempt("import", () -> {
                int[] counts;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    counts = diary.importFrom(in);
                }
                toast("Imported " + counts[0] + " entries" + (counts[1] > 0 ? ", " + counts[1] + " already here" : ""));
            });
        }
    }

    // ---- Navigation ----

    /** Shows the calendar (true) or the day screen (false), up to date. */
    private void setScreen(boolean month) {
        monthMode = month;
        setContentView(month ? monthScreen : dayScreen);
        refresh();
    }

    /** Shows the next (+1) or previous (-1) day, or month on the calendar. */
    private void step(int direction) {
        if (monthMode) shownMonth = shownMonth.plusMonths(direction); else shownDay = shownDay.plusDays(direction);
        refresh();
    }

    /**
     * Swiping sideways over the notes list or the calendar grid steps to the next or previous day or month.
     * A gesture becomes a swipe only once it is clearly sideways; from then on the views underneath get a cancel
     * instead of the touch, so a swipe never also scrolls, opens a note, or drags one.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        float dx = event.getRawX() - swipeStartX, dy = event.getRawY() - swipeStartY;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.getRawX();
                swipeStartY = event.getRawY();
                swipeTracking = contains(monthMode ? monthGrid : entryScroll, event);
                swiping = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!swipeTracking || swiping) break;
                if (reorder.isDragging() || Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                    swipeTracking = false; // a drag or a scroll, not a swipe
                } else if (Math.abs(dx) > touchSlop && Math.abs(dx) > 2 * Math.abs(dy)) {
                    swiping = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (swiping && Math.abs(dx) >= dp(SWIPE_MIN_DP)) step(dx < 0 ? 1 : -1); // finger left = forward
            }
            default -> { }
        }
        if (swiping) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swiping = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private static boolean contains(View view, MotionEvent event) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX() - location[0], y = event.getRawY() - location[1];
        return x >= 0 && x < view.getWidth() && y >= 0 && y < view.getHeight();
    }

    private void refresh() {
        try {
            if (monthMode) refreshMonth(); else refreshDay();
        } catch (IOException | RuntimeException e) {
            toast("Couldn't read the diary: " + describe(e));
        }
    }

    private void attempt(String what, IoAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException e) {
            toast("Couldn't " + what + ": " + describe(e));
        }
        refresh();
    }

    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---- View helpers ----

    /** A full-screen column drawn edge to edge, kept clear of the system bars, camera cutout and keyboard. */
    private LinearLayout screen() {
        LinearLayout screen = column();
        screen.setBackgroundColor(background); // also fills behind the system bars
        screen.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            int space = dp(SPACE_S);
            v.setPadding(bars.left + space, bars.top + space, bars.right + space, bars.bottom + space);
            return WindowInsets.CONSUMED;
        });
        return screen;
    }

    /** Adds a "◀ title ▶" row to the top of a screen, the arrows stepping by one day or month; returns the title. */
    private TextView header(LinearLayout screen, String unit) {
        TextView title = text(TEXT_TITLE);
        title.setGravity(Gravity.CENTER);
        screen.addView(row(
                iconButton("◀", "Previous " + unit, v -> step(-1)),
                weighted(title),
                iconButton("▶", "Next " + unit, v -> step(1))));
        return title;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row(View... children) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        for (View child : children) {
            if (child.getLayoutParams() == null) layout.addView(child); else layout.addView(child, child.getLayoutParams());
        }
        return layout;
    }

    private <T extends View> T weighted(T view) {
        view.setLayoutParams(weight());
        return view;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private TextView text(float sp) {
        TextView view = new TextView(this);
        view.setTextSize(sp);
        return view;
    }

    /** Small, gray, centered secondary text. */
    private TextView note(String message) {
        TextView view = text(TEXT_SMALL);
        view.setTextColor(gray);
        view.setGravity(Gravity.CENTER);
        view.setText(message);
        return view;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(onClick);
        return button;
    }

    private Button iconButton(String label, String description, View.OnClickListener onClick) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setContentDescription(description);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setOnClickListener(onClick);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static DayOfWeek firstDayOfWeek() {
        return WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    }
}
