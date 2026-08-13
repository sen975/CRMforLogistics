package com.crmforlogistics.messagecenter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@FunctionalInterface
interface AuditDiskSpaceProbe {
    AuditDiskSpaceProbe SYSTEM = directory -> Files.getFileStore(directory).getUsableSpace();

    long usableBytes(Path directory) throws IOException;

    static AuditDiskSpaceProbe system() {
        return SYSTEM;
    }
}
