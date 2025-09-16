package picocli.test;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

final class CommandLineExecution extends Execution {

    private final Supplier<CommandLine> commandLineSupplier;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private StringWriter outWriter;
    private StringWriter errWriter;
    private boolean customizeOut;
    private boolean customizeErr;
    private boolean alive;

    CommandLineExecution(final Supplier<CommandLine> commandLineSupplier, final String[] args) {
        this.commandLineSupplier = Objects.requireNonNull(commandLineSupplier, "commandLineSupplier");
        this.args = Objects.requireNonNull(args, "args");
    }

    CommandLineExecution setCustomizeOut(final boolean customizeOut) {
        this.customizeOut = customizeOut;
        return this;
    }

    CommandLineExecution setCustomizeErr(final boolean customizeErr) {
        this.customizeErr = customizeErr;
        return this;
    }

    @Override
    protected void execute() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        final PrintStream oldOut = System.out;
        final PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            final CommandLine commandLine = commandLineSupplier.get();
            if (customizeOut) {
                outWriter = new StringWriter();
                commandLine.setOut(new PrintWriter(outWriter));
            }
            if (customizeErr) {
                errWriter = new StringWriter();
                commandLine.setErr(new PrintWriter(errWriter));
            }
            alive = true;
            exitCode = commandLine.execute(args);
        } finally {
            alive = false;
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Override
    protected boolean isAlive() {
        return alive;
    }

    @Override
    protected int getExitCode() {
        return exitCode;
    }

    @Override
    public String getSystemOutString() {
        if (outWriter != null) {
            return outWriter.toString();
        }
        return out.toString();
    }

    @Override
    public String getSystemErrString() {
        if (errWriter != null) {
            return errWriter.toString();
        }
        return err.toString();
    }
}
