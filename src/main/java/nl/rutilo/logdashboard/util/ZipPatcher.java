package nl.rutilo.logdashboard.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static nl.rutilo.logdashboard.util.ZipUtil.asString;
import static nl.rutilo.logdashboard.util.ZipUtil.copyOf;
import static nl.rutilo.logdashboard.util.ZipUtil.entryIterableOf;
import static nl.rutilo.logdashboard.util.ZipUtil.exhaust;
import static nl.rutilo.logdashboard.util.ZipUtil.openZipForReading;
import static nl.rutilo.logdashboard.util.ZipUtil.openZipForWriting;
import static nl.rutilo.logdashboard.util.ZipUtil.sorted;
import static nl.rutilo.logdashboard.util.ZipUtil.toBytes;

/* Copied from ZipDiff */
public class ZipPatcher {
    public static final String REMOVALS_FILENAME = ".removed_files.zipdiff";
    public static final String EXPECTED_CRC_FILENAME = ".expected_crc.zipdiff";
    protected static final byte[] CODE_HEADER_NOCHANGE = toBytes("@ZipDiff:NOCHANGE@");
    private final File zipFile;
    private byte[] headerData;
    private final Map<String, ZipEntry> nameToEntry = new HashMap<>();
    public static class Changes {
        final byte[] newHeaderData;
        final Set<String> added    = new HashSet<>();
        final Set<String> removed  = new HashSet<>();
        final Set<String> replaced = new HashSet<>();
        public Changes(byte[] newHeaderData) { this.newHeaderData = newHeaderData; }
        public boolean hasNewHeaderData() { return !ZipUtil.isEqual(CODE_HEADER_NOCHANGE, newHeaderData); }
    }

    public ZipPatcher(File file) throws IOException {
        zipFile = file;
        final byte[][] hdr = { new byte[0] };
        try(final ZipInputStream zipIn = openZipForReading(file, h -> hdr[0] = (h == null ? new byte[0] : h))) {
            for(final ZipEntry entryIn : entryIterableOf(zipIn)) {
                nameToEntry.put(entryIn.getName(), entryIn);
            }
        }
        headerData = hdr[0];
    }

    public byte[] getHeaderData() { return headerData; }
    public ZipPatcher setHeaderData(byte[] hd) { headerData = hd == null ? new byte[0] : hd; return this; }
    public Changes getChangesTo(ZipPatcher other) {
        final byte[] changedHeaderData = ZipUtil.isEqual(headerData, other.headerData) ? CODE_HEADER_NOCHANGE : other.headerData;
        final Changes changes = new Changes(changedHeaderData);

        changes.removed.addAll(nameToEntry.keySet());
        changes.removed.removeAll(other.nameToEntry.keySet());

        other.nameToEntry.values().forEach(otherEntry -> {
            final ZipEntry entry = nameToEntry.get(otherEntry.getName());

            if(entry == null) changes.added.add(otherEntry.getName());
            else if(entry.getCrc() != otherEntry.getCrc()) changes.replaced.add(entry.getName());
        });
        return changes;
    }

    public void generatePatchFileTo(ZipPatcher other, File patchFile) throws IOException {
        generatePatchFileTo(other, getChangesTo(other), patchFile);
    }
    public void generatePatchFileTo(ZipPatcher other, Changes changes, File patchFile) throws IOException {
        Files.deleteIfExists(patchFile.toPath());

        // changes.additions & changes.replacements -> put in patchFile
        // changes.removals -> put as textfile in patchFile
        try(final ZipInputStream zipIn = openZipForReading(other.zipFile, /*already known by other*/null);
            final ZipOutputStream patchOut = openZipForWriting(patchFile, changes.hasNewHeaderData() ? changes.newHeaderData : other.headerData)) {

            final byte[] removedFilesText = toBytes(String.join("\n", sorted(changes.removed)));
            final ZipEntry removalsEntry = new ZipEntry(REMOVALS_FILENAME);
            patchOut.putNextEntry(removalsEntry);
            patchOut.write(removedFilesText);
            patchOut.closeEntry();

            long zipCrc = 0;
            for(final ZipEntry entryIn : entryIterableOf(zipIn)) {
                if(   changes.added   .contains(entryIn.getName())
                    || changes.replaced.contains(entryIn.getName())) {
                    patchOut.putNextEntry(copyOf(entryIn));
                    ZipUtil.copyAndReturnCount(zipIn, patchOut);
                    patchOut.closeEntry();
                } else {
                    ZipUtil.drain(zipIn); // needed for crc
                }
                zipCrc ^= entryIn.getCrc();
            }

            final ZipEntry expectedCrcEntry = new ZipEntry(EXPECTED_CRC_FILENAME);
            patchOut.putNextEntry(expectedCrcEntry);
            patchOut.write(toBytes(String.valueOf(zipCrc)));
            patchOut.closeEntry();
        }
    }

