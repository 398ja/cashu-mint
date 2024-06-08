package cashu.mint.rest.entity.repository;

import cashu.mint.rest.entity.MintQuote;
import lombok.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface MintQuoteRepository extends JpaRepository<MintQuote, Integer> {
    @Query("select m from MintQuote m where m.quote = ?1")
    Optional<MintQuote> findByQuote(String quote);

    @Override
    @NonNull
    Optional<MintQuote> findById(@NonNull Integer integer);
}