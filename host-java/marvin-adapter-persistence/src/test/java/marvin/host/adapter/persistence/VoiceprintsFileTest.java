// SPDX-License-Identifier: MIT
package marvin.host.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import marvin.host.domain.conversation.Voiceprint;

/** voiceprints.json: kept, read back, private to its owner, tolerant of a damaged file. */
class VoiceprintsFileTest {

    @Test
    void keptReadBackAndPrivate(@TempDir Path dir) throws Exception {
        VoiceprintsFile f = new VoiceprintsFile(dir.resolve("marvin/voiceprints.json"));
        assertThat(f.load()).isEmpty();
        var seb = new Voiceprint("Sébastien", List.of(0.6, 0.8), "wespeaker-ecapa512", "2026-10-04T10:00:00", "computer");
        var ana = new Voiceprint("Aashma", List.of(-0.5, 0.25), "wespeaker-ecapa512", "2026-10-05T19:00:00", "robot");
        f.save(List.of(seb, ana));
        assertThat(f.load()).containsExactly(seb, ana);
        if (Files.getFileStore(f.path()).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(f.path()))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
        f.save(List.of(ana));
        assertThat(f.load()).containsExactly(ana);
        assertThat(Files.readString(f.path())).doesNotContain("Sébastien");
    }

    @Test
    void aDamagedFileOrEntryIsIgnored(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("voiceprints.json");
        Files.writeString(p, "{not json");
        assertThat(new VoiceprintsFile(p).load()).isEmpty();
        Files.writeString(p, "{\"voiceprints\": [{\"name\": \"A\", \"embedding\": [1, \"x\"]}, {\"name\": \"\", "
                + "\"embedding\": [1]}, {\"name\": \"B\", \"embedding\": [0.5, 1]}]}");
        assertThat(new VoiceprintsFile(p).load()).extracting(Voiceprint::name).containsExactly("B");
    }
}
