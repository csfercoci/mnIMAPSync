package com.marcnuri.mnimapsync.index;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Target-specific state used to reconcile a logical message without changing its MessageId.
 */
public final class MessageState {

    private final long uid;
    private final String contentDigest;

    private MessageState(long uid, String contentDigest) {
        this.uid = uid;
        this.contentDigest = contentDigest;
    }

    public static MessageState from(Message message, long uid) throws MessagingException {
        return new MessageState(uid, contentDigest(message));
    }

    public static String contentDigest(Message message) throws MessagingException {
        try (InputStream inputStream = message.getInputStream()) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            for (int read; (read = inputStream.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
            return toHex(digest.digest());
        } catch (IOException exception) {
            throw new MessagingException("Unable to read message content", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public long getUid() {
        return uid;
    }

    public boolean hasSameContent(Message message) throws MessagingException {
        return contentDigest.equals(contentDigest(message));
    }

    private static String toHex(byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(String.format("%02x", current & 0xff));
        }
        return result.toString();
    }
}
