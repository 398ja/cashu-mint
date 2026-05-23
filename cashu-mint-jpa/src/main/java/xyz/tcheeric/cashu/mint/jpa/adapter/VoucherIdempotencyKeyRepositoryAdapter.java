package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdempotencyKeyEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdempotencyKeyId;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherIdempotencyKeyJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIdempotencyKey;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIdempotencyKeyRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * Spec 003 — JPA-backed implementation of
 * {@link VoucherIdempotencyKeyRepository}.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class VoucherIdempotencyKeyRepositoryAdapter implements VoucherIdempotencyKeyRepository {

    private final VoucherIdempotencyKeyJpaRepository jpa;

    @Override
    public Optional<VoucherIdempotencyKey> findByKey(String idempotencyKey, String principalId) {
        return jpa.findById(new VoucherIdempotencyKeyId(idempotencyKey, principalId))
                .map(e -> (VoucherIdempotencyKey) e);
    }

    @Override
    public VoucherIdempotencyKey save(VoucherIdempotencyKey row) {
        VoucherIdempotencyKeyEntity entity = (row instanceof VoucherIdempotencyKeyEntity e)
                ? e
                : toEntity(row);
        return jpa.save(entity);
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return jpa.deleteExpiredBefore(cutoff);
    }

    private VoucherIdempotencyKeyEntity toEntity(VoucherIdempotencyKey r) {
        VoucherIdempotencyKeyEntity e = new VoucherIdempotencyKeyEntity();
        e.setId(new VoucherIdempotencyKeyId(r.idempotencyKey(), r.principalId()));
        e.setRequestHash(r.requestHash());
        e.setResponseStatus(r.responseStatus());
        e.setResponseBodyJson(r.responseBodyJson());
        e.setExpiresAt(r.expiresAt());
        return e;
    }
}
