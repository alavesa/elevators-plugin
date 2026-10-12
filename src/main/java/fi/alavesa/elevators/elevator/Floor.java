package fi.alavesa.elevators.elevator;

import java.util.regex.Pattern;

/** A stop of an elevator: the Y the cabin floor rests at (the block surface riders stand on) and a name. */
public record Floor(String name, double y) implements Comparable<Floor> {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    public static boolean validName(String n) { return n != null && NAME.matcher(n).matches(); }

    @Override public int compareTo(Floor o) { return Double.compare(y, o.y); }
}
