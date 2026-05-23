package xyz.tcheeric.cashu.mint.rest.controller.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaTransition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spec 002 US3 — admin-only saga query + operator reconciliation endpoint.
 * Loaded only when a {@link MeltSagaRepository} bean is present (i.e.,
 * {@code cashu.mint.jpa.enabled=true}).
 *
 * <p><b>Security note:</b> this controller is intended for internal use
 * only. A future iteration wires it behind Spring Security with the
 * service-account authentication used by {@code cashu-mint-admin-rest}.
 * Until then, deployments MUST protect this endpoint at the network
 * layer (e.g. listen on an internal interface, allow-list specific IPs,
 * front it with a reverse proxy that adds auth).
 */
@Slf4j
@RestController
@ConditionalOnBean(MeltSagaRepository.class)
@RequestMapping("/admin/melt-saga")
@RequiredArgsConstructor
public class MeltSagaAdminController {

    private final MeltSagaRepository sagaRepository;

    @GetMapping("/by-id/{meltSagaId}")
    public ResponseEntity<MeltSagaResponse> getById(@PathVariable("meltSagaId") String meltSagaId) {
        return sagaRepository.findById(meltSagaId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-quote/{quoteId}")
    public ResponseEntity<MeltSagaResponse> getByQuote(@PathVariable("quoteId") String quoteId) {
        return sagaRepository.findByQuoteId(quoteId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Operator-initiated resolution of a stuck saga. Appends a new
     * {@code mark_resolved} transition; does not overwrite history.
     *
     * <p>The {@code actor} field comes from the request body so the
     * append-only timeline records who took the action.
     */
    @PostMapping("/{meltSagaId}/mark-resolved")
    public ResponseEntity<MeltSagaResponse> markResolved(
            @PathVariable("meltSagaId") String meltSagaId,
            @RequestBody Map<String, String> body) {
        Optional<MeltSaga> sagaOpt = sagaRepository.findById(meltSagaId);
        if (sagaOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        MeltSaga saga = sagaOpt.get();
        String actor = body.getOrDefault("actor", "operator:unknown");
        String reason = body.getOrDefault("reason", "marked resolved by operator");

        log.warn("[melt-saga][operator] mark_resolved saga_id={} quote_id={} from_state={} actor={}",
                meltSagaId, saga.quoteId(), saga.currentState(), actor);

        // Spec 002 § operator reconciliation contract: resolution is an
        // append-only transition — we do NOT overwrite current_state from
        // here. The resolution annotates the timeline; downstream tooling
        // decides the final state (e.g. an operator-driven CAS into
        // COMPLETED or FAILED via a separate endpoint that lands once
        // proof-side refund support exists).
        sagaRepository.recordTransition(meltSagaId, saga.currentState(),
                saga.currentState(), reason, actor);
        return ResponseEntity.ok(toResponse(saga));
    }

    private MeltSagaResponse toResponse(MeltSaga saga) {
        List<MeltSagaTransition> transitions = sagaRepository.transitionsFor(saga.meltSagaId());
        return MeltSagaResponse.from(saga, transitions);
    }
}
