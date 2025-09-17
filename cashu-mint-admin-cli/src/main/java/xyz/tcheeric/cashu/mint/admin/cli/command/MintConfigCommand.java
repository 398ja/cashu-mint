package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Top-level command group for configuration workflow operations.
 */
@Command(name = "config",
         description = "Manage configuration workflows for a mint.",
         mixinStandardHelpOptions = true)
public final class MintConfigCommand implements Runnable {

    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
