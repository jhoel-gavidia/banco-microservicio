package com.banco.transfer_service.transfer.receipt.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReceiptTempDirectoryInitializer {

    private final ReceiptS3Properties properties;

    @PostConstruct
    public void initialize() {
        ReceiptS3Properties.Download download = properties.getDownload();
        Path baseDir = download.getTempDir().toAbsolutePath().normalize();
        Path receiptsDir = baseDir.resolve("receipts").normalize();
        if (!receiptsDir.startsWith(baseDir)) {
            throw new IllegalStateException("Invalid transfer.receipts.download.temp-dir configuration");
        }

        try {
            Files.createDirectories(receiptsDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create receipt temp directory: " + receiptsDir, ex);
        }

        harden(receiptsDir, download.isAllowUnverifiedTempAcl());
    }

    void harden(Path receiptsDir, boolean allowUnverified) {
        if (tryPosix(receiptsDir)) {
            return;
        }
        if (tryWindowsAcl(receiptsDir)) {
            return;
        }
        if (allowUnverified) {
            log.warn("Receipt temp directory ACL could not be verified; proceeding because allow-unverified-temp-acl=true: {}",
                    receiptsDir);
            return;
        }
        throw new IllegalStateException(
                "Could not harden receipt temp directory ACL: " + receiptsDir
                        + ". Set transfer.receipts.download.allow-unverified-temp-acl=true only for local dev.");
    }

    private boolean tryPosix(Path receiptsDir) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
            Files.setPosixFilePermissions(receiptsDir, perms);
            return true;
        } catch (UnsupportedOperationException ignored) {
            return false;
        } catch (IOException ex) {
            log.warn("Failed to set POSIX permissions on receipt temp dir {}", receiptsDir, ex);
            return false;
        }
    }

    private boolean tryWindowsAcl(Path receiptsDir) {
        AclFileAttributeView view = Files.getFileAttributeView(receiptsDir, AclFileAttributeView.class);
        if (view == null) {
            return false;
        }
        try {
            UserPrincipal owner = view.getOwner();
            var lookup = receiptsDir.getFileSystem().getUserPrincipalLookupService();
            GroupPrincipal admins = lookup.lookupPrincipalByGroupName("Administrators");
            GroupPrincipal system;
            try {
                system = lookup.lookupPrincipalByGroupName("SYSTEM");
            } catch (IOException ex) {
                system = lookup.lookupPrincipalByGroupName("SISTEMA");
            }

            Set<AclEntryPermission> full = EnumSet.allOf(AclEntryPermission.class);
            List<AclEntry> acl = List.of(
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(full).build(),
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(admins).setPermissions(full).build(),
                    AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system).setPermissions(full).build()
            );
            view.setAcl(acl);

            List<AclEntry> current = view.getAcl();
            validateAcl(current, owner.getName(), admins.getName(), system.getName());
            return true;
        } catch (IOException | UnsupportedOperationException ex) {
            log.warn("Failed to set Windows ACL on receipt temp dir {}", receiptsDir, ex);
            return false;
        }
    }

    private static void validateAcl(List<AclEntry> acl, String owner, String admins, String system) throws IOException {
        for (AclEntry entry : acl) {
            if (entry.type() != AclEntryType.ALLOW) {
                throw new IOException("Unexpected ACL entry type: " + entry.type());
            }
            String name = entry.principal().getName().toLowerCase(Locale.ROOT);
            boolean allowed = name.equals(owner.toLowerCase(Locale.ROOT))
                    || name.equalsIgnoreCase(admins)
                    || name.equalsIgnoreCase(system)
                    || name.endsWith("\\administrators")
                    || name.endsWith("\\system")
                    || name.endsWith("\\sistema")
                    || name.equals("builtin\\administrators")
                    || name.equals("nt authority\\system");
            if (!allowed) {
                throw new IOException("Unexpected ACL principal: " + entry.principal().getName());
            }
        }
    }
}