    public void patchTo(File patchFile, File generatedFile, boolean ignoreValidation) throws IOException {
        Files.deleteIfExists(generatedFile.toPath());

        final ZipPatcher patchZip = new ZipPatcher(patchFile);
        final byte[] patchedHeaderData = ZipUtil.isEqual(CODE_HEADER_NOCHANGE, patchZip.headerData) ? headerData : patchZip.headerData;

        try(final ZipOutputStream zipOut = openZipForWriting(generatedFile, patchedHeaderData)) {
            copy(zipFile, zipOut, getNamesToSkip(patchFile));
            copy(patchFile, zipOut, new HashSet<>(Arrays.asList(REMOVALS_FILENAME, EXPECTED_CRC_FILENAME)));
        }
        if(!ignoreValidation) {
            final long targetCrc = crcOfZipFile(generatedFile);
            final long expectedCrc = expectedCrcFromPatch(patchFile);
            if (targetCrc != expectedCrc) {
                Files.delete(generatedFile.toPath());
                throw new IOException("CRC is incorrect. Patch failed.");
            }
        }
    }

    public void writeTo(File outFile, Map<String, byte[]> data) throws IOException {
        try(final ZipOutputStream out = openZipForWriting(outFile, headerData)) {
            for(final Map.Entry<String,ZipEntry> entry : nameToEntry.entrySet()) {
                out.putNextEntry(copyOf(entry.getValue()));
                out.write(data.get(entry.getKey()));
            }
        }
    }

    public Map<String,byte[]> readFully() throws IOException {
        final LinkedHashMap<String,byte[]> map = new LinkedHashMap<>();
        try(final ZipInputStream zipIn = openZipForReading(zipFile /*ignore header*/)) {
            for(final ZipEntry entryIn : entryIterableOf(zipIn)) {
                map.put(entryIn.getName(), ZipUtil.exhaust(zipIn));
            }
        }
        return map;
    }

    private static long crcOfZipFile(File zipFile) throws IOException {
        try(final ZipInputStream patchIn = openZipForReading(zipFile, /*headerText:*/null)) {

            long crc = 0;
            for(final ZipEntry patchEntry : entryIterableOf(patchIn)) {
                ZipUtil.drain(patchIn); // this sets the CRC value in the entry
                crc ^= patchEntry.getCrc();
            }
            return crc;
        }
    }
    private static long expectedCrcFromPatch(File patchFile) throws IOException {
        try(final ZipInputStream patchIn = openZipForReading(patchFile, /*headerText:*/null)) {

            for(final ZipEntry patchEntry : entryIterableOf(patchIn)) {
                if (EXPECTED_CRC_FILENAME.equals(patchEntry.getName())) {
                    return Long.parseLong(asString(exhaust(patchIn)));
                }
            }
        }
        return -1;
    }

    private static Set<String> getNamesToSkip(File patchFile) throws IOException {
        final Set<String> namesToSkip = new HashSet<>();

        try(final ZipInputStream patchIn = openZipForReading(patchFile, /*headerText:*/null)) {
            for(final ZipEntry patchEntry : entryIterableOf(patchIn)) {
                if (REMOVALS_FILENAME.equals(patchEntry.getName())) {
                    namesToSkip.addAll(
                        new HashSet<>(Arrays.asList(asString(exhaust(patchIn)).split("\n")))
                    );
                } else {
                    namesToSkip.add(patchEntry.getName());
                }
            }
        }
        return namesToSkip;
    }
    private static void copy(File zipFile, ZipOutputStream zipOut, Set<String> namesToSkip) throws IOException {
        try(final ZipInputStream zipIn = openZipForReading(zipFile, /*headerText not needed*/null)) {
            for(final ZipEntry entryIn : entryIterableOf(zipIn)) {
                if (!namesToSkip.contains(entryIn.getName())) {
                    zipOut.putNextEntry(copyOf(entryIn));
                    ZipUtil.copyAndReturnCount(zipIn, zipOut);
                    zipOut.closeEntry();
                }
            }
        }
    }
}
