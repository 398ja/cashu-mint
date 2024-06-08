package cashu.mint.rest.entity.repository;

import cashu.mint.rest.entity.MeltQuote;
import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MeltQuoteRepository extends JpaRepository<MeltQuote, Integer> {
    @Query("select m from MeltQuote m where m.quote = ?1")
    Optional<MeltQuote> findByQuote(String quote);

    @Override
    @NonNull
    Optional<MeltQuote> findById(@NonNull Integer integer);
}