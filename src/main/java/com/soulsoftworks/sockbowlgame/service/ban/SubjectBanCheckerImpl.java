package com.soulsoftworks.sockbowlgame.service.ban;

import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBanChecker;
import com.soulsoftworks.sockbowlgame.service.BanService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The real {@link SubjectBanChecker} (D8, AB-01) used by the request guard
 * filter: answers from {@link BanService#findActiveBanCached}, i.e. the
 * {@link BanStatusCache} over the Redis mirror, so it costs no Postgres query
 * per request and fails open. With auth off this bean is absent and
 * {@code LimitsFallbackAutoConfiguration}'s no-op stays.
 */
@Component
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class SubjectBanCheckerImpl implements SubjectBanChecker {

    private final BanService banService;

    public SubjectBanCheckerImpl(BanService banService) {
        this.banService = banService;
    }

    @Override
    public Optional<SubjectBan> findActiveBan(String sub) {
        return banService.findActiveBanCached(sub);
    }
}
