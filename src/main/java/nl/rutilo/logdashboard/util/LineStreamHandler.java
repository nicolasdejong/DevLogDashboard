package nl.rutilo.logdashboard.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class LineStreamHandler {
    private final InputStream stream;
    private static final char LF            = '\n';
    private static final char CR            = '\r';
    private static final char BS            = '\b';
    private String            nextLineInit  = "";
    private boolean           nextOverwrite = false;
    private int               peekByte      = -1;

    public static class Line {
        public final String text;
        public final boolean replacesPreviousLine;

        private Line(String text, boolean replacesPreviousLine) {
            this.text = text;
            this.replacesPreviousLine = replacesPreviousLine;
        }
    }

    public LineStreamHandler(InputStream in) {
        stream = in;
    }

    private Line nextLine() {
        final StringBuilder sb = new StringBuilder(nextLineInit);
        boolean replacing = sb.length() != 0;
        boolean overwrite = nextOverwrite;
        boolean done = false;
        boolean endOfStream = false;
        int index = 0;
        int readCount = 0;
        int prevByte = -1;
        nextLineInit = "";
        nextOverwrite = false;

        while(!done) {
            try {
                final int b = peekByte >= 0 ? peekByte : stream.read();
                peekByte = -1;
                if(b != ' ') readCount++;

                if(b < 0) { endOfStream = sb.length() == 0; done = true; }
                else
                switch(b) {
                    case LF: done = true; break;
                    case CR:
                        done = true;
                        // optimization: CR followed by a LF can be interpreted as just an LF
                        //               This prevents a duplicate overwrite.
                        peekByte = stream.read();
                        if(peekByte == LF) {
                            peekByte = -1;
                        } else {
                            nextLineInit = sb.toString();
                            nextOverwrite = true;
                        }
                        break;
                    case BS:
                        if(readCount == 1) {
                            if(overwrite) removeAt(sb, index--); else removeLastOf(sb);
                            readCount--;
                        } else {
                            done = true;
                            nextLineInit = removeLastOf(sb.toString());
                        }
                        break;
                    default:
                        if(overwrite) sb.setCharAt(index++, (char)b); else sb.append((char)b);
                }
                prevByte = b;
            } catch (IOException e) {
                return null;
            }
        }
        return endOfStream ? null : new Line(sb.toString(), replacing);
    }
    private String removeLastOf(String s) { return s.isEmpty() ? "" : s.substring(0, s.length()-1); }
    private void removeLastOf(StringBuilder sb) { if(sb.length() > 0) sb.setLength(sb.length()-1); }
    private void removeAt(StringBuilder sb, int index) { if(index >= 0 && index < sb.length()) sb.delete(index, index+1); }

    private Iterator<Line> iterator() {
        return new Iterator<Line>() {
            private Line nextLine;

            @Override public boolean hasNext() {
                if(nextLine == null) {
                    nextLine = nextLine();
                    //if(nextLine != null) System.out.println("next line: " + (nextLine.replacesPreviousLine ? "(REP)" : "") + nextLine.text);
                }
                return nextLine != null;
            }
            @Override public Line next() {
                if(nextLine == null) throw new IllegalStateException("next() without hasNext() == true");
                final Line result = nextLine;
                nextLine = null;
                return result;
            }
        };
    }

    public Stream<Line> stream() {
        return StreamSupport.stream(
            Spliterators.spliterator(iterator(), /*initial size=*/0L, Spliterator.NONNULL),
            /*parallel=*/false
        );
    }
    public void forEach(Consumer<Line> linesConsumer) {
        stream().forEach(linesConsumer);
    }
}
