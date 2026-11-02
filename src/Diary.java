package daily.recorder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * Diary storage: one text file per day (days/2026-10-06.txt), named by the local date where the entries were
 * written. Each line is one entry: epoch millis, timezone ID, and the text with backslash, \n, \r and tab escaped.
 * Lines are in the order the entries are shown; new entries go to the bottom and can be moved within their day.
 * A line that can't be read is skipped but kept in the file as it is, so it never blocks or loses anything.
 * Plain Java only, so it can be tested off the phone.
 */
final class Diary {
    static final String EXPORT_HEADER = "daily-recorder-export v1";

    static final class Entry {
        final long millis; // moment written; also the entry's ID within its day
        final ZoneId zone; // timezone where it was written
        final String text;

        Entry(long millis, ZoneId zone, String text) {
            this.millis = millis;
            this.zone = zone;
            this.text = text;
        }

        ZonedDateTime time() {
            return Instant.ofEpochMilli(millis).atZone(zone);
        }

        LocalDate date() {
            return time().toLocalDate();
        }
    }

    /** A day's file contents: readable entries in display order, plus damaged lines kept verbatim. */
    static final class Day {
        final List<Entry> entries = new ArrayList<>();
        final List<String> damaged = new ArrayList<>();
    }

    private final File dir;

    Diary(File filesDir) {
        dir = new File(filesDir, "days");
    }

    Day read(LocalDate date) throws IOException {
        Day day = new Day();
        File file = file(date);
        if (!file.exists()) return day;
        try (BufferedReader reader = reader(new FileInputStream(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    day.entries.add(parse(line));
                } catch (RuntimeException e) {
                    day.damaged.add(line);
                }
            }
        }
        return day;
    }

    /**
     * Adds text to the bottom of a day, timed at the current time of day in the phone's current timezone: for
     * today that is simply now; for a past day it is that date at the current clock time.
     */
    void add(LocalDate date, String text) throws IOException {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime when = ZonedDateTime.of(date, LocalTime.now(zone), zone);
        if (!when.toLocalDate().equals(date)) when = date.atStartOfDay(zone); // clock time skipped by a DST change
        long millis = when.toInstant().toEpochMilli();
        Day day = read(date);
        while (indexOf(day.entries, millis) >= 0) millis++; // keep IDs unique within the day
        day.entries.add(new Entry(millis, zone, text));
        save(date, day);
    }

    void update(Entry entry, String text) throws IOException {
        change(entry, (entries, i) -> entries.set(i, new Entry(entry.millis, entry.zone, text)));
    }

    /** Moves an entry to a new position within its day (0 = top). */
    void move(Entry entry, int position) throws IOException {
        change(entry, (entries, i) -> {
            Entry moved = entries.remove(i);
            entries.add(Math.max(0, Math.min(position, entries.size())), moved);
        });
    }

    void delete(Entry entry) throws IOException {
        change(entry, (entries, i) -> entries.remove(i));
    }

    private interface Change {
        void apply(List<Entry> entries, int index);
    }

    /** Re-reads the entry's day, applies the change at the entry's position, and saves; does nothing if it's gone. */
    private void change(Entry entry, Change change) throws IOException {
        Day day = read(entry.date());
        int i = indexOf(day.entries, entry.millis);
        if (i < 0) return;
        change.apply(day.entries, i);
        save(entry.date(), day);
    }

    /** Days of the month with at least one readable entry. */
    Set<LocalDate> datesWithEntries(YearMonth month) throws IOException {
        Set<LocalDate> dates = new HashSet<>();
        String prefix = month + "-";
        for (String name : dayFiles()) {
            if (!name.startsWith(prefix)) continue;
            LocalDate date = dateOf(name);
            if (!read(date).entries.isEmpty()) dates.add(date);
        }
        return dates;
    }

    /**
     * Writes every readable entry to out, day by day in date order and each day in display order.
     * Returns {entries written, damaged lines skipped}.
     */
    int[] exportTo(OutputStream out) throws IOException {
        List<Entry> all = new ArrayList<>();
        int damaged = 0;
        for (String name : dayFiles()) {
            Day day = read(dateOf(name));
            all.addAll(day.entries);
            damaged += day.damaged.size();
        }
        Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write(EXPORT_HEADER + "\n");
        for (Entry e : all) writer.write(format(e) + "\n");
        writer.flush();
        return new int[] { all.size(), damaged };
    }

