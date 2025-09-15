package xyz.tcheeric.cashu.mint.admin.cli.io;

/**
 * Strategy for rendering command responses.
 */
public interface ResponseRenderer {

    OutputFormat format();

    String render(Object response);
}
