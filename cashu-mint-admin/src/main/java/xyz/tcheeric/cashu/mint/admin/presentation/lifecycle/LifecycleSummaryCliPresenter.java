package xyz.tcheeric.cashu.mint.admin.presentation.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

import xyz.tcheeric.cashu.mint.admin.cli.io.OutputFormat;

/**
 * Delegates lifecycle summary rendering to the appropriate presenter based on CLI output format.
 */
public final class LifecycleSummaryCliPresenter {

    private final LifecycleSummaryJsonPresenter jsonPresenter;
    private final LifecycleSummaryTablePresenter tablePresenter;

    public LifecycleSummaryCliPresenter(final ObjectMapper mapper) {
        this(new LifecycleSummaryJsonPresenter(mapper), new LifecycleSummaryTablePresenter(mapper));
    }

    public LifecycleSummaryCliPresenter(final LifecycleSummaryJsonPresenter jsonPresenter,
                                        final LifecycleSummaryTablePresenter tablePresenter) {
        this.jsonPresenter = Objects.requireNonNull(jsonPresenter, "jsonPresenter");
        this.tablePresenter = Objects.requireNonNull(tablePresenter, "tablePresenter");
    }

    public String present(final LifecycleSummary summary, final OutputFormat format) {
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case JSON -> jsonPresenter.present(summary);
            case TABLE -> tablePresenter.present(summary);
        };
    }
}
