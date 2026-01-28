package xyz.tcheeric.cashu.mint.rest.event;

import org.springframework.context.ApplicationEvent;

/**
 * Event fired when a proof's state changes.
 *
 * <p>Used for NUT-17 WebSocket notifications when proofs transition
 * between UNSPENT, PENDING, and SPENT states.
 */
public class ProofStateChangeEvent extends ApplicationEvent {

    private final String y;
    private final String state;
    private final String witness;

    /**
     * Creates a new proof state change event.
     *
     * @param source the event source
     * @param y the proof's Y value (compressed point of secret)
     * @param state the new state (UNSPENT, PENDING, SPENT)
     * @param witness optional witness data for P2PK
     */
    public ProofStateChangeEvent(Object source, String y, String state, String witness) {
        super(source);
        this.y = y;
        this.state = state;
        this.witness = witness;
    }

    public String getY() {
        return y;
    }

    public String getState() {
        return state;
    }

    public String getWitness() {
        return witness;
    }
}
