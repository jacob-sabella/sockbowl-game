package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.repository.BanRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BanServiceTest {

    @Test
    void isBannedReflectsActiveBanLookup() {
        BanRepository repo = mock(BanRepository.class);
        when(repo.findActiveBan(eq("kc-1"), any(Instant.class)))
                .thenReturn(Optional.of(BanRecord.builder().bannedKeycloakId("kc-1").build()));
        when(repo.findActiveBan(eq("kc-2"), any(Instant.class)))
                .thenReturn(Optional.empty());

        BanService service = new BanService(repo);

        assertTrue(service.isBanned("kc-1"));
        assertFalse(service.isBanned("kc-2"));
        assertFalse(service.isBanned(null));
        assertFalse(service.isBanned(""));
    }

    @Test
    void createBanPersistsRecordWithMetadata() {
        BanRepository repo = mock(BanRepository.class);
        when(repo.save(any(BanRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        BanService service = new BanService(repo);
        Instant expiry = Instant.now().plusSeconds(3600);
        BanRecord ban = service.createBan("kc-9", "spamming", "kc-admin", expiry);

        assertEquals("kc-9", ban.getBannedKeycloakId());
        assertEquals("spamming", ban.getReason());
        assertEquals("kc-admin", ban.getBannedBy());
        assertEquals(expiry, ban.getExpiresAt());
        verify(repo).save(any(BanRecord.class));
    }

    @Test
    void removeBanReturnsFalseWhenMissing() {
        BanRepository repo = mock(BanRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.existsById(id)).thenReturn(false);

        BanService service = new BanService(repo);

        assertFalse(service.removeBan(id));
        verify(repo, never()).deleteById(any());
    }

    @Test
    void removeBanDeletesWhenPresent() {
        BanRepository repo = mock(BanRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.existsById(id)).thenReturn(true);

        BanService service = new BanService(repo);

        assertTrue(service.removeBan(id));
        verify(repo).deleteById(id);
    }
}
