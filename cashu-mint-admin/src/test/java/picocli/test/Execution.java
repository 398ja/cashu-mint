package picocli.test;

import picocli.CommandLine;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class Execution {

    protected int exitCode;
    protected String[] args;

    public static final class Builder {
        private final Supplier<CommandLine> commandLineSupplier;
        private boolean customizeOut;
        private boolean customizeErr;

        private Builder(final Supplier<CommandLine> commandLineSupplier) {
            this.commandLineSupplier = Objects.requireNonNull(commandLineSupplier, "commandLineSupplier");
        }

        public Builder customizeOut(final boolean customizeOut) {
            this.customizeOut = customizeOut;
            return this;
        }

        public Builder customizeErr(final boolean customizeErr) {
            this.customizeErr = customizeErr;
            return this;
        }

        public Execution execute(final String... arguments) {
            final Execution execution = new CommandLineExecution(commandLineSupplier, arguments.clone())
                .setCustomizeOut(customizeOut)
                .setCustomizeErr(customizeErr);
            execution.execute();
            return execution;
        }
    }

    protected abstract void execute();

    protected abstract boolean isAlive();

    protected abstract int getExitCode();

    public abstract String getSystemOutString();

    public abstract String getSystemErrString();

    public static Builder builder(final Supplier<CommandLine> commandLineSupplier) {
        return new Builder(commandLineSupplier);
    }

    public Execution assertExitCode(final int expectedExitCode) {
        assertEquals(expectedExitCode, getExitCode());
        return this;
    }

    public Execution assertSystemOut(final String expectedSystemOut) {
        assertEquals(String.format(expectedSystemOut), getSystemOutString());
        return this;
    }

    public Execution assertSystemErr(final String expectedSystemErr) {
        assertEquals(String.format(expectedSystemErr), getSystemErrString());
        return this;
    }
}
