package com.marcnuri.mnimapsync.imap;

import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.Utility;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.MessageSet;
import com.sun.mail.util.ASCIIUtility;
import com.sun.mail.util.BASE64DecoderStream;
import com.sun.mail.util.ReadableMime;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * @author kristian
 * @since  29.02.2024
 */
public class MessageHelper {

    private MimeMessage imessage;
    private static final List<String> DO_NOT_REPLY = Collections.unmodifiableList(Arrays.asList(
            "noreply",
            "no.reply",
            "no-reply",
            "donotreply",
            "do.not.reply",
            "do-not-reply",
            "nicht.antworten"
    ));

    static final String FLAG_FORWARDED = "$Forwarded";
    static final String FLAG_JUNK = "$Junk";
    static final String FLAG_NOT_JUNK = "$NotJunk";
    static final String FLAG_CLASSIFIED = "$Classified";
    static final String FLAG_FILTERED = "$Filtered";
    static final String FLAG_DELIVERED = "$Delivered";
    static final String FLAG_NOT_DELIVERED = "$NotDelivered";
    static final String FLAG_DISPLAYED = "$Displayed";
    static final String FLAG_NOT_DISPLAYED = "$NotDisplayed";
    static final String FLAG_COMPLAINT = "Complaint";
    static final String FLAG_LOW_IMPORTANCE = "$LowImportance";
    static final String FLAG_HIGH_IMPORTANCE = "$HighImportance";

    // https://www.iana.org/assignments/imap-jmap-keywords/imap-jmap-keywords.xhtml
    // Not black listed: Gmail $Phishing
    private static final List<String> FLAG_BLACKLIST = Collections.unmodifiableList(Arrays.asList(
            FLAG_FORWARDED,
            FLAG_JUNK,
            FLAG_NOT_JUNK,
            FLAG_CLASSIFIED, // FairEmail
            FLAG_FILTERED, // FairEmail
            FLAG_LOW_IMPORTANCE, // FairEmail
            FLAG_HIGH_IMPORTANCE, // FairEmail
            "Sent",
            "$MDNSent", // https://tools.ietf.org/html/rfc3503
            "$SubmitPending",
            "$Submitted",
            "Junk",
            "NonJunk",
            "$recent",
            "DTAG_document",
            "DTAG_image",
            "$X-Me-Annot-1",
            "$X-Me-Annot-2",
            "\\Unseen", // Mail.ru
            "$sent", // Kmail
            "$attachment", // Kmail
            "$signed", // Kmail
            "$encrypted", // Kmail
            "$HasAttachment", // Dovecot
            "$HasNoAttachment", // Dovecot
            "$IsTrusted", // Fastmail
            "$X-ME-Annot-2", // Fastmail
            "$purchases", // mailbox.org
            "$social" // mailbox.org
    ));
    private static final List<Charset> CHARSET16 = Collections.unmodifiableList(Arrays.asList(
            StandardCharsets.UTF_16,
            StandardCharsets.UTF_16BE,
            StandardCharsets.UTF_16LE
    ));

    public MessageHelper(Message message) {
        imessage= (MimeMessage) message;
    }

    private String fixEncoding(String name, String header) {
        if (header.trim().startsWith("=?"))
            return header;

        Charset detected = CharsetHelper.detect(header, StandardCharsets.ISO_8859_1);
        if (detected == null && CharsetHelper.isUTF8(header))
            detected = StandardCharsets.UTF_8;
        if (detected == null ||
                CHARSET16.contains(detected) ||
                StandardCharsets.US_ASCII.equals(detected) ||
                StandardCharsets.ISO_8859_1.equals(detected))
            return header;
        return new String(header.getBytes(StandardCharsets.ISO_8859_1), detected);
    }
    public String getSubject() throws MessagingException {
        ensureHeaders();

        String subject = imessage.getHeader("Subject", null);
        if (subject == null)
            return null;

        subject = fixEncoding("subject", subject);
        subject = subject.replaceAll("\\?=[\\r\\n\\t ]+=\\?", "\\?==\\?");
        subject = MimeUtility.unfold(subject);
        subject = decodeMime(subject);

        return subject
                .trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace("\u00ad", "");  // soft hyphen
    }


