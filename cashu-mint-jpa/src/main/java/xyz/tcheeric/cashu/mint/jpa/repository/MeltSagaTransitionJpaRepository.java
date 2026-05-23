package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionId;

import java.util.List;

/**
 * Append-only timeline for melt-saga transitions. No update queries; the
 * spec 002 data-model § MeltSagaTransition makes the append-only contract
 * explicit and the admin endpoint (US3) reads through this repository.
 */
@Repository
public interface MeltSagaTransitionJpaRepository
        extends JpaRepository<MeltSagaTransitionEntity, MeltSagaTransitionId> {

    @Query("SELECT t FROM MeltSagaTransitionEntity t "
            + "WHERE t.meltSagaId = :id ORDER BY t.seq ASC")
    List<MeltSagaTransitionEntity> findTimeline(@Param("id") String meltSagaId);

    @Query("SELECT COALESCE(MAX(t.seq), 0) FROM MeltSagaTransitionEntity t "
            + "WHERE t.meltSagaId = :id")
    int maxSeq(@Param("id") String meltSagaId);
}
