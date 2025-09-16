package xyz.tcheeric.cashu.mint.admin.cli.command;

import picocli.CommandLine.Option;

import xyz.tcheeric.cashu.mint.admin.cli.io.CommandPayloadMapper;
import xyz.tcheeric.cashu.mint.admin.cli.io.InputFormat;
import xyz.tcheeric.cashu.mint.admin.cli.io.OutputFormat;
import xyz.tcheeric.cashu.mint.admin.cli.io.PayloadMappingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class CommandIOOptions {

    @Option(names = {"-f", "--input-format"},
            description = "Payload format: \u003c${COMPLETION-CANDIDATES}\u003e",
            defaultValue = "JSON")
    private InputFormat inputFormat = InputFormat.JSON;

    @Option(names = {"-p", "--payload"},
            description = "Inline payload in the selected input format.")
    private String inlinePayload;

    @Option(names = {"-F", "--payload-file"},
            description = "Path to a file containing the payload in the selected input format.")
    private Path payloadFile;

    @Option(names = {"-o", "--output-format"},
            description = "Response render format: \u003c${COMPLETION-CANDIDATES}\u003e",
            defaultValue = "TABLE")
    private OutputFormat outputFormat = OutputFormat.TABLE;

    InputFormat inputFormat() {
        return inputFormat;
    }

    OutputFormat outputFormat() {
        return outputFormat;
    }

    <T> Optional<T> readPayload(final CommandPayloadMapper mapper, final Class<T> targetType) {
        return resolvePayload().map(content -> mapper.read(content, inputFormat, targetType));
    }

    private Optional<String> resolvePayload() {
        if (inlinePayload != null && payloadFile != null) {
            throw new PayloadMappingException("Specify either --payload or --payload-file, not both.");
        }
        if (inlinePayload != null) {
            return Optional.of(inlinePayload);
        }
        if (payloadFile != null) {
            try {
                return Optional.of(Files.readString(payloadFile));
            } catch (final IOException ex) {
                throw new PayloadMappingException("Failed to read payload file '%s'".formatted(payloadFile), ex);
            }
        }
        return Optional.empty();
    }
}