    private Address[] getAddressHeader(String name) throws MessagingException {
        ensureHeaders();

        String header = imessage.getHeader(name, ",");
        if (header == null)
            return null;

        header = fixEncoding(name, header);
        header = header.replaceAll("\\?=[\\r\\n\\t ]+=\\?", "\\?==\\?");
        Address[] addresses = InternetAddress.parseHeader(header, false);

        List<Address> result = new ArrayList<>();
        for (Address address : addresses) {
            InternetAddress iaddress = (InternetAddress) address;
            String email = iaddress.getAddress();
            String personal = iaddress.getPersonal();
            if (!StringUtils.isEmpty(personal))
                personal = personal.replace("\u00ad",""); // soft hyphen

            if (StringUtils.isEmpty(email) && StringUtils.isEmpty(personal))
                continue;

            if (personal != null && personal.equals(email))
                try {
                    iaddress.setPersonal(null);
                    personal = null;
                } catch (UnsupportedEncodingException ex) {
                  //log
                }

            if (email != null) {
                email = decodeMime(email);
                iaddress.setAddress(email);
            }

            if (personal != null) {
                try {
                    iaddress.setPersonal(decodeMime(personal));
                } catch (UnsupportedEncodingException ex) {
                }
            }

            result.add(address);
        }

        return (result.size() == 0 ? null : result.toArray(new Address[0]));
    }

  public  Address[] getFrom() throws MessagingException {
        Address[] address = getAddressHeader("From");
        if (address == null)
            address = getAddressHeader("Sender");
        return address;
    }

    public Address[] getTo() throws MessagingException {
        return getAddressHeader("To");
    }

    public Address[] getCc() throws MessagingException {
        return getAddressHeader("Cc");
    }

    public Address[] getBcc() throws MessagingException {
        return getAddressHeader("Bcc");
    }

    Address[] getReply() throws MessagingException {
        return getAddressHeader("Reply-To");
    }
    private static class MimeTextPart {
        String charset;
        String encoding;
        String text;

        MimeTextPart(String text) {
            this.text = text;
        }

        MimeTextPart(String charset, String encoding, String text) {
            this.charset = charset;
            this.encoding = encoding;
            this.text = text;
        }

        @Override
        public String toString() {
            if (charset == null)
                return text;

            try {
                return decodeMime(new String(decodeWord(text, encoding, charset), charset));
            } catch (Throwable ex) {
                String word = "=?" + charset + "?" + encoding + "?" + text + "?=";
                return word;
            }
        }
    }


    static byte[] decodeWord(String word, String encoding, String charset) throws IOException {
        String e = encoding.trim();
        if (e.equalsIgnoreCase("B"))
            while (word.startsWith("="))
                word = word.substring(1);
        ByteArrayInputStream bis = new ByteArrayInputStream(ASCIIUtility.getBytes(word));

        InputStream is;
        if (e.equalsIgnoreCase("B"))
            is = new BASE64DecoderStream(bis);
        else if (e.equalsIgnoreCase("Q"))
            is = new QDecoderStreamEx(bis);
        else {
            return word.getBytes(charset);
        }

        int count = bis.available();
        byte[] bytes = new byte[count];
        count = is.read(bytes, 0, count);

        return Arrays.copyOf(bytes, count);
    }


    public static String decodeMime(String text) {
        if (text == null)
            return null;

        // https://tools.ietf.org/html/rfc2045
        // https://tools.ietf.org/html/rfc2047
        // encoded-word = "=?" charset "?" encoding "?" encoded-text "?="

        int s, q1, q2, e, i = 0;
        List<MimeTextPart> parts = new ArrayList<>();
        while (i < text.length()) {
            s = text.indexOf("=?", i);
            if (s < 0)
                break;

            q1 = text.indexOf("?", s + 2);
            if (q1 < 0)
                break;

            q2 = text.indexOf("?", q1 + 1);
            if (q2 < 0)
                break;

            e = text.indexOf("?=", q2 + 1);
            if (e < 0)
                break;

            String plain = text.substring(i, s);
            if (!StringUtils.isEmpty(plain))
                parts.add(new MimeTextPart(plain));

            parts.add(new MimeTextPart(
                    text.substring(s + 2, q1),
                    text.substring(q1 + 1, q2),
                    text.substring(q2 + 1, e)));

            i = e + 2;
        }

        if (i < text.length())
            parts.add(new MimeTextPart(text.substring(i)));

        // Fold words to not break encoding
        int p = 0;
        while (p + 1 < parts.size()) {
            MimeTextPart p1 = parts.get(p);
            MimeTextPart p2 = parts.get(p + 1);
            // https://bugzilla.mozilla.org/show_bug.cgi?id=1374149
            if (!"ISO-2022-JP".equalsIgnoreCase(p1.charset) &&
                    p1.charset != null && p1.charset.equalsIgnoreCase(p2.charset) &&
                    p1.encoding != null && p1.encoding.equalsIgnoreCase(p2.encoding) &&
                    p1.text != null && !p1.text.endsWith("=")) {
                /*
                try {
                    byte[] b1 = decodeWord(p1.text, p1.encoding, p1.charset);
                    byte[] b2 = decodeWord(p2.text, p2.encoding, p2.charset);
                    if (CharsetHelper.isValid(b1, p1.charset) && CharsetHelper.isValid(b2, p2.charset)) {
                        p++;
                        continue;
                    }

                    byte[] b = new byte[b1.length + b2.length];
                    System.arraycopy(b1, 0, b, 0, b1.length);
                    System.arraycopy(b2, 0, b, b1.length, b2.length);
                    p1.text = new String(b, p1.charset);
                    p1.charset = null;
                    p2.encoding = null;
                    parts.remove(p + 1);
                    continue;
                } catch (Throwable ex) {
                    Log.w(ex);
                }
                */
                p1.text+= p2.text;
                parts.remove(p + 1);
            } else
                p++;
        }

        StringBuilder sb = new StringBuilder();
        for (MimeTextPart part : parts)
            sb.append(part);
        return sb.toString();
    }