    /**
     * Merges an export file into the diary. Entries already present (same moment) are kept as they are; new ones
     * go to the bottom of their day in the file's order, so an export imported into an empty diary keeps its order.
     * All or nothing: the whole file is checked, and every changed day is written to a temp file, before any
     * day file is replaced. Returns {added, skipped}.
     */
    int[] importFrom(InputStream in) throws IOException {
        BufferedReader reader = reader(in);
        if (!EXPORT_HEADER.equals(reader.readLine())) throw new IOException("not a Daily Recorder export file");
        Map<LocalDate, List<Entry>> byDate = new TreeMap<>();
        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isEmpty()) continue;
            Entry e;
            try {
                e = parse(line);
            } catch (RuntimeException ex) {
                throw new IOException("line " + lineNumber + " is not a valid entry");
            }
            byDate.computeIfAbsent(e.date(), d -> new ArrayList<>()).add(e);
        }

        int added = 0, skipped = 0;
        Map<File, File> pending = new LinkedHashMap<>(); // temp file -> day file it replaces
        try {
            for (Map.Entry<LocalDate, List<Entry>> imported : byDate.entrySet()) {
                LocalDate date = imported.getKey();
                Day day = read(date);
                int before = day.entries.size();
                for (Entry e : imported.getValue()) {
                    if (indexOf(day.entries, e.millis) >= 0) {
                        skipped++;
                    } else {
                        day.entries.add(e);
                        added++;
                    }
                }
                if (day.entries.size() > before) pending.put(writeTemp(date, day), file(date));
            }
        } catch (IOException e) {
            for (File tmp : pending.keySet()) tmp.delete();
            throw e;
        }
        for (Map.Entry<File, File> p : pending.entrySet()) commit(p.getKey(), p.getValue());
        return new int[] { added, skipped };
    }

    /** Writes a day's file atomically: a crash mid-save leaves the old file intact. */
    private void save(LocalDate date, Day day) throws IOException {
        File file = file(date);
        if (day.entries.isEmpty() && day.damaged.isEmpty()) {
            if (file.exists() && !file.delete()) throw new IOException("could not delete " + file.getName());
            return;
        }
        commit(writeTemp(date, day), file);
    }

    /** Writes a day's new content (entries, then damaged lines) to a temp file next to it; commit() swaps it in. */
    private File writeTemp(LocalDate date, Day day) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not create " + dir);
        StringBuilder content = new StringBuilder();
        for (Entry e : day.entries) content.append(format(e)).append('\n');
        for (String line : day.damaged) content.append(line).append('\n');

        File tmp = new File(dir, file(date).getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        return tmp;
    }

    private static void commit(File tmp, File file) throws IOException {
        if (!tmp.renameTo(file)) throw new IOException("could not save " + file.getName());
    }

    private File file(LocalDate date) {
        return new File(dir, date + ".txt");
    }

    private static LocalDate dateOf(String fileName) {
        return LocalDate.parse(fileName.substring(0, 10));
    }

    private List<String> dayFiles() {
        List<String> names = new ArrayList<>();
        String[] list = dir.list();
        if (list == null) return names;
        for (String name : list) {
            if (name.length() == 14 && name.endsWith(".txt")) names.add(name);
        }
        Collections.sort(names);
        return names;
    }

    private static int indexOf(List<Entry> entries, long millis) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).millis == millis) return i;
        }
        return -1;
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static String format(Entry e) {
        return e.millis + "\t" + e.zone.getId() + "\t" + escape(e.text);
    }

    private static Entry parse(String line) {
        String[] parts = line.split("\t", 3);
        if (parts.length != 3) throw new IllegalArgumentException("expected 3 fields");
        return new Entry(Long.parseLong(parts[0]), ZoneId.of(parts[1]), unescape(parts[2]));
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                b.append(c);
                continue;
            }
            char next = s.charAt(++i);
            switch (next) {
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                default -> b.append(next);
            }
        }
        return b.toString();
    }
}
