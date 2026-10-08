package ww86.hocon_fmt.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** The JDK counterpart of the cats and ZIO adapters' identity-preserving replacement. */
final class AtomicFile {
    private AtomicFile() {}

    record Identity(int owner, int group, int mode) {}

    static void write(Path target, String text) throws IOException {
        Path parent = target.getParent();
        Identity identity = readIdentity(target);
        if (parent != null && identity != null && Files.isWritable(target) && Files.isWritable(parent)) {
            Path staged = Files.createTempFile(parent, ".hocon-fmt-", ".tmp");
            try {
                Files.writeString(staged, text);
                if (keepIdentity(staged, identity)) {
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            } finally {
                Files.deleteIfExists(staged);
            }
        }
        // This retains the inode when replacement cannot prove it will keep the identity.
        // Like the effect adapters, the fallback cannot offer crash-atomicity.
        Files.writeString(target, text);
    }

    static @org.jspecify.annotations.Nullable Identity readIdentity(Path path) {
        try {
            var attributes = Files.readAttributes(path, "unix:uid,gid,mode");
            if (attributes.get("uid") instanceof Integer owner
                    && attributes.get("gid") instanceof Integer group
                    && attributes.get("mode") instanceof Integer mode) {
                return new Identity(owner, group, mode & 0xfff);
            }
        } catch (IOException | RuntimeException unavailable) {
            // Filesystems without the unix view and inaccessible metadata require the fallback.
        }
        return null;
    }

    static boolean keepIdentity(Path staged, Identity identity) {
        try {
            // chown can clear setuid/setgid; apply every mode bit after owner and group.
            Files.setAttribute(staged, "unix:uid", identity.owner());
            Files.setAttribute(staged, "unix:gid", identity.group());
            Files.setAttribute(staged, "unix:mode", identity.mode());
            return identity.equals(readIdentity(staged));
        } catch (IOException | RuntimeException denied) {
            return false;
        }
    }
}