    private void ensureHeaders() throws MessagingException {
        _ensureMessage(false, true);
    }

    private void _ensureMessage(boolean structure, boolean headers) throws MessagingException {
        try {
                if (structure)
                    imessage.getContentType(); // force loadBODYSTRUCTURE
                else {
                    if (headers)
                        imessage.getAllHeaders(); // force loadHeaders
                    else
                        imessage.getMessageID(); // force loadEnvelope
                }

        } catch (MessagingException ex) {
            // https://javaee.github.io/javamail/FAQ#imapserverbug
            if ("Failed to load IMAP envelope".equals(ex.getMessage()) ||
                    "Unable to load BODYSTRUCTURE".equals(ex.getMessage()))
                    if (false)
                        ((IMAPFolder) imessage.getFolder()).doCommand(new IMAPFolder.ProtocolCommand() {
                            @Override
                            public Object doCommand(IMAPProtocol p) throws ProtocolException {
                                MessageSet[] set = Utility.toMessageSet(new Message[]{imessage}, null);
                                Response[] r = p.fetch(set, p.isREV1() ? "BODY.PEEK[]" : "RFC822");
                                p.notifyResponseHandlers(r);
                                p.handleResult(r[r.length - 1]);
                                return null;
                            }
                        });


            else
                throw ex;
        }
    }



    static boolean hasCapability(IMAPFolder ifolder, final String capability) throws MessagingException {
        // Folder can have different capabilities than the store
        return (boolean) ifolder.doCommand(new IMAPFolder.ProtocolCommand() {
            @Override
            public Object doCommand(IMAPProtocol protocol) throws ProtocolException {
                return protocol.hasCapability(capability);
            }
        });
    }

    static String sanitizeKeyword(String keyword) {
        // https://tools.ietf.org/html/rfc3501
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keyword.length(); i++) {
            // flag-keyword    = atom
            // atom            = 1*ATOM-CHAR
            // ATOM-CHAR       = <any CHAR except atom-specials>
            // CHAR8           = %x01-ff ; any OCTET except NUL, %x00
            // So, basically ISO 8859-1
            char kar = keyword.charAt(i);
            // atom-specials   = "(" / ")" / "{" / SP / CTL / list-wildcards / quoted-specials / resp-specials
            if (kar == '(' || kar == ')' || kar == '{' || kar == ' ' || Character.isISOControl(kar))
                continue;
            // list-wildcards  = "%" / "*"
            if (kar == '%' || kar == '*')
                continue;
            // quoted-specials = DQUOTE / "\"
            if (kar == '"' || kar == '\\')
                continue;
            // resp-specials   = "]"
            if (kar == ']')
                continue;
            sb.append(kar);
        }

        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFKD)
                .replaceAll("[^\\p{ASCII}]", "");
    }




    static InternetAddress[] dedup(InternetAddress[] addresses) {
        if (addresses == null)
             return null;

        List<String> emails = new ArrayList<>();
        List<InternetAddress> result = new ArrayList<>();
        for (InternetAddress address : addresses) {
            String email = address.getAddress();
            if (!emails.contains(email)) {
                emails.add(email);
                result.add(address);
            }
        }

        return result.toArray(new InternetAddress[0]);
    }

    static Address[] removeGroups(Address[] addresses) {
        if (addresses == null)
            return null;

        List<Address> result = new ArrayList<>();

        for (Address address : addresses) {
            if (address instanceof InternetAddress && ((InternetAddress) address).isGroup())
                continue;
            result.add(address);
        }

        return result.toArray(new Address[0]);
    }

}
