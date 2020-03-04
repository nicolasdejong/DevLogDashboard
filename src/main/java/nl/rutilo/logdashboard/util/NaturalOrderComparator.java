package nl.rutilo.logdashboard.util;

import lombok.NonNull;

import java.util.Comparator;
import java.util.function.Function;

// From https://github.com/paour/natorder/blob/master/NaturalOrderComparator.java
// Based on original C version by Martin Pool
//
// NdJ: Slightly altered for generic use
public class NaturalOrderComparator<T> implements Comparator<T> {
    private final Function<T,String> stringMapper;

    public NaturalOrderComparator() { this(Object::toString); }
    public NaturalOrderComparator(@NonNull Function<T,String> stringMapper) {
        this.stringMapper = stringMapper;
    }

    private int compareRight(String a, String b) {
        int bias = 0;
        int ia = 0;
        int ib = 0;

        // The longest run of digits wins. That aside, the greatest
        // value wins, but we can't know that it will until we've scanned
        // both numbers to know that they have the same magnitude, so we
        // remember it in BIAS.
        for (;; ia++, ib++)
        {
            final char ca = charAt(a, ia);
            final char cb = charAt(b, ib);

            if (!isDigit(ca) && !isDigit(cb)) {
                return bias;
            }
            if (!isDigit(ca)) {
                return -1;
            }
            if (!isDigit(cb)) {
                return +1;
            }
            if (ca == 0 && cb == 0) {
                return bias;
            }

            if (bias == 0) {
                if (ca < cb) {
                    bias = -1;
                } else if (ca > cb) {
                    bias = +1;
                }
            }
        }
    }

    public int compare(T ta, T tb) {
        final String a = ta == null ? "" : stringMapper.apply(ta);
        final String b = tb == null ? "" : stringMapper.apply(tb);
        int ia = 0;
        int ib = 0;
        int nza = 0;
        int nzb = 0;
        char ca;
        char cb;

        while (true) {
            // Only count the number of zeroes leading the last number compared
            nza = nzb = 0;

            ca = charAt(a, ia);
            cb = charAt(b, ib);

            // skip over leading spaces or zeros
            while (Character.isSpaceChar(ca) || ca == '0') {
                if (ca == '0') {
                    nza++;
                } else {
                    // Only count consecutive zeroes
                    nza = 0;
                }

                ca = charAt(a, ++ia);
            }

            while (Character.isSpaceChar(cb) || cb == '0') {
                if (cb == '0') {
                    nzb++;
                } else {
                    // Only count consecutive zeroes
                    nzb = 0;
                }

                cb = charAt(b, ++ib);
            }

            // Process run of digits
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                final int bias = compareRight(a.substring(ia), b.substring(ib));
                if (bias != 0) {
                    return bias;
                }
            }

            if (ca == 0 && cb == 0) {
                // The strings compare the same. Perhaps the caller
                // will want to call strcmp to break the tie.
                return compareEqual(a, b, nza, nzb);
            }
            if (ca < cb) {
                return -1;
            }
            if (ca > cb) {
                return +1;
            }

            ++ia;
            ++ib;
        }
    }

    private static boolean isDigit(char c) {
        return Character.isDigit(c) || c == '.' || c == ',';
    }

    private static char charAt(String s, int i) {
        return i >= s.length() ? 0 : s.charAt(i);
    }

    private static int compareEqual(String a, String b, int nza, int nzb) {
        if (nza - nzb != 0)
            return nza - nzb;

        if (a.length() == b.length())
            return a.compareTo(b);

        return a.length() - b.length();
    }
}
