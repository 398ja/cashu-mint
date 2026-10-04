package xyz.tcheeric.cashu.mint.proto.voucher;

/**
 * The voucher issuer binding the running mint configured, for the swap tasks to find
 * (cashu-mint#527).
 *
 * <p>The swap tasks are built with {@code new} inside the static NUT helpers, so they cannot take
 * a Spring bean and have to look the binding up. It lives here rather than in
 * {@code MintIntegrityContext} for two reasons:
 * <ul>
 *   <li>{@code MintIntegrityContext.clear()} runs on the JPA installer's shutdown hook. A binding
 *       kept there would fall back to {@code log} whenever that ran, silently relaxing a mint
 *       configured to {@code enforce}. Nothing clears this holder but {@link #install} itself.</li>
 *   <li>It keeps {@code ports} free of a dependency on this package, which would otherwise close
 *       a {@code ports -> voucher -> metrics -> ports} cycle.</li>
 * </ul>
 */
public final class InstalledVoucherIssuerBinding {

    private static volatile VoucherIssuerBinding installed;

    private InstalledVoucherIssuerBinding() {
    }

    /**
     * Installs the configured binding, replacing any previous one.
     *
     * @param binding the binding to apply from now on; null restores the unconfigured default
     */
    public static void install(VoucherIssuerBinding binding) {
        installed = binding;
    }

    /**
     * The installed binding, or {@link VoucherIssuerBinding#unconfigured()} when none is: the
     * configuration property's own default, {@code log} with nothing trusted. Never null.
     *
     * @return the binding to apply
     */
    public static VoucherIssuerBinding current() {
        VoucherIssuerBinding binding = installed;
        return binding != null ? binding : VoucherIssuerBinding.unconfigured();
    }
}
