package com.qa.utils;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.*;
import jakarta.mail.internet.*;

import java.io.File;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class EmailManager {

    // ---- Config keys (avoid magic strings) ----
    private static final String KEY_MAIL_USERNAME   = SecureConfig.value(SecKeys.MAIL_USERNAME);
    private static final String KEY_MAIL_PASSWORD   = SecureConfig.value(SecKeys.MAIL_PASSWORD);
    private static final String KEY_MAIL_PROVIDER   = SecureConfig.value(SecKeys.MAIL_PROVIDER);
    private static final String KEY_MAIL_TO         = SecureConfig.value(SecKeys.MAIL_TO);
    private static final String KEY_MAIL_CC         = SecureConfig.value(SecKeys.EMAIL_CC);      // kept as in your code
    private static final String KEY_OTP_SUBJECT     = SecureConfig.value(SecKeys.OTP_SUBJECT);
    private static final String KEY_OTP_SENDER      = SecureConfig.value(SecKeys.OTP_SENDEREMAIL);
    private static final String KEY_OTP_TAIL_WINDOW = "60";

    // ---- Providers / SMTP ----
    private static final String PROVIDER_GMAIL    = "gmail";
    private static final String PROVIDER_OUTLOOK  = "outlook";
    private static final String SMTP_HOST_GMAIL   = "smtp.gmail.com";
    private static final String SMTP_HOST_O365    = "smtp.office365.com";
    private static final String SMTP_PORT_TLS     = "587";

    // ---- IMAP / Store ----
    private static final String IMAP_HOST_GMAIL       = "imap.gmail.com";
    private static final int    IMAP_PORT_SSL         = 993;
    private static final String PROTOCOL_IMAPS        = "imaps";
    private static final String PROP_STORE_PROTOCOL   = "mail.store.protocol";
    private static final String PROP_IMAPS_SSL_ENABLE = "mail.imaps.ssl.enable";

    // ---- OTP defaults ----
    private static final int  OTP_TIMEOUT_MINUTES = 2;
    private static final int  OTP_TAIL_DEFAULT    = 30;
    private static final int  OTP_TAIL_MIN        = 5;
    private static final long RETRY_SLEEP_MS      = 5_000L;

    // Small holder for SMTP config
    private record SmtpConfig(String host, String port) {}

    /** Holder for IMAP resources with proper try-with-resources support */
    private static final class ImapContext implements AutoCloseable {
        private final Store store;
        private final Folder inbox;
        private boolean expungeOnClose = false; // default for read flow

        ImapContext(Store store, Folder inbox) {
            this.store = store;
            this.inbox = inbox;
        }

        Folder inbox() { return inbox; }

        /** For delete flow: request expunge on close (no param since it's always set to true by callers). */
        void enableExpungeOnClose() { this.expungeOnClose = true; }

        @Override
        public void close() {
            try {
                if (inbox != null && inbox.isOpen()) {
                    inbox.close(expungeOnClose);
                }
            } catch (MessagingException e) {
                TestUtils.log().fatal("Failed to close IMAP inbox: {}", e.getMessage());
            } finally {
                try {
                    if (store != null && store.isConnected()) {
                        store.close();
                    }
                } catch (MessagingException e) {
                    TestUtils.log().fatal("Failed to close IMAP store: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Sends an email with the given subject, body and optional file attachments.
     *
     * @param subject     Subject of the email.
     * @param body        HTML content of the email body.
     * @param attachments Array of files to attach; can be null or empty.
     */
    public static void sendEmailWithAttachments(String subject, String body, File[] attachments) {
        try {
            // 1) Read config
            String provider  = KEY_MAIL_PROVIDER.toLowerCase(Locale.ROOT);
            List<String> toList = splitEmails(KEY_MAIL_TO);
            List<String> ccList = splitEmails(KEY_MAIL_CC);

            // 2) Validate essentials
            if (KEY_MAIL_USERNAME == null || KEY_MAIL_PASSWORD == null || toList.isEmpty()) {
                TestUtils.log().error("Missing email configuration. Check {}, {} and {}.", KEY_MAIL_USERNAME, KEY_MAIL_PASSWORD, KEY_MAIL_TO);
                return;
            }

            // 3) Resolve SMTP & session
            SmtpConfig smtp = resolveSmtp(provider);
            Session session = buildSmtpSession(smtp.host(), smtp.port(), KEY_MAIL_USERNAME, KEY_MAIL_PASSWORD);

            // 4) Build message
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(KEY_MAIL_USERNAME));
            message.setRecipients(Message.RecipientType.TO, toAddresses(toList));
            InternetAddress[] ccAddresses = toAddresses(ccList);
            if (ccAddresses.length > 0) {
                message.setRecipients(Message.RecipientType.CC, ccAddresses);
            }
            message.setSubject(subject);

            // 5) Body + attachments
            Multipart multipart = new MimeMultipart();

            MimeBodyPart bodyPart = new MimeBodyPart();
            bodyPart.setContent(body, "text/html");
            multipart.addBodyPart(bodyPart);

            addAttachments(multipart, attachments);

            message.setContent(multipart);

            // 6) Send
            Transport.send(message);

            if (TestUtils.log().isInfoEnabled()) {
                TestUtils.log().info("Email sent successfully via {}", provider.toUpperCase(Locale.ROOT));
            }
        } catch (Exception e) {
            TestUtils.log().fatal("Failed to send email: {}", e.toString());
        }
    }

    /**
     * Reads the OTP from the inbox by looking for an email matching subject and sender.
     * Waits up to {@value #OTP_TIMEOUT_MINUTES} minutes retrying every {@value #RETRY_SLEEP_MS} ms.
     * Refactored to reduce cognitive complexity, define a dedicated exception, and handle interruption properly.
     */
    public static String readOtpFromInbox() {
        String subjectLine = KEY_OTP_SUBJECT;
        String senderEmail = KEY_OTP_SENDER;

        int  tailWindow    = resolveTailWindow();
        long deadline      = System.currentTimeMillis() + (OTP_TIMEOUT_MINUTES * 60_000L);
        Date testStartTime = new Date();

        Properties props = new Properties();
        props.put(PROP_STORE_PROTOCOL, PROTOCOL_IMAPS);
        props.put(PROP_IMAPS_SSL_ENABLE, "true");

        try (ImapContext ctx = openImapContext(props, KEY_MAIL_USERNAME, KEY_MAIL_PASSWORD)) {
            return scanForOtpUntilTimeout(ctx.inbox(), tailWindow, testStartTime, subjectLine, senderEmail, deadline);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new OtpReadException("Thread interrupted while waiting for OTP.", ie);
        } catch (MessagingException me) {
            throw new OtpReadException("Mail error while reading OTP: " + me.getMessage(), me);
        }
    }

    // ---- readOtpFromInbox helpers ----

    private static ImapContext openImapContext(Properties props, String user, String pass)
            throws MessagingException {
        TestUtils.log().info("Connecting to Gmail IMAP server → host={}, port={}, user={}", IMAP_HOST_GMAIL, IMAP_PORT_SSL, user);
        Session session = Session.getInstance(props);
        Store store = session.getStore(PROTOCOL_IMAPS);
        store.connect(IMAP_HOST_GMAIL, IMAP_PORT_SSL, user, pass);
        TestUtils.log().info("Connected to Gmail successfully");

        Folder inbox = store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE); // Required to mark messages as read
        TestUtils.log().info("INBOX opened. Total messages={}, Unread={}", inbox.getMessageCount(), inbox.getUnreadMessageCount());
        return new ImapContext(store, inbox);
    }

    private static String scanForOtpUntilTimeout(
            Folder inbox,
            int tailWindow,
            Date testStartTime,
            String subjectLine,
            String senderEmail,
            long deadline
    ) throws MessagingException, InterruptedException {

        while (System.currentTimeMillis() < deadline) {
            int total = inbox.getMessageCount();
            if (total <= 0) {
                TestUtils.log().info("Mailbox empty. Will retry...");
                waitBeforeRetry(deadline);
                continue;
            }

            int start = Math.max(1, total - tailWindow + 1);
            Message[] messages = fetchRecentMessages(inbox, start, total);

            TestUtils.log().info("Scanning last {} message(s) (range {}-{}) for OTP...", messages.length, start, total);

            Optional<String> maybeOtp = tryExtractOtpFromMessages(messages, testStartTime, subjectLine, senderEmail);
            if (maybeOtp.isPresent()) {
                return maybeOtp.get();
            }

            TestUtils.log().info("OTP not found yet. Will retry...");
            waitBeforeRetry(deadline);
        }

        throw new OtpReadException("OTP email not received within " + OTP_TIMEOUT_MINUTES + " minutes.");
    }

    private static Message[] fetchRecentMessages(Folder inbox, int start, int end) throws MessagingException {
        if (end < start) {
            return new Message[0];
        }
        Message[] messages = inbox.getMessages(start, end);

        // Prefetch envelope + flags to reduce per-message round trips
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        inbox.fetch(messages, fp);
        return messages;
    }

    /**
     * Reduced-complexity scanner: newest → oldest; delegates checks to tiny helpers.
     */
    private static Optional<String> tryExtractOtpFromMessages(
            Message[] messages,
            Date testStartTime,
            String subjectLine,
            String senderEmail
    ) {
        final String expectedSubject = subjectLine == null ? "" : subjectLine.trim();
        final String senderNeedle    = senderEmail == null ? "" : senderEmail.toLowerCase(Locale.ROOT);

        for (int i = messages.length - 1; i >= 0; i--) {
            try {
                Optional<String> otp = maybeOtpFromMessage(messages[i], testStartTime, expectedSubject, senderNeedle);
                if (otp.isPresent()) {
                    TestUtils.log().info("OTP extracted and message marked as read: {}", otp.get());
                    TestUtils.log().info("OTP read time: {}", LocalTime.now());
                    return otp;
                }
            } catch (EmailContentException ece) {
                TestUtils.log().warn("Failed to parse message content: {}", ece.getMessage());
            } catch (MessagingException me) {
                TestUtils.log().warn("Message access error while scanning for OTP: {}", me.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Single-message pipeline: guard by recency and header match, then extract OTP and mark SEEN.
     * Kept small to reduce cognitive complexity in the caller.
     */
    private static Optional<String> maybeOtpFromMessage(
            Message msg, Date testStartTime, String expectedSubject, String senderNeedle
    ) throws MessagingException, EmailContentException {
        if (!isRecent(msg, testStartTime)) return Optional.empty();
        if (!matchesSubjectAndSender(msg, expectedSubject, senderNeedle)) return Optional.empty();

        Optional<String> otp = extractOtpFromMessage(msg);
        if (otp.isPresent()) {
            msg.setFlag(Flags.Flag.SEEN, true);
        }
        return otp;
    }

    private static boolean isRecent(Message msg, Date testStartTime) throws MessagingException {
        Date received = msg.getReceivedDate();
        return received != null && !received.before(testStartTime);
    }

    private static boolean matchesSubjectAndSender(Message msg, String expectedSubject, String senderNeedle)
            throws MessagingException {
        String subj = msg.getSubject();
        boolean subjectMatches = subj != null && subj.trim().equalsIgnoreCase(expectedSubject);

        Address[] froms = msg.getFrom();
        String from = (froms != null && froms.length > 0) ? froms[0].toString() : "";
        boolean senderMatches = from.toLowerCase(Locale.ROOT).contains(senderNeedle);

        TestUtils.log().debug("Checking email: from='{}', subject='{}'", from, subj);
        return subjectMatches && senderMatches;
    }

    private static Optional<String> extractOtpFromMessage(Message msg) throws EmailContentException {
        String content = getTextFromMessage(msg);
        if (content == null) return Optional.empty();
        String otp = extractOtp(content);
        return Optional.ofNullable(otp);
    }

    /**
     * Extracts plain text content from an email Message.
     * Supports "text/plain" or multipart messages.
     *
     * @throws EmailContentException when the content cannot be read
     */
    private static String getTextFromMessage(Message message) throws EmailContentException {
        try {
            if (message.isMimeType("text/plain")) {
                Object content = message.getContent();
                return content != null ? content.toString() : null;
            } else if (message.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) message.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart part = multipart.getBodyPart(i);
                    if (part.isMimeType("text/plain")) {
                        Object inner = part.getContent();
                        return inner != null ? inner.toString() : null;
                    }
                }
            }
            return null; // no plain text found
        } catch (Exception e) {
            throw new EmailContentException("Unable to read email content: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the OTP from email content by searching for 4 to 8 digit numbers.
     */
    private static String extractOtp(String content) {
        // Matches 4 to 8 consecutive digits followed by optional non-digit characters
        Pattern pattern = Pattern.compile("(\\d{4,8})\\D?");
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Deletes the most recent OTP email from inbox that matches the subject and sender,
     * but only if the email is already marked as read.
     * NOTE: Logic preserved; only scans a small "tail window" of latest messages and prefetches headers.
     * Refactored to try-with-resources and reduced complexity.
     */
    public static void deleteOtpEmails() {
        String subjectLine = KEY_OTP_SUBJECT;
        String senderEmail = KEY_OTP_SENDER;

        int tailWindow = resolveTailWindow();

        Properties props = new Properties();
        props.put(PROP_STORE_PROTOCOL, PROTOCOL_IMAPS);
        props.put(PROP_IMAPS_SSL_ENABLE, "true");

        try (ImapContext ctx = openImapContext(props, KEY_MAIL_USERNAME, KEY_MAIL_PASSWORD)) {
            Folder inbox = ctx.inbox();

            int total = inbox.getMessageCount();
            if (total == 0) {
                TestUtils.log().warn("Mailbox empty during delete scan.");
                return;
            }

            int start = Math.max(1, total - tailWindow + 1);
            Optional<Message> latestOtpMessage =
                    findLatestReadMatchingMessage(inbox, start, total, subjectLine, senderEmail);

            if (latestOtpMessage.isPresent()) {
                latestOtpMessage.get().setFlag(Flags.Flag.DELETED, true);
                ctx.enableExpungeOnClose(); // expunge when ctx closes
                TestUtils.log().info("Deleted latest read OTP email from {}", senderEmail);
            } else {
                TestUtils.log().warn("No read OTP email found to delete for {}", senderEmail);
            }
        } catch (Exception e) {
            TestUtils.log().fatal("Failed to delete OTP emails: {}", e.getMessage());
        }
    }

    // ---------- helpers ----------

    /** Accepts nullable CSV string to avoid Optional in parameter position. */
    private static List<String> splitEmails(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList(); // unmodifiable
    }

    private static InternetAddress[] toAddresses(List<String> emails) {
        return emails.stream()
                .map(EmailManager::toInternetAddressSafe)
                .filter(Objects::nonNull)
                .toArray(InternetAddress[]::new);
    }

    private static InternetAddress toInternetAddressSafe(String email) {
        try {
            return new InternetAddress(email);
        } catch (AddressException e) {
            TestUtils.log().warn("Invalid email skipped: {}", email);
            return null;
        }
    }

    private static SmtpConfig resolveSmtp(String provider) {
        String p = Objects.toString(provider, ""); // avoids NPE
        return switch (p) {
            case PROVIDER_OUTLOOK -> new SmtpConfig(SMTP_HOST_O365, SMTP_PORT_TLS);
            case PROVIDER_GMAIL   -> new SmtpConfig(SMTP_HOST_GMAIL, SMTP_PORT_TLS);
            default               -> new SmtpConfig(SMTP_HOST_GMAIL, SMTP_PORT_TLS); // fallback
        };
    }

    private static Session buildSmtpSession(String host, String port, String fromEmail, String password) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(fromEmail, password);
            }
        });
    }

    private static void addAttachments(Multipart multipart, File[] attachments) {
        if (attachments == null || attachments.length == 0) {
            return;
        }

        Stream.of(attachments)
                .filter(Objects::nonNull)
                .filter(File::exists)
                .forEach(file -> {
                    try {
                        MimeBodyPart attach = new MimeBodyPart();
                        DataSource source = new FileDataSource(file);
                        attach.setDataHandler(new DataHandler(source));
                        attach.setFileName(file.getName());
                        multipart.addBodyPart(attach);

                        if (TestUtils.log().isInfoEnabled()) {
                            TestUtils.log().info("Attachment added: {}", file.getName());
                        }
                    } catch (MessagingException e) {
                        TestUtils.log().warn("Failed to add attachment {}: {}", file.getName(), e.getMessage());
                    }
                });
    }

    /** Resolve tail window with sane minimum and logging on parsing issues. */
    private static int resolveTailWindow() {
        int tailWindow = OTP_TAIL_DEFAULT;
        try {
            String tw = KEY_OTP_TAIL_WINDOW;
            if (tw != null && !tw.isBlank()) {
                tailWindow = Math.max(OTP_TAIL_MIN, Integer.parseInt(tw.trim()));
            }
        } catch (Exception e) {
            TestUtils.log().warn("Invalid {} value. Falling back to default={}. Reason: {}",
                    KEY_OTP_TAIL_WINDOW, OTP_TAIL_DEFAULT, e.getMessage());
        }
        return tailWindow;
    }

    /**
     * Finds the newest (latest) message in [start, end] that is already READ (SEEN)
     * and matches the given subject and sender.
     * Uses prefetch (ENVELOPE, FLAGS) to reduce round trips.
     * No break/continue usage in the caller; single linear pass here.
     */
    private static Optional<Message> findLatestReadMatchingMessage(
            Folder inbox, int start, int end, String subjectLine, String senderEmail) throws MessagingException {

        if (end < start) {
            return Optional.empty();
        }

        Message[] messages = inbox.getMessages(start, end);

        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.FLAGS);
        inbox.fetch(messages, fp);

        String senderNeedle = senderEmail == null ? "" : senderEmail.toLowerCase(Locale.ROOT);

        for (int i = messages.length - 1; i >= 0; i--) {
            Message msg = messages[i];

            boolean isRead = msg.isSet(Flags.Flag.SEEN);
            if (isRead) {
                String subj = msg.getSubject();
                boolean subjectMatches = subj != null && subj.trim().equalsIgnoreCase(subjectLine);

                Address[] froms = msg.getFrom();
                String from = (froms != null && froms.length > 0) ? froms[0].toString() : "";
                boolean senderMatches = from.toLowerCase(Locale.ROOT).contains(senderNeedle);

                TestUtils.log().debug("Checking message for delete: from='{}', subject='{}'", from, subj);

                if (subjectMatches && senderMatches) {
                    return Optional.of(msg); // newest match
                }
            }
        }

        return Optional.empty();
    }

    // ---- Dedicated Exceptions ----

    /** Thrown for OTP-specific failures (connection, timeout, interruption, etc.). */
    public static class OtpReadException extends RuntimeException {
        public OtpReadException(String message) { super(message); }
        public OtpReadException(String message, Throwable cause) { super(message, cause); }
    }

    /** Thrown when email content cannot be extracted/read. */
    public static class EmailContentException extends Exception {
        public EmailContentException(String message, Throwable cause) { super(message, cause); }
    }

    /** Sleeps up to RETRY_SLEEP_MS, but never beyond the overall deadline. */
    private static void waitBeforeRetry(long deadline) throws InterruptedException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            return; // out of time; loop will exit on next check
        }
        long sleepMs = Math.min(RETRY_SLEEP_MS, remaining);
        TestUtils.log().info("Waiting {} second(s) before retry...", java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(sleepMs));
        java.util.concurrent.TimeUnit.MILLISECONDS.sleep(sleepMs);
    }
}

