package com.crmforlogistics.messagecenter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpenSslImapClient {
    private static final Pattern EXISTS = Pattern.compile("\\*\\s+(\\d+)\\s+EXISTS", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_SIZE = Pattern.compile("\\{(\\d+)}\\s*$");
    private static final String CIPHER = "AES256-GCM-SHA384";

    private final Config config;

    OpenSslImapClient(Config config) {
        this.config = config;
    }

    List<MimeMessage> fetchLatest(String folderName, int limit) throws Exception {
        if (folderName == null || folderName.isBlank()) {
            return List.of();
        }
        Process process = startOpenSsl();
        try {
            InputStream input = process.getInputStream();
            BufferedOutputStream output = new BufferedOutputStream(process.getOutputStream());
            readLine(input);
            command(input, output, "A001 CAPABILITY");
            String login = command(input, output, "A002 LOGIN \"" + escape(config.value("IMAP_USERNAME", "")) + "\" \""
                    + escape(config.value("IMAP_PASSWORD", "")) + "\"");
            if (!login.contains("A002 OK")) {
                throw new IllegalStateException(cleanLoginFailure(login));
            }
            String select = command(input, output, "A003 SELECT \"" + escape(folderName) + "\"");
            if (!select.contains("A003 OK")) {
                return List.of();
            }
            int count = messageCount(select);
            if (count <= 0) {
                return List.of();
            }
            int start = Math.max(1, count - Math.max(1, limit) + 1);
            writeLine(output, "A004 FETCH " + start + ":" + count + " (RFC822)");
            List<byte[]> rawMessages = extractLiterals(input, "A004");
            command(input, output, "A005 LOGOUT");
            List<MimeMessage> messages = new ArrayList<>();
            for (byte[] rawMessage : rawMessages) {
                messages.add(new MimeMessage(Session.getInstance(new Properties()), new java.io.ByteArrayInputStream(rawMessage)));
            }
            return messages;
        } finally {
            process.destroy();
            process.waitFor(5, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static List<byte[]> extractLiterals(InputStream input, String tag) throws IOException {
        List<byte[]> messages = new ArrayList<>();
        String line;
        while ((line = readLine(input)) != null) {
            Matcher matcher = LITERAL_SIZE.matcher(line);
            if (matcher.find()) {
                int size = Integer.parseInt(matcher.group(1));
                messages.add(readExact(input, size));
                readLine(input);
                continue;
            }
            if (line.startsWith(tag + " ")) {
                break;
            }
        }
        return messages;
    }

    private Process startOpenSsl() throws IOException {
        List<String> command = List.of(
                config.value("OPENSSL_BIN", "/usr/bin/openssl"),
                "s_client",
                "-quiet",
                "-crlf",
                "-servername", config.value("IMAP_HOST", ""),
                "-connect", config.value("IMAP_HOST", "") + ":" + config.value("IMAP_PORT", "993"),
                "-tls1_2",
                "-cipher", CIPHER
        );
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static String command(InputStream input, BufferedOutputStream output, String command) throws IOException {
        String tag = command.split(" ", 2)[0];
        writeLine(output, command);
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = readLine(input)) != null) {
            if (response.length() > 0) {
                response.append(' ');
            }
            response.append(line);
            if (line.startsWith(tag + " ")) {
                break;
            }
        }
        return response.toString();
    }

    private static void writeLine(BufferedOutputStream output, String command) throws IOException {
        output.write(command.getBytes(StandardCharsets.US_ASCII));
        output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = buffer.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            buffer.write(current);
            previous = current;
        }
        if (buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private static byte[] readExact(InputStream input, int size) throws IOException {
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) {
            throw new IOException("IMAP literal ended early");
        }
        return bytes;
    }

    private static int messageCount(String selectResponse) {
        Matcher matcher = EXISTS.matcher(selectResponse);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String cleanLoginFailure(String response) {
        return response == null || response.isBlank()
                ? "IMAP LOGIN failed"
                : response.replaceAll("\\s+", " ").trim();
    }
}
